package net.primal.android.drawer.multiaccount.model

import net.primal.android.core.utils.authorNameUiFriendly
import net.primal.android.user.domain.UserAccount
import net.primal.domain.links.CdnImage

data class UserAccountUi(
    val pubkey: String,
    val displayName: String,
    val internetIdentifier: String? = null,
    val avatarCdnImage: CdnImage? = null,
    val avatarBlossoms: List<String> = emptyList(),
    val lastAccessedAt: Long,
)

fun UserAccount.asUserAccountUi() =
    UserAccountUi(
        pubkey = pubkey,
        displayName = authorNameUiFriendly(),
        internetIdentifier = internetIdentifier,
        avatarCdnImage = avatarCdnImage,
        avatarBlossoms = blossomServers,
        lastAccessedAt = lastAccessedAt,
    )
