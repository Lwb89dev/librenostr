package net.primal.core.networking.tor.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Pure parsers over the guard sample Arti persists between runs, `<dataDir>/state/state/guards.json`.
 *
 * The file maps a guard-set selection name (usually `"default"`) to an object with a `"guards"`
 * array. Each guard carries, among other things:
 *
 * - `disabled`: non-null once Arti permanently retires the guard (for example
 *   `TooManyIndeterminateFailures` on a flaky network);
 * - `unlisted_since`: non-null once the guard drops out of the network consensus;
 * - `confirmed_at`: non-null once the guard has actually been used to build a circuit.
 *
 * ## The failure this exists for
 *
 * On an unreliable network Arti records circuit failures past the first hop as "indeterminate"
 * (it cannot tell whether the guard or a later hop was at fault). Past a ratio it *permanently*
 * disables the guard. Disabled guards are never re-enabled and stay in the sample, which is capped
 * at 60. Arti refills usable guards from the network when they fall below a minimum, but once the
 * sample is full of unusable ones there is no room to add any, so replenishment is wedged for good
 * and every circuit fails with "all guards down" — across restarts, because the state persists and
 * the bootstrap still "succeeds" off the cached directory.
 *
 * This was observed in the field by another app embedding the same library: a sample of 60 with 59
 * disabled and 1 usable, all sixty rejected at runtime, roughly 87 % of connections failing
 * indefinitely. The obvious check ("no usable guard left") never fired because one survivor was
 * enough to veto it, so the test here is proportional.
 *
 * File IO and the decision to wipe live in [ArtiTorEngine]; this object only reads the JSON, so it
 * can be tested against captured fixtures.
 */
object ArtiGuardState {

    /**
     * Below this many guards a low usable count is just a young sample that Arti is still filling.
     * Wiping it would loop the bootstrap, so small samples are only judged by "none usable".
     */
    const val MIN_SAMPLE_TO_JUDGE_RATIO = 10

    /** A sample is wedged when fewer than one in this many of its guards are usable. */
    const val USABLE_RATIO_DIVISOR = 10

    private val json = Json { ignoreUnknownKeys = true }

    /** Parses the file's text, or null when it is not valid JSON (treated as "nothing to judge"). */
    fun parseOrNull(text: String): JsonElement? =
        try {
            json.parseToJsonElement(text)
        } catch (@Suppress("SwallowedException") error: IllegalArgumentException) {
            null
        }

    /**
     * True when a non-empty guard sample has no usable guard, or so few that Arti cannot realistically
     * recover from them. A guard is usable when it is neither disabled nor unlisted.
     */
    fun hasNoUsableGuards(root: JsonElement): Boolean =
        guardSets(root).any { guards ->
            val usable = guards.count { !it.isSet("disabled") && !it.isSet("unlisted_since") }
            guards.isNotEmpty() &&
                (
                    usable == 0 ||
                        (guards.size >= MIN_SAMPLE_TO_JUDGE_RATIO && usable * USABLE_RATIO_DIVISOR < guards.size)
                    )
        }

    /**
     * True when the sample contains a guard Arti has *confirmed*, even one that is disabled or
     * unlisted by now. Confirmation happens only after a guard built a circuit, so it is durable
     * proof that Tor worked on this install once, surviving the process restarts that reset any
     * in-memory flag. The supervisor uses it to treat a later stuck start as stale state worth
     * wiping rather than as a slow first bootstrap worth waiting out.
     */
    fun hasConfirmedGuard(root: JsonElement): Boolean =
        guardSets(root).any { guards -> guards.any { it.isSet("confirmed_at") } }

    private fun guardSets(root: JsonElement): List<List<JsonObject>> =
        (root as? JsonObject)?.values.orEmpty().mapNotNull { selection ->
            ((selection as? JsonObject)?.get("guards") as? JsonArray)?.mapNotNull { it as? JsonObject }
        }

    private fun JsonObject.isSet(key: String): Boolean {
        val value = get(key)
        return value != null && value !is JsonNull
    }
}
