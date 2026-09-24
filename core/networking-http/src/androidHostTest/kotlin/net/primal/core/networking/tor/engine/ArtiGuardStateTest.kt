package net.primal.core.networking.tor.engine

import io.kotest.matchers.shouldBe
import org.junit.Test

@Suppress("TooManyFunctions")
class ArtiGuardStateTest {

    /** One entry of `guards.json`: usable, disabled by Arti, or dropped from the consensus. */
    private enum class Guard { USABLE, DISABLED, UNLISTED, CONFIRMED }

    private fun sample(vararg guards: Guard): String =
        guards.joinToString(prefix = """{"default":{"guards":[""", postfix = "]}}") { guard ->
            when (guard) {
                Guard.USABLE -> """{"disabled":null,"unlisted_since":null,"confirmed_at":null}"""
                Guard.DISABLED -> """{"disabled":{"reason":"x"},"unlisted_since":null,"confirmed_at":null}"""
                Guard.UNLISTED -> """{"disabled":null,"unlisted_since":"2026-09-01T00:00:00Z","confirmed_at":null}"""
                Guard.CONFIRMED -> """{"disabled":null,"unlisted_since":null,"confirmed_at":"2026-09-01T00:00:00Z"}"""
            }
        }

    private fun parse(text: String) = requireNotNull(ArtiGuardState.parseOrNull(text)) { "not valid JSON: $text" }

    private fun wedged(vararg guards: Guard) = ArtiGuardState.hasNoUsableGuards(parse(sample(*guards)))

    private fun confirmed(vararg guards: Guard) = ArtiGuardState.hasConfirmedGuard(parse(sample(*guards)))

    @Test
    fun `a healthy sample is not wedged`() {
        wedged(*Array(20) { Guard.USABLE }) shouldBe false
    }

    @Test
    fun `a sample with no usable guard is wedged`() {
        wedged(Guard.DISABLED, Guard.UNLISTED, Guard.DISABLED) shouldBe true
    }

    @Test
    fun `59 disabled guards and one usable survivor is wedged`() {
        // The field failure: the lone survivor was as unreachable as the rest, but "usable == 0" never
        // became true, so the wipe never fired and ~87% of connections failed across restarts.
        wedged(*Array(59) { Guard.DISABLED }, Guard.USABLE) shouldBe true
    }

    @Test
    fun `the ratio rule needs a sample big enough to judge`() {
        // Twelve guards with one usable is under one in ten; five with one usable is only a young
        // sample that Arti is still filling, and wiping it would loop the bootstrap.
        wedged(*Array(11) { Guard.DISABLED }, Guard.USABLE) shouldBe true
        wedged(*Array(4) { Guard.DISABLED }, Guard.USABLE) shouldBe false
    }

    @Test
    fun `two usable guards out of twelve is recoverable`() {
        wedged(*Array(10) { Guard.DISABLED }, Guard.USABLE, Guard.USABLE) shouldBe false
    }

    @Test
    fun `an empty or unrecognised file is never wedged`() {
        ArtiGuardState.hasNoUsableGuards(parse("{}")) shouldBe false
        ArtiGuardState.hasNoUsableGuards(parse("""{"default":{"guards":[]}}""")) shouldBe false
        ArtiGuardState.hasNoUsableGuards(parse("[1,2,3]")) shouldBe false
    }

    @Test
    fun `text that is not JSON is ignored rather than throwing`() {
        ArtiGuardState.parseOrNull("not json at all") shouldBe null
    }

    @Test
    fun `a confirmed guard proves Tor worked here before, even if it is disabled by now`() {
        confirmed(Guard.USABLE, Guard.CONFIRMED) shouldBe true
        confirmed(Guard.USABLE, Guard.DISABLED) shouldBe false
    }

    /**
     * A real `guards.json` as Arti 2.6.0 wrote it (captured from a run of tools/arti-build's host
     * test), trimmed to three guards and with the relay identities replaced. It pins the exact field
     * names and the pretty-printed shape the parser has to cope with, which the compact fixtures
     * above cannot.
     */
    private val realFile = """
{
  "default": {
    "guards": [
      {
        "id": {
          "ed25519": "REDACTED00",
          "rsa": "0000000000000000000000000000000000000000"
        },
        "orports": [
          "192.0.2.1:9001"
        ],
        "added_at": "2026-09-24T04:45:20Z",
        "added_by": {
          "crate": "tor-guardmgr",
          "version": "0.46.0"
        },
        "disabled": null,
        "confirmed_at": "2026-09-24T04:45:20Z",
        "unlisted_since": null
      },
      {
        "id": {
          "ed25519": "REDACTED01",
          "rsa": "0000000000000000000000000000000000000001"
        },
        "orports": [
          "192.0.2.2:9001"
        ],
        "added_at": "2026-09-18T10:01:30Z",
        "added_by": {
          "crate": "tor-guardmgr",
          "version": "0.46.0"
        },
        "disabled": null,
        "confirmed_at": "2026-09-18T10:01:30Z",
        "unlisted_since": null
      },
      {
        "id": {
          "ed25519": "REDACTED02",
          "rsa": "0000000000000000000000000000000000000002"
        },
        "orports": [
          "192.0.2.3:9001"
        ],
        "added_at": "2026-09-16T01:59:30Z",
        "added_by": {
          "crate": "tor-guardmgr",
          "version": "0.46.0"
        },
        "disabled": null,
        "confirmed_at": null,
        "unlisted_since": null
      }
    ],
    "confirmed": [
      {
        "ed25519": "680D4UvOuqJTyX/FE2EWuVcEx4bnejM56ov88tyXCu8",
        "rsa": "79fb0659bc7482364dfea06d941abcf1140fce2e"
      },
      {
        "ed25519": "6XmV24Q9viEDrc9fT/Idg1GAK0/JJEHrPB6kGxevHoU",
        "rsa": "e7839cd9e5a5d15d34d9a52ca4809aa9278e5e05"
      }
    ]
  },
  "restricted": {
    "guards": [],
    "confirmed": []
  }
}
    """.trimIndent()

    @Test
    fun `a real Arti file with confirmed, usable guards is healthy and proves Tor worked`() {
        val root = parse(realFile)

        ArtiGuardState.hasNoUsableGuards(root) shouldBe false
        ArtiGuardState.hasConfirmedGuard(root) shouldBe true
    }

    @Test
    fun `the same real file with every guard disabled is wedged`() {
        val allDisabled = realFile.replace(
            "\"disabled\": null",
            "\"disabled\": {\"reason\": \"TooManyIndeterminateFailures\"}",
        )

        ArtiGuardState.hasNoUsableGuards(parse(allDisabled)) shouldBe true
    }
}
