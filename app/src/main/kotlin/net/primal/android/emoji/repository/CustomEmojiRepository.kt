package net.primal.android.emoji.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aakira.napier.Napier
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.primal.android.emoji.BuiltInEmojiPacks
import net.primal.android.emoji.Nip30
import net.primal.android.emoji.model.CustomEmoji
import net.primal.android.emoji.model.EmojiPack
import net.primal.android.emoji.model.UserEmojiList
import net.primal.android.emoji.model.emojiPackAddress
import net.primal.android.networking.relays.RelayPoolQueryResult
import net.primal.android.networking.relays.RelaysSocketManager
import net.primal.android.nostr.publish.NostrPublisher
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.getOrDefault
import net.primal.core.utils.onFailure
import net.primal.core.utils.runCatching
import net.primal.core.utils.serialization.decodeFromJsonStringOrNull
import net.primal.core.utils.serialization.encodeToJsonString
import net.primal.domain.nostr.Naddr
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.NostrUnsignedEvent

/**
 * The active account's NIP-30 emoji library: the packs it picked (its kind 10030 emoji list), the
 * packs it made (its kind 30030 emoji sets), and the packs bundled with the app.
 *
 * Read side: [library] is served from a small per-account JSON cache the moment the account
 * becomes active, then refreshed from relays on demand ([ensureFresh]) — the picker opens with
 * what was there last time instead of waiting on the network.
 *
 * Write side: every change to the emoji list is a read-modify-write of a replaceable event that
 * other clients edit too. It always starts from the newest copy relays have (see
 * [latestUserListOrThrow]), and refuses to write at all when no relay answered, because "no
 * relay answered" and "you have no list" look the same in an empty result — and treating the
 * first as the second would publish an empty list over the user's real one.
 */
@Singleton
class CustomEmojiRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val activeAccountStore: ActiveAccountStore,
    private val relaysSocketManager: RelaysSocketManager,
    private val nostrPublisher: NostrPublisher,
) {

    private val scope = CoroutineScope(dispatchers.io() + SupervisorJob())
    private val mutex = Mutex()

    private val _library = MutableStateFlow(EmojiLibrary())
    val library: StateFlow<EmojiLibrary> = _library.asStateFlow()

    init {
        scope.launch {
            // A StateFlow, so it already only emits when the account actually changes.
            activeAccountStore.activeUserId
                .collect { userId -> mutex.withLock { _library.value = loadCached(userId) } }
        }
    }

    /** Refreshes from relays unless the library was refreshed within [maxAge]. */
    suspend fun ensureFresh(maxAge: Duration = REFRESH_INTERVAL) {
        val current = _library.value
        val age = System.currentTimeMillis() - current.refreshedAtMillis
        if (current.userId.isNotEmpty() && age < maxAge.inWholeMilliseconds) return
        refresh()
    }

    /** Fetches the account's emoji list and every pack it refers to, plus the packs it authored. */
    suspend fun refresh() =
        mutex.withLock {
            val userId = activeAccountStore.activeUserId()
            if (userId.isEmpty()) return@withLock
            val current = _library.value.takeIf { it.userId == userId } ?: loadCached(userId)

            val userList = newestOf(current.userList, fetchUserList(userId).list)
            val ownPacks = queryPacks(authorsFilter(kind = NostrEventKind.EmojiSet.value, authors = listOf(userId)))
                .associateBy { it.address }
            val known = mergeNewest(current.packs, ownPacks)
            val referenced = fetchPacksByAddress(userList?.packAddresses.orEmpty().filterNot { it in ownPacks })

            val updated = current.copy(
                userId = userId,
                userList = userList,
                packs = mergeNewest(known, referenced),
                refreshedAtMillis = System.currentTimeMillis(),
            )
            store(updated)
        }

    /** Recent emoji packs published on the account's relays, newest first — for discovery. */
    suspend fun discoverPacks(limit: Int = DISCOVERY_LIMIT): List<EmojiPack> {
        val filter = buildJsonObject {
            putJsonArray("kinds") { add(JsonPrimitive(NostrEventKind.EmojiSet.value)) }
            put("limit", limit)
        }
        return queryPacks(filter)
            .filter { it.emojis.isNotEmpty() }
            .sortedByDescending { it.createdAt }
    }

    /** The pack an `naddr` points at, or null if no relay has it. */
    suspend fun fetchPack(naddr: Naddr): EmojiPack? {
        if (naddr.kind != NostrEventKind.EmojiSet.value) return null
        return fetchPacksByAddress(listOf(emojiPackAddress(naddr.userId, naddr.identifier))).values.firstOrNull()
    }

    /** Adds [pack] to the account's emoji list, so its emoji show up in the picker. */
    suspend fun addPackToList(pack: EmojiPack) =
        updateUserList(alsoKnown = pack) { list ->
            if (pack.address in list.packAddresses) {
                list
            } else {
                list.copy(packAddresses = list.packAddresses + pack.address)
            }
        }

    /** Removes the pack at [address] from the account's emoji list; the pack itself is untouched. */
    suspend fun removePackFromList(address: String) =
        updateUserList { list -> list.copy(packAddresses = list.packAddresses - address) }

    /**
     * Publishes a pack the account authors — a new one when [identifier] is null — and makes sure
     * it is on the account's emoji list, since a pack the author cannot pick from is pointless.
     */
    suspend fun savePack(
        identifier: String?,
        title: String,
        emojis: List<CustomEmoji>,
    ): EmojiPack {
        val userId = activeAccountStore.activeUserId()
        val dTag = identifier ?: newPackIdentifier(title)
        val event = nostrPublisher.signPublishImportNostrEvent(
            NostrUnsignedEvent(
                pubKey = userId,
                kind = NostrEventKind.EmojiSet.value,
                tags = Nip30.emojiPackTags(identifier = dTag, title = title, emojis = emojis),
                content = "",
            ),
        ).nostrEvent
        val pack = requireNotNull(Nip30.parseEmojiPack(event))
        // Into the library before touching the list: if the list update fails (no relay answered),
        // the pack is still published and still shows up among the account's own packs, with an
        // Add button, instead of vanishing until the next refresh.
        mutex.withLock { store(_library.value.copy(packs = _library.value.packs + (pack.address to pack))) }
        addPackToList(pack)
        return pack
    }

    /**
     * Deletes a pack the account authored: a NIP-09 deletion request for its address, then off the
     * emoji list. Relays that honour deletions drop it; copies elsewhere may linger, as with any
     * Nostr deletion.
     */
    suspend fun deletePack(pack: EmojiPack) {
        val userId = activeAccountStore.activeUserId()
        require(pack.ownerPubkey == userId) { "Only the author of a pack can delete it." }
        nostrPublisher.signPublishImportNostrEvent(
            NostrUnsignedEvent(
                pubKey = userId,
                kind = NostrEventKind.EventDeletion.value,
                tags = listOf(stringTag("a", pack.address), stringTag("k", NostrEventKind.EmojiSet.value.toString())),
                content = "",
            ),
        )
        mutex.withLock { store(_library.value.copy(packs = _library.value.packs - pack.address)) }
        removePackFromList(pack.address)
    }

    /** @param alsoKnown a pack to keep in the library alongside the change, e.g. the one just added. */
    private suspend fun updateUserList(alsoKnown: EmojiPack? = null, change: (UserEmojiList) -> UserEmojiList) =
        mutex.withLock {
            val userId = activeAccountStore.activeUserId()
            val latest = latestUserListOrThrow(userId)
            val changed = change(latest)
            if (changed == latest) return@withLock
            val event = nostrPublisher.signPublishImportNostrEvent(
                NostrUnsignedEvent(
                    pubKey = userId,
                    kind = NostrEventKind.UserEmojiList.value,
                    tags = Nip30.userEmojiListTags(changed),
                    content = "",
                ),
            ).nostrEvent
            val packs = _library.value.packs + listOfNotNull(alsoKnown).associateBy { it.address }
            store(_library.value.copy(userList = Nip30.parseUserEmojiList(event), packs = packs))
        }

    /**
     * The newest emoji list between the cached one and what relays have now. Throws when relays
     * did not answer and nothing is cached, rather than returning an empty list a caller would
     * then publish over the real one.
     */
    private suspend fun latestUserListOrThrow(userId: String): UserEmojiList {
        val cached = _library.value.takeIf { it.userId == userId }?.userList
        val fetched = fetchUserList(userId)
        if (!fetched.answered && cached == null) {
            throw EmojiLibraryUnavailableException("No relay answered for the emoji list of $userId")
        }
        return newestOf(cached, fetched.list) ?: UserEmojiList()
    }

    private suspend fun fetchUserList(userId: String): FetchedUserList {
        val result = query(authorsFilter(kind = NostrEventKind.UserEmojiList.value, authors = listOf(userId)))
        val newest = result.events
            .filter { it.pubKey == userId }
            .mapNotNull { Nip30.parseUserEmojiList(it) }
            .maxByOrNull { it.createdAt }
        return FetchedUserList(list = newest, answered = result.eoseRelays.isNotEmpty() || newest != null)
    }

    private suspend fun fetchPacksByAddress(addresses: List<String>): Map<String, EmojiPack> {
        val coordinates = addresses.mapNotNull { it.toPackCoordinates() }
        if (coordinates.isEmpty()) return emptyMap()
        // One REQ for all of them: authors x identifiers over-matches in theory, so the result is
        // filtered back down to exactly the addresses that were asked for.
        val filter = buildJsonObject {
            putJsonArray("kinds") { add(JsonPrimitive(NostrEventKind.EmojiSet.value)) }
            putJsonArray("authors") { coordinates.map { it.first }.distinct().forEach { add(JsonPrimitive(it)) } }
            putJsonArray("#d") { coordinates.map { it.second }.distinct().forEach { add(JsonPrimitive(it)) } }
            put("limit", coordinates.size * 2)
        }
        val wanted = addresses.toSet()
        return mergeNewest(emptyMap(), queryPacks(filter).filter { it.address in wanted })
    }

    private suspend fun queryPacks(filter: JsonObject): List<EmojiPack> =
        query(filter).events.mapNotNull { Nip30.parseEmojiPack(it) }

    private suspend fun query(filter: JsonObject): RelayPoolQueryResult =
        runCatching { relaysSocketManager.queryEvents(filter) }
            .onFailure { Napier.w(it) { "Emoji query failed" } }
            .getOrDefault(RelayPoolQueryResult())

    private suspend fun loadCached(userId: String): EmojiLibrary {
        if (userId.isEmpty()) return EmojiLibrary()
        val cached = withContext(dispatchers.io()) {
            cacheFile(userId).takeIf { it.exists() }?.readText()
        }.decodeFromJsonStringOrNull<CachedLibrary>()
        return EmojiLibrary(
            userId = userId,
            userList = cached?.userList,
            packs = cached?.packs.orEmpty().associateBy { it.address },
            refreshedAtMillis = 0,
        )
    }

    /** Call under [mutex]. Publishes [library] and writes it to the account's cache file. */
    private suspend fun store(library: EmojiLibrary) {
        _library.value = library
        if (library.userId.isEmpty()) return
        val cached = CachedLibrary(userList = library.userList, packs = library.packs.values.toList())
        withContext(dispatchers.io()) {
            runCatching {
                cacheFile(library.userId).apply { parentFile?.mkdirs() }.writeText(cached.encodeToJsonString())
            }.onFailure { Napier.w(it) { "Could not write the emoji cache" } }
        }
    }

    private fun cacheFile(userId: String) = File(File(context.filesDir, CACHE_DIR), "$userId.json")

    @Serializable
    private data class CachedLibrary(
        val userList: UserEmojiList? = null,
        val packs: List<EmojiPack> = emptyList(),
    )

    private data class FetchedUserList(val list: UserEmojiList?, val answered: Boolean)

    private companion object {
        const val CACHE_DIR = "custom_emoji"
        const val DISCOVERY_LIMIT = 40
        const val OWN_PACKS_LIMIT = 100
        const val ADDRESS_PARTS = 3
        const val MAX_SLUG_LENGTH = 32
        const val RANDOM_SUFFIX_LENGTH = 6
        val REFRESH_INTERVAL = 10.minutes

        fun newestOf(a: UserEmojiList?, b: UserEmojiList?): UserEmojiList? =
            listOfNotNull(a, b).maxByOrNull { it.createdAt }

        fun mergeNewest(base: Map<String, EmojiPack>, incoming: Iterable<EmojiPack>): Map<String, EmojiPack> {
            val merged = base.toMutableMap()
            incoming.forEach { pack ->
                val existing = merged[pack.address]
                if (existing == null || pack.createdAt > existing.createdAt) merged[pack.address] = pack
            }
            return merged
        }

        fun mergeNewest(base: Map<String, EmojiPack>, incoming: Map<String, EmojiPack>) =
            mergeNewest(base, incoming.values)

        fun authorsFilter(kind: Int, authors: List<String>): JsonObject =
            buildJsonObject {
                putJsonArray("kinds") { add(JsonPrimitive(kind)) }
                putJsonArray("authors") { authors.forEach { add(JsonPrimitive(it)) } }
                put("limit", OWN_PACKS_LIMIT)
            }

        /** `30030:<pubkey>:<d>` to (pubkey, d); the d tag may itself contain colons. */
        fun String.toPackCoordinates(): Pair<String, String>? {
            val (kind, pubkey, identifier) = split(":", limit = ADDRESS_PARTS).takeIf { it.size == ADDRESS_PARTS }
                ?: return null
            return (pubkey to identifier).takeIf { kind == NostrEventKind.EmojiSet.value.toString() }
        }

        /**
         * A `d` tag for a new pack: readable (from the title) and unique (a random suffix), since two
         * packs of one author with the same `d` tag would be the same pack — the newer replacing
         * the older.
         */
        fun newPackIdentifier(title: String): String {
            val slug = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(MAX_SLUG_LENGTH)
            val suffix = Uuid.random().toHexString().take(RANDOM_SUFFIX_LENGTH)
            return if (slug.isEmpty()) "emoji-$suffix" else "$slug-$suffix"
        }

        fun stringTag(name: String, value: String): JsonArray =
            buildJsonArray {
                add(JsonPrimitive(name))
                add(JsonPrimitive(value))
            }
    }
}

/**
 * Everything the app knows about the active account's emoji.
 *
 * @property userList the account's kind 10030 list, or null if none was ever seen.
 * @property packs every kind 30030 pack known, by address: the account's own and those its list
 *   refers to.
 */
data class EmojiLibrary(
    val userId: String = "",
    val userList: UserEmojiList? = null,
    val packs: Map<String, EmojiPack> = emptyMap(),
    val refreshedAtMillis: Long = 0,
) {
    /** The packs the picker offers, in order: built in, then the list's own order. */
    val enabledPacks: List<EmojiPack>
        get() = BuiltInEmojiPacks.all + userList?.packAddresses.orEmpty().mapNotNull { packs[it] } +
            listOfNotNull(looseEmojisPack)

    /** Packs the account authored, whether or not they are on its list. */
    val ownPacks: List<EmojiPack>
        get() = packs.values.filter { it.ownerPubkey == userId }.sortedBy { it.title.lowercase() }

    /**
     * Shortcode to URL for everything in [enabledPacks], first pack winning on a clash — what a
     * note's `:shortcode:` resolves to when it is published.
     */
    val availableEmojis: Map<String, String>
        get() = buildMap { enabledPacks.forEach { pack -> pack.emojis.forEach { putIfAbsent(it.shortcode, it.url) } } }

    fun isOnList(address: String): Boolean = userList?.packAddresses?.contains(address) == true

    /** Emoji added to the list one by one (not as part of a pack), shown as a pack of their own. */
    private val looseEmojisPack: EmojiPack?
        get() = userList?.emojis?.takeIf { it.isNotEmpty() }?.let {
            EmojiPack(ownerPubkey = userId, identifier = "", title = "", emojis = it)
        }
}

class EmojiLibraryUnavailableException(message: String) : Exception(message)
