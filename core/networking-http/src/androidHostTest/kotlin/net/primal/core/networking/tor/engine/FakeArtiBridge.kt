package net.primal.core.networking.tor.engine

/**
 * A scriptable [ArtiBridge]: no native code, no network. Records every call so tests can assert on
 * the exact sequence the engine drove.
 */
internal class FakeArtiBridge : ArtiBridge {

    val calls = mutableListOf<String>()

    var initializeResult = 0
    var socksPort = 40_123
    /** What `isBootstrapped` answers; the test flips it to simulate the directory landing. */
    var bootstrapped = 0
    var progress = 0
    var connectOk = 0L
    var connectFailed = 0L
    var accessFailedStreak = 0L
    var pendingLog: String? = null

    /** Called inside `initialize`, before it returns, so a test can inspect the world at that moment. */
    var onInitialize: () -> Unit = {}

    override fun version() = "fake"

    override fun initialize(dataDir: String): Int {
        calls += "initialize"
        onInitialize()
        return initializeResult
    }

    override fun startSocks(port: Int): Int {
        calls += "startSocks($port)"
        return socksPort
    }

    override fun stopSocks(): Int {
        calls += "stopSocks"
        return 0
    }

    override fun isBootstrapped() = bootstrapped

    override fun bootstrapProgress() = progress

    override fun setDormant(soft: Boolean) {
        calls += "setDormant($soft)"
    }

    override fun stat(index: Int) = when (index) {
        ArtiBridge.STAT_CONNECT_OK -> connectOk
        ArtiBridge.STAT_CONNECT_FAILED -> connectFailed
        ArtiBridge.STAT_ACCESS_FAILED_STREAK -> accessFailedStreak
        else -> -1L
    }

    override fun pollLog() = pendingLog.also { pendingLog = null }

    override fun destroy(): Int {
        calls += "destroy"
        return 0
    }
}
