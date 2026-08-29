package net.primal.android.auth.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import net.primal.android.R

val defaultOnboardingAvatarBackground = Color(0xFFFFFFFF).copy(alpha = 0.3f)
val defaultAvatarForeground = Color(0xFF1A295A).copy(0.7f)

@Composable
fun DefaultOnboardingAvatar() {
    Box(
        modifier = Modifier
            .background(color = defaultOnboardingAvatarBackground)
            .fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(id = R.drawable.default_avatar_ostrich),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
