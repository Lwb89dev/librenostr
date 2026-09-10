package net.primal.android.core.compose.notifications

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import net.primal.android.R
import net.primal.android.core.compose.res.painterResource
import net.primal.domain.notifications.NotificationType

/**
 * The badge drawn next to a notification's actor avatars.
 *
 * A gift-wrapped private reply deliberately shares the ordinary reply badge: it is still a reply,
 * and what marks it apart belongs on the note itself, where the lock is drawn — not on an icon
 * that would announce "this person sent you something private" to anyone glancing at the screen.
 *
 * Kept as one exhaustive `when` past detekt's length limit on purpose. It is a lookup table, and
 * the compiler refusing to build until a newly added notification type is given an icon here is
 * the whole value of writing it this way; splitting it would need an `else` branch, which trades
 * that guarantee for a line count.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun NotificationType.toImagePainter(): Painter =
    when (this) {
        NotificationType.NEW_USER_FOLLOWED_YOU -> painterResource(
            darkResId = R.drawable.notification_type_followed_dark,
            lightResId = R.drawable.notification_type_followed_light,
        )

        NotificationType.YOUR_POST_WAS_ZAPPED -> painterResource(
            darkResId = R.drawable.notification_type_your_post_was_zapped_dark,
            lightResId = R.drawable.notification_type_your_post_was_zapped_light,
        )

        NotificationType.YOUR_POST_WAS_LIKED -> painterResource(
            darkResId = R.drawable.notification_type_your_post_was_liked_dark,
            lightResId = R.drawable.notification_type_your_post_was_liked_light,
        )

        NotificationType.YOUR_POST_WAS_REPOSTED -> painterResource(
            darkResId = R.drawable.notification_type_your_post_was_reposted_dark,
            lightResId = R.drawable.notification_type_your_post_was_reposted_light,
        )

        NotificationType.YOUR_POST_WAS_REPLIED_TO,
        NotificationType.YOUR_POST_WAS_PRIVATELY_REPLIED_TO,
        -> painterResource(
            darkResId = R.drawable.notification_type_your_post_was_replied_to_dark,
            lightResId = R.drawable.notification_type_your_post_was_replied_to_light,
        )

        NotificationType.YOU_WERE_MENTIONED_IN_POST -> painterResource(
            darkResId = R.drawable.notification_type_you_were_mentioned_in_a_post_dark,
            lightResId = R.drawable.notification_type_you_were_mentioned_in_a_post_light,
        )

        NotificationType.YOUR_POST_WAS_MENTIONED_IN_POST -> painterResource(
            darkResId = R.drawable.notification_type_your_post_was_mentioned_in_a_post_dark,
            lightResId = R.drawable.notification_type_your_post_was_mentioned_in_a_post_light,
        )

        NotificationType.POST_YOU_WERE_MENTIONED_IN_WAS_ZAPPED -> painterResource(
            darkResId = R.drawable.notification_type_post_you_were_mentioned_in_was_zapped_dark,
            lightResId = R.drawable.notification_type_post_you_were_mentioned_in_was_zapped_light,
        )

        NotificationType.POST_YOU_WERE_MENTIONED_IN_WAS_LIKED -> painterResource(
            darkResId = R.drawable.notification_type_post_you_were_mentioned_in_was_liked_dark,
            lightResId = R.drawable.notification_type_post_you_were_mentioned_in_was_liked_light,
        )

        NotificationType.POST_YOU_WERE_MENTIONED_IN_WAS_REPOSTED -> painterResource(
            darkResId = R.drawable.notification_type_post_you_were_mentioned_in_was_reposted_dark,
            lightResId = R.drawable.notification_type_post_you_were_mentioned_in_was_reposted_light,
        )

        NotificationType.POST_YOU_WERE_MENTIONED_IN_WAS_REPLIED_TO -> painterResource(
            darkResId = R.drawable.notification_type_post_you_were_mentioned_in_was_replied_to_dark,
            lightResId = R.drawable.notification_type_post_you_were_mentioned_in_was_replied_to_light,
        )

        NotificationType.POST_YOUR_POST_WAS_MENTIONED_IN_WAS_ZAPPED -> painterResource(
            darkResId = R.drawable.notification_type_post_your_post_was_mentioned_in_was_zapped_dark,
            lightResId = R.drawable.notification_type_post_your_post_was_mentioned_in_was_zapped_light,
        )

        NotificationType.POST_YOUR_POST_WAS_MENTIONED_IN_WAS_LIKED -> painterResource(
            darkResId = R.drawable.notification_type_post_your_post_was_mentioned_in_was_liked_dark,
            lightResId = R.drawable.notification_type_post_your_post_was_mentioned_in_was_liked_light,
        )

        NotificationType.POST_YOUR_POST_WAS_MENTIONED_IN_WAS_REPOSTED -> painterResource(
            darkResId = R.drawable.notification_type_post_your_post_was_mentioned_in_was_reposted_dark,
            lightResId = R.drawable.notification_type_post_your_post_was_mentioned_in_was_reposted_light,
        )

        NotificationType.POST_YOUR_POST_WAS_MENTIONED_IN_WAS_REPLIED_TO -> painterResource(
            darkResId = R.drawable.notification_type_post_your_post_was_mentioned_in_was_replied_to_dark,
            lightResId = R.drawable.notification_type_post_your_post_was_mentioned_in_was_replied_to_light,
        )

        NotificationType.YOUR_POST_WAS_HIGHLIGHTED -> painterResource(
            darkResId = R.drawable.notification_type_highlighted_dark,
            lightResId = R.drawable.notification_type_highlighted_light,
        )

        NotificationType.YOUR_POST_WAS_BOOKMARKED -> painterResource(
            darkResId = R.drawable.notification_type_bookmarked_dark,
            lightResId = R.drawable.notification_type_bookmarked_light,
        )

        NotificationType.LIVE_EVENT_HAPPENING -> painterResource(
            darkResId = R.drawable.notification_type_live_stream_dark,
            lightResId = R.drawable.notification_type_live_stream_light,
        )

        NotificationType.REPLY_TO_REPLY -> painterResource(
            darkResId = R.drawable.notification_type_your_post_was_replied_to_dark,
            lightResId = R.drawable.notification_type_your_post_was_replied_to_light,
        )
    }
