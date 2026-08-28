package net.primal.android.navigation

import androidx.compose.runtime.Composable

@Composable
fun AppOverlays(
    onRemoteSessionClick: () -> Unit,
    onUpgradeWalletClick: () -> Unit,
    onWalletFaqClick: () -> Unit,
    onRestoreWalletClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    content()
}
