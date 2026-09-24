package net.primal.core.networking.tor.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A [TorEngine] whose state the test sets directly, recording the restarts it is asked for. */
internal class FakeTorEngine(initial: TorEngineState = TorEngineState.Off) : TorEngine {

    private val mutableState = MutableStateFlow(initial)
    override val state: StateFlow<TorEngineState> = mutableState

    /** One entry per restart, `true` when it asked for the state to be wiped. */
    val restarts = mutableListOf<Boolean>()
    var health = TorHealth(0, 0, 0)

    /** What a restart leaves behind. `Starting` by default; a test for a start that keeps failing sets `Failed`. */
    var stateAfterRestart: TorEngineState = TorEngineState.Starting

    fun set(next: TorEngineState) {
        mutableState.value = next
    }

    override suspend fun start() {
        mutableState.value = TorEngineState.Starting
    }

    override suspend fun stop() {
        mutableState.value = TorEngineState.Off
    }

    override suspend fun restart(wipeState: Boolean) {
        restarts += wipeState
        mutableState.value = stateAfterRestart
    }

    override fun setBackgrounded(backgrounded: Boolean) = Unit

    override fun health() = health
}
