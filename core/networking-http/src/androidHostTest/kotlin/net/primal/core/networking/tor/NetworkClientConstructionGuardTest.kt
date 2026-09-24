package net.primal.core.networking.tor

import io.kotest.matchers.shouldBe
import java.io.File
import org.junit.Test

/**
 * A source-level guard against the most likely way Tor mode gets broken later: someone adds a new
 * `OkHttpClient` (an image loader, a downloader, a new SDK client) and forgets to route it, and that
 * client quietly connects directly no matter which mode the user chose.
 *
 * Every file that builds an OkHttp client must apply the network route. It is a blunt check by design;
 * a file that has a good reason to build one without it belongs in [allowedWithoutRoute], with the
 * reason written next to it, so the exception is a decision someone made and not an oversight.
 */
class NetworkClientConstructionGuardTest {

    private val skippedDirectories = setOf("build", "bin", "shelved", ".git", ".gradle", "test", "androidHostTest")

    /** Files that build an OkHttp client on purpose without the route, with why. */
    private val allowedWithoutRoute = emptyMap<String, String>()

    private fun repositoryRoot(): File =
        checkNotNull(
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "settings.gradle.kts").exists() },
        ) { "Repository root not found from the test's working directory; the guard cannot check anything." }

    private fun sourceFiles(root: File): Sequence<File> =
        root.walkTopDown()
            .onEnter { directory -> directory.name !in skippedDirectories }
            .filter { it.isFile && it.extension == "kt" }

    @Test
    fun `every file that builds an OkHttp client applies the network route`() {
        val root = repositoryRoot()

        val offenders = sourceFiles(root)
            .filter { file ->
                val text = file.readText()
                val buildsClient = CLIENT_CONSTRUCTION.containsMatchIn(text)
                buildsClient && "applyNetworkRoute" !in text
            }
            .map { it.relativeTo(root).path }
            .filter { it !in allowedWithoutRoute }
            .toList()

        // A non-empty list names the files that would connect outside the chosen network mode.
        offenders shouldBe emptyList()
    }

    private companion object {
        val CLIENT_CONSTRUCTION = Regex("""OkHttpClient(\.Builder)?\(\)""")
    }
}
