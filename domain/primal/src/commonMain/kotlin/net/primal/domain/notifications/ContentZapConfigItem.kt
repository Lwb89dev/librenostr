package net.primal.domain.notifications

import kotlinx.serialization.Serializable

@Serializable
data class ContentZapConfigItem(
    val emoji: String,
    val amount: Long,
    val message: String,
)

val DEFAULT_ZAP_CONFIG = listOf(
    ContentZapConfigItem(emoji = "", amount = 21, message = ""),
    ContentZapConfigItem(emoji = "", amount = 50, message = ""),
    ContentZapConfigItem(emoji = "", amount = 100, message = ""),
    ContentZapConfigItem(emoji = "", amount = 500, message = ""),
    ContentZapConfigItem(emoji = "", amount = 1000, message = ""),
    ContentZapConfigItem(emoji = "", amount = 2000, message = ""),
    ContentZapConfigItem(emoji = "", amount = 3000, message = ""),
    ContentZapConfigItem(emoji = "", amount = 5000, message = ""),
)
