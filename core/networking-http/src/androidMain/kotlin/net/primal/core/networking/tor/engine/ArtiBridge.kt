package net.primal.core.networking.tor.engine

/**
 * The native Tor client as the rest of the app sees it: a handful of blocking calls, no coroutines.
 *
 * It is an interface so that [ArtiTorEngine] and [TorSupervisor] can be exercised with an in-memory
 * fake, without the native library, a Tor network or a device. [JniArtiBridge] is the production
 * implementation and the only one that touches JNI.
 *
 * Every function may block (creating the client waits for a file lock, destroying it waits for
 * tasks to stop), so callers must not invoke them from the main thread.
 */
interface ArtiBridge {

    /** A human-readable version string of the native library, used only for diagnostics. */
    fun version(): String

    /**
     * Creates the Tor client (once per process) and starts downloading the directory in the
     * background. Returns as soon as the client exists. [dataDir] holds Arti's state and cache and
     * must be app-private. Returns 0 on success, negative on failure; calling it while a client is
     * already running is a no-op that also returns 0.
     */
    fun initialize(dataDir: String): Int

    /**
     * Binds the loopback SOCKS5 listener and returns the port it is bound to, or a negative error.
     * [port] 0 lets the OS pick a free one, which is what the app always asks for: there is then no
     * port to guess and no well-known port for another app to probe.
     */
    fun startSocks(port: Int): Int

    /** Stops the listener. The Tor client keeps running, so this never touches Arti's file lock. */
    fun stopSocks(): Int

    /** 1 when circuits can be built now, 0 when not yet, -1 when there is no client. */
    fun isBootstrapped(): Int

    /** Directory download progress in permille (0..1000), or -1 when there is no client. */
    fun bootstrapProgress(): Int

    /** Suspends (`true`) or resumes (`false`) Arti's background work. Use is transparent: it wakes on demand. */
    fun setDormant(soft: Boolean)

    /** One of the `STAT_*` counters, or -1 for an unknown index. */
    fun stat(index: Int): Long

    /** Drains the native log queue as newline-separated lines, or null when it is empty. */
    fun pollLog(): String?

    /**
     * Drops the client and everything running on it so a later [initialize] starts from scratch,
     * including on the same data directory (the state-file lock is released).
     */
    fun destroy(): Int

    companion object {
        const val STAT_CONNECT_OK = 0
        const val STAT_CONNECT_FAILED = 1
        const val STAT_ACCESS_FAILED_STREAK = 2
    }
}

/** Connect outcomes counted by the native proxy since the client was created. */
data class TorHealth(
    val connectOk: Long,
    val connectFailed: Long,
    /**
     * Consecutive connects that failed because the Tor network itself could not be reached (Arti's
     * `TorAccessFailed`, which includes "all guards down"), reset by any success. A long streak with
     * no success in between is what a wedged client looks like from the outside.
     */
    val accessFailedStreak: Long,
)

internal fun ArtiBridge.health() = TorHealth(
    connectOk = stat(ArtiBridge.STAT_CONNECT_OK),
    connectFailed = stat(ArtiBridge.STAT_CONNECT_FAILED),
    accessFailedStreak = stat(ArtiBridge.STAT_ACCESS_FAILED_STREAK),
)
