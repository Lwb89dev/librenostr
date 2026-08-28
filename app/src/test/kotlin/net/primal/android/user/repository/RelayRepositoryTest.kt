package net.primal.android.user.repository

import androidx.room.withTransaction
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import net.primal.android.networking.relays.FALLBACK_RELAY_URLS
import net.primal.android.nostr.publish.NostrPublisher
import net.primal.android.user.db.Relay as RelayPO
import net.primal.android.user.db.RelayDao
import net.primal.android.user.db.UsersDatabase
import net.primal.android.user.domain.Relay
import net.primal.android.user.domain.RelayKind
import net.primal.core.testing.CoroutinesTestRule
import net.primal.data.remote.api.users.UsersApi
import net.primal.data.remote.api.users.model.UsersRelaysResponse
import net.primal.domain.common.PrimalEvent
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.relay.RelayEventQuerier
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@Suppress("MaxLineLength")
@OptIn(ExperimentalCoroutinesApi::class)
class RelayRepositoryTest {

    @get:Rule
    val coroutinesTestRule = CoroutinesTestRule()

    @Before
    fun setup() {
        mockkStatic("androidx.room.RoomDatabaseKt")
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    private fun buildPrimalUserRelaysListEvent(relays: List<String>): PrimalEvent {
        return PrimalEvent(
            kind = NostrEventKind.PrimalUserRelaysList.value,
            tags = mutableListOf<JsonArray>().apply {
                relays.forEach {
                    add(
                        buildJsonArray {
                            add("r")
                            add(it)
                        },
                    )
                }
            },
        )
    }

    private fun buildRelayDao(): RelayDao = mockk(relaxed = true)

    @Suppress("UNCHECKED_CAST")
    private fun buildUsersDatabase(relayDao: RelayDao = buildRelayDao()): UsersDatabase {
        val db = mockk<UsersDatabase>(relaxed = true) {
            every { relays() } returns relayDao
        }
        coEvery { db.withTransaction(any<suspend () -> Any?>()) } coAnswers {
            val block = args[1] as suspend () -> Any?
            block()
        }
        return db
    }

    private fun buildNip65Event(
        pubkey: String,
        createdAt: Long,
        relays: List<String>,
    ): NostrEvent {
        return NostrEvent(
            id = "id-$createdAt",
            pubKey = pubkey,
            createdAt = createdAt,
            kind = NostrEventKind.RelayListMetadata.value,
            tags = relays.map { url ->
                buildJsonArray {
                    add("r")
                    add(url)
                }
            },
            content = "",
            sig = "sig",
        )
    }

    private fun buildRepository(
        usersDatabase: UsersDatabase = buildUsersDatabase(),
        usersApi: UsersApi = mockk(relaxed = true),
        nostrPublisher: NostrPublisher = mockk(relaxed = true),
        relayEventQuerier: RelayEventQuerier = mockk(relaxed = true) {
            coEvery { query(any()) } returns emptyList()
        },
    ): RelayRepository {
        return RelayRepository(
            dispatchers = coroutinesTestRule.dispatcherProvider,
            usersDatabase = usersDatabase,
            usersApi = usersApi,
            nostrPublisher = nostrPublisher,
            relayEventQuerier = relayEventQuerier,
        )
    }

    @Test
    fun `bootstrapDefaultUserRelays publishes fallback relays`() =
        runTest {
            val userId = "random"
            val nostrPublisher = mockk<NostrPublisher>(relaxed = true)
            val usersApi = mockk<UsersApi>(relaxed = true)
            val relayDao = buildRelayDao()
            val usersDatabase = buildUsersDatabase(relayDao)

            val repository = buildRepository(
                usersDatabase = usersDatabase,
                usersApi = usersApi,
                nostrPublisher = nostrPublisher,
            )

            repository.bootstrapDefaultUserRelays(userId = userId)

            coVerify(exactly = 0) { usersApi.getDefaultRelays() }
            coVerify {
                nostrPublisher.publishRelayList(
                    userId = userId,
                    relays = withArg { relays ->
                        relays.map { it.url }.sorted() shouldBe FALLBACK_RELAY_URLS.sorted()
                    },
                )
            }
        }

    @Test
    fun `bootstrapDefaultUserRelays persists relays to database`() =
        runTest {
            val userId = "random"
            val relayDao = buildRelayDao()
            val usersDatabase = buildUsersDatabase(relayDao)
            val upsertedRelaysSlot = slot<List<RelayPO>>()

            coEvery { relayDao.upsertAll(capture(upsertedRelaysSlot)) } returns Unit

            val repository = buildRepository(usersDatabase = usersDatabase)

            repository.bootstrapDefaultUserRelays(userId = userId)

            coVerify { relayDao.deleteAll(userId = userId, kind = RelayKind.UserRelay) }
            upsertedRelaysSlot.captured.map { it.url }.sorted() shouldBe FALLBACK_RELAY_URLS.sorted()
        }

    @Test
    fun `removeRelayAndPublishRelayList removes relay and publishes updated list`() =
        runTest {
            val userId = "random"
            val relays = listOf("wss://relay.primal.net", "wss://nostr1.current.fyi/")
            val nostrPublisher = mockk<NostrPublisher>(relaxed = true)
            val relayDao = buildRelayDao()
            val usersDatabase = buildUsersDatabase(relayDao)

            val repository = buildRepository(
                usersDatabase = usersDatabase,
                usersApi = mockk(relaxed = true) {
                    coEvery { getUserRelays(listOf(userId)) } returns UsersRelaysResponse(
                        cachedRelayListEvents = listOf(buildPrimalUserRelaysListEvent(relays = relays)),
                    )
                },
                nostrPublisher = nostrPublisher,
            )

            repository.removeRelayAndPublishRelayList(userId = userId, url = relays.first())

            // After removing "wss://relay.primal.net", remaining relays from the API are used as-is
            val expectedRelayUrls = relays.drop(1)
            coVerify {
                nostrPublisher.publishRelayList(
                    userId = userId,
                    relays = withArg { publishedRelays ->
                        publishedRelays.map { it.url } shouldBe expectedRelayUrls
                    },
                )
            }
        }

    @Test
    fun `removeRelayAndPublishRelayList removes even if relay URL is not cleaned`() =
        runTest {
            val userId = "random"
            val relays = listOf("wss://nostr1.current.fyi", "wss://relay.primal.net")
            val nostrPublisher = mockk<NostrPublisher>(relaxed = true)
            val relayDao = buildRelayDao()
            val usersDatabase = buildUsersDatabase(relayDao)

            val repository = buildRepository(
                usersDatabase = usersDatabase,
                usersApi = mockk(relaxed = true) {
                    coEvery { getUserRelays(listOf(userId)) } returns UsersRelaysResponse(
                        cachedRelayListEvents = listOf(buildPrimalUserRelaysListEvent(relays = relays)),
                    )
                },
                nostrPublisher = nostrPublisher,
            )

            // Removing with trailing slash should still match "wss://nostr1.current.fyi"
            // because removeIf compares cleaned URLs
            repository.removeRelayAndPublishRelayList(userId = userId, url = "wss://nostr1.current.fyi/")

            // Remaining relay URLs are from the API (not cleaned)
            val expectedRelayUrls = relays.drop(1)
            coVerify {
                nostrPublisher.publishRelayList(
                    userId = userId,
                    relays = withArg { publishedRelays ->
                        publishedRelays.map { it.url }.sorted() shouldBe expectedRelayUrls.sorted()
                    },
                )
            }
        }

    @Test
    fun `removeRelayAndPublishRelayList calls publish with remaining relay list`() =
        runTest {
            val userId = "random"
            val relays = listOf("wss://relay.primal.net", "wss://nostr1.current.fyi/")
            val nostrPublisher = mockk<NostrPublisher>(relaxed = true)
            val relayDao = buildRelayDao()
            val usersDatabase = buildUsersDatabase(relayDao)

            val repository = buildRepository(
                usersDatabase = usersDatabase,
                usersApi = mockk(relaxed = true) {
                    coEvery { getUserRelays(listOf(userId)) } returns UsersRelaysResponse(
                        cachedRelayListEvents = listOf(buildPrimalUserRelaysListEvent(relays = relays)),
                    )
                },
                nostrPublisher = nostrPublisher,
            )

            repository.removeRelayAndPublishRelayList(userId = userId, url = relays.first())
            // After removing first relay, remaining relays from the API are used as-is
            val expectedRelays = relays.drop(1).map { Relay(url = it, read = true, write = true) }

            coVerify {
                nostrPublisher.publishRelayList(
                    withArg { it shouldBe userId },
                    withArg { it shouldBe expectedRelays },
                )
            }
        }

    @Test
    fun `fetchAndUpdateUserRelays clears user relays if cached NIP-65 has empty tags`() =
        runTest {
            val userId = "random"
            val relayDao = buildRelayDao()
            val usersDatabase = buildUsersDatabase(relayDao)

            val repository = buildRepository(
                usersDatabase = usersDatabase,
                usersApi = mockk(relaxed = true) {
                    coEvery { getUserRelays(listOf(userId)) } returns UsersRelaysResponse(
                        cachedRelayListEvents = listOf(buildPrimalUserRelaysListEvent(relays = emptyList())),
                    )
                },
            )

            repository.fetchAndUpdateUserRelays(userId = userId)

            coVerify { relayDao.deleteAll(userId = userId, kind = RelayKind.UserRelay) }
            coVerify {
                relayDao.upsertAll(
                    relays = withArg { it shouldBe emptyList() },
                )
            }
        }

    @Test
    fun `fetchAndUpdateUserRelays ignores fetch if cached NIP-65 is missing`() =
        runTest {
            val userId = "random"
            val relayDao = buildRelayDao()
            val usersDatabase = buildUsersDatabase(relayDao)

            val repository = buildRepository(
                usersDatabase = usersDatabase,
                usersApi = mockk(relaxed = true) {
                    coEvery { getUserRelays(listOf(userId)) } returns
                        UsersRelaysResponse(cachedRelayListEvents = emptyList())
                },
            )

            repository.fetchAndUpdateUserRelays(userId = userId)

            coVerify(exactly = 0) { relayDao.deleteAll(userId = userId, kind = RelayKind.UserRelay) }
            coVerify(exactly = 0) { relayDao.upsertAll(any()) }
        }

    @Test
    fun `fetchAndUpdateUserRelays replaces relays when response has valid event`() =
        runTest {
            val userId = "random"
            val expectedRelayUrls = listOf("wss://relay.primal.net", "wss://relay.damus.io")
            val relayDao = buildRelayDao()
            val usersDatabase = buildUsersDatabase(relayDao)

            val repository = buildRepository(
                usersDatabase = usersDatabase,
                usersApi = mockk(relaxed = true) {
                    coEvery { getUserRelays(listOf(userId)) } returns UsersRelaysResponse(
                        cachedRelayListEvents = listOf(
                            buildPrimalUserRelaysListEvent(relays = expectedRelayUrls),
                        ),
                    )
                },
            )

            repository.fetchAndUpdateUserRelays(userId = userId)

            coVerify { relayDao.deleteAll(userId = userId, kind = RelayKind.UserRelay) }
            coVerify {
                relayDao.upsertAll(
                    relays = withArg { relays ->
                        relays.map { it.url }.sorted() shouldBe expectedRelayUrls.sorted()
                    },
                )
            }
        }

    @Test
    fun `fetchAndUpdateUserRelays prefers kind 10002 from relays over cache`() =
        runTest {
            val userId = "random"
            val relayDao = buildRelayDao()
            val usersDatabase = buildUsersDatabase(relayDao)
            val usersApi = mockk<UsersApi>(relaxed = true) {
                coEvery { getUserRelays(listOf(userId)) } returns UsersRelaysResponse(
                    cachedRelayListEvents = listOf(
                        buildPrimalUserRelaysListEvent(relays = listOf("wss://from.cache")),
                    ),
                )
            }
            val querier = mockk<RelayEventQuerier> {
                coEvery { query(any()) } returns listOf(
                    buildNip65Event(
                        pubkey = userId,
                        createdAt = 100,
                        relays = listOf("wss://from.relay"),
                    ),
                )
            }

            val repository = buildRepository(
                usersDatabase = usersDatabase,
                usersApi = usersApi,
                relayEventQuerier = querier,
            )

            repository.fetchAndUpdateUserRelays(userId = userId)

            coVerify(exactly = 0) { usersApi.getUserRelays(any<List<String>>()) }
            coVerify {
                relayDao.upsertAll(
                    relays = withArg { relays ->
                        relays.map { it.url } shouldBe listOf("wss://from.relay")
                    },
                )
            }
        }

    @Test
    fun `fetchAndUpdateUserRelays uses the latest kind 10002 event`() =
        runTest {
            val userId = "random"
            val relayDao = buildRelayDao()
            val usersDatabase = buildUsersDatabase(relayDao)
            val querier = mockk<RelayEventQuerier> {
                coEvery { query(any()) } returns listOf(
                    buildNip65Event(userId, createdAt = 10, relays = listOf("wss://old.example")),
                    buildNip65Event(userId, createdAt = 50, relays = listOf("wss://new.example")),
                    buildNip65Event(userId, createdAt = 20, relays = listOf("wss://mid.example")),
                )
            }

            val repository = buildRepository(
                usersDatabase = usersDatabase,
                relayEventQuerier = querier,
            )

            repository.fetchAndUpdateUserRelays(userId = userId)

            coVerify {
                relayDao.upsertAll(
                    relays = withArg { relays ->
                        relays.map { it.url } shouldBe listOf("wss://new.example")
                    },
                )
            }
        }

    @Test
    fun `fetchAndUpdateUserRelays falls back to cache when relays return nothing`() =
        runTest {
            val userId = "random"
            val expectedRelayUrls = listOf("wss://from.cache")
            val relayDao = buildRelayDao()
            val usersDatabase = buildUsersDatabase(relayDao)
            val querier = mockk<RelayEventQuerier> {
                coEvery { query(any()) } returns emptyList()
            }

            val repository = buildRepository(
                usersDatabase = usersDatabase,
                usersApi = mockk(relaxed = true) {
                    coEvery { getUserRelays(listOf(userId)) } returns UsersRelaysResponse(
                        cachedRelayListEvents = listOf(
                            buildPrimalUserRelaysListEvent(relays = expectedRelayUrls),
                        ),
                    )
                },
                relayEventQuerier = querier,
            )

            repository.fetchAndUpdateUserRelays(userId = userId)

            coVerify {
                relayDao.upsertAll(
                    relays = withArg { relays ->
                        relays.map { it.url }.sorted() shouldBe expectedRelayUrls.sorted()
                    },
                )
            }
        }

    @Test
    fun `updateRelayPermissionsAndPublishRelayList publishes read write flags`() =
        runTest {
            val userId = "random"
            val relays = listOf("wss://relay.one", "wss://relay.two")
            val nostrPublisher = mockk<NostrPublisher>(relaxed = true)
            val querier = mockk<RelayEventQuerier> {
                coEvery { query(any()) } returns listOf(
                    buildNip65Event(userId, createdAt = 1, relays = relays),
                )
            }

            val repository = buildRepository(
                usersDatabase = buildUsersDatabase(),
                nostrPublisher = nostrPublisher,
                relayEventQuerier = querier,
            )

            repository.updateRelayPermissionsAndPublishRelayList(
                userId = userId,
                url = "wss://relay.one",
                read = true,
                write = false,
            )

            coVerify {
                nostrPublisher.publishRelayList(
                    userId = userId,
                    relays = withArg { published ->
                        published.find { it.url == "wss://relay.one" } shouldBe
                            Relay(url = "wss://relay.one", read = true, write = false)
                        published.find { it.url == "wss://relay.two" }?.write shouldBe true
                    },
                )
            }
        }
}
