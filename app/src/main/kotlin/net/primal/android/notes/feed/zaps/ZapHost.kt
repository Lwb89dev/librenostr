package net.primal.android.notes.feed.zaps

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import net.primal.domain.zaps.ZappingState

@Composable
fun ZapHost(
    zapHostState: ZapHostState,
    onZap: (Long, String?) -> Unit,
) {
    if (zapHostState.showZapOptions && zapHostState.receiverName != null) {
        ZapBottomSheet(
            onDismissRequest = { zapHostState.dismissZapOptions() },
            receiverName = zapHostState.receiverName,
            zappingState = zapHostState.zappingState,
            onZap = onZap,
        )
    }
}

@Stable
class ZapHostState(
    val zappingState: ZappingState,
    val receiverName: String? = null,
) {
    var showZapOptions by mutableStateOf(false)
        internal set

    fun dismissZapOptions() {
        showZapOptions = false
    }

    fun showZapOptionsOrShowWarning() {
        showZapOptions = true
    }
}

@Composable
fun rememberZapHostState(zappingState: ZappingState, receiverName: String? = null): ZapHostState =
    remember(zappingState, receiverName) {
        ZapHostState(
            zappingState = zappingState,
            receiverName = receiverName,
        )
    }
