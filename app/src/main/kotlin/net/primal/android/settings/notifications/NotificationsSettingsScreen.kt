package net.primal.android.settings.notifications

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import net.primal.android.R
import net.primal.android.core.compose.PrimalDivider
import net.primal.android.core.compose.PrimalScaffold
import net.primal.android.core.compose.PrimalSwitch
import net.primal.android.core.compose.PrimalTopAppBar
import net.primal.android.core.compose.SignatureErrorColumn
import net.primal.android.core.compose.icons.PrimalIcons
import net.primal.android.core.compose.icons.primaliconpack.ArrowBack
import net.primal.android.core.compose.icons.primaliconpack.FeedLikeOutline
import net.primal.android.core.compose.icons.primaliconpack.FeedReplyOutline
import net.primal.android.core.compose.icons.primaliconpack.FeedRepostsOutline
import net.primal.android.core.compose.icons.primaliconpack.Follow
import net.primal.android.core.compose.icons.primaliconpack.Message
import net.primal.android.core.compose.icons.primaliconpack.Messages
import net.primal.android.core.compose.icons.primaliconpack.Mute
import net.primal.android.core.compose.icons.primaliconpack.NavWallet
import net.primal.android.core.compose.icons.primaliconpack.Notifications
import net.primal.android.core.compose.icons.primaliconpack.Play
import net.primal.android.core.compose.icons.primaliconpack.Zap
import net.primal.android.core.compose.preview.PrimalPreview
import net.primal.android.core.utils.getNotificationSettingsIntent
import net.primal.android.settings.notifications.NotificationsSettingsContract.UiEvent.NotificationSettingsChanged
import net.primal.android.settings.notifications.NotificationsSettingsContract.UiState.ApiError
import net.primal.android.settings.notifications.ui.NotificationSwitchUi
import net.primal.android.theme.AppTheme
import net.primal.android.theme.domain.PrimalTheme
import net.primal.domain.notifications.NotificationSettingsType

@Composable
fun NotificationsSettingsScreen(
    viewModel: NotificationsSettingsViewModel,
    onClose: () -> Unit,
    embedded: Boolean = false,
) {
    val state = viewModel.state.collectAsState()

    LaunchedErrorHandler(viewModel = viewModel)

    NotificationsSettingsScreen(
        state = state.value,
        onClose = onClose,
        embedded = embedded,
        eventPublisher = { viewModel.setEvent(it) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsSettingsScreen(
    state: NotificationsSettingsContract.UiState,
    onClose: () -> Unit,
    embedded: Boolean = false,
    eventPublisher: (NotificationsSettingsContract.UiEvent) -> Unit,
) {
    val content: @Composable (PaddingValues) -> Unit = { paddingValues ->
        SignatureErrorColumn(
            modifier = if (embedded) Modifier.fillMaxWidth() else Modifier.fillMaxSize(),
            contentPadding = if (embedded) PaddingValues() else paddingValues,
            signatureUiError = state.signatureError,
        ) {
            NotificationsColumn(
                modifier = if (embedded) Modifier.fillMaxWidth() else Modifier.padding(paddingValues),
                state = state,
                eventPublisher = eventPublisher,
                embedded = embedded,
            )
        }
    }

    if (embedded) {
        content(PaddingValues())
    } else {
        PrimalScaffold(
            containerColor = AppTheme.colorScheme.surfaceVariant,
            topBar = {
                PrimalTopAppBar(
                    title = stringResource(id = R.string.settings_notifications_title),
                    navigationIcon = PrimalIcons.ArrowBack,
                    navigationIconContentDescription = stringResource(id = R.string.accessibility_back_button),
                    onNavigationIconClick = onClose,
                )
            },
            content = content,
        )
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun NotificationsColumn(
    modifier: Modifier = Modifier,
    state: NotificationsSettingsContract.UiState,
    eventPublisher: (NotificationsSettingsContract.UiEvent) -> Unit,
    embedded: Boolean = false,
) {
    if (embedded) {
        NotificationsContent(
            modifier = modifier,
            state = state,
            eventPublisher = eventPublisher,
        )
    } else {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .background(color = AppTheme.colorScheme.surfaceVariant),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                NotificationsContent(
                    state = state,
                    eventPublisher = eventPublisher,
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalPermissionsApi::class)
private fun NotificationsContent(
    modifier: Modifier = Modifier,
    state: NotificationsSettingsContract.UiState,
    eventPublisher: (NotificationsSettingsContract.UiEvent) -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(color = AppTheme.colorScheme.surfaceVariant),
    ) {
        PushNotificationSection(
            modifier = Modifier.padding(vertical = 12.dp),
            pushNotificationsEnabled = state.pushNotificationsEnabled,
            onChange = { eventPublisher(NotificationsSettingsContract.UiEvent.PushNotificationsToggled(it)) },
        )

        NotificationEventFiltersBlock(
            notifications = state.pushNotificationsSettings,
            eventPublisher = eventPublisher,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun NotificationEventFiltersBlock(
    notifications: List<NotificationSwitchUi<NotificationSettingsType.PushNotifications>>,
    eventPublisher: (NotificationsSettingsContract.UiEvent) -> Unit,
) {
    if (notifications.isEmpty()) return

    val settingsByType = notifications.associateBy { it.settingsType }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
            text = stringResource(R.string.settings_notifications_event_filters_title),
            fontWeight = FontWeight.Medium,
            style = AppTheme.typography.bodySmall,
        )
        Column(
            modifier = Modifier
                .background(
                    color = AppTheme.extraColorScheme.surfaceVariantAlt3,
                    shape = RoundedCornerShape(12.dp),
                )
                .fillMaxWidth()
                .clip(RoundedCornerShape(size = 12.dp)),
            horizontalAlignment = Alignment.Start,
        ) {
            NOTIFICATION_EVENT_TYPES.forEachIndexed { index, type ->
                val setting = settingsByType[type] ?: return@forEachIndexed
                NotificationEventFilterRow(
                    title = type.toEventTitle(),
                    enabled = setting.enabled,
                    icon = type.icon(),
                    onCheckedChange = { enabled ->
                        eventPublisher(NotificationSettingsChanged(type = type, value = enabled))
                    },
                )

                if (index < NOTIFICATION_EVENT_TYPES.lastIndex) {
                    PrimalDivider()
                }
            }
        }
    }
}

private val NOTIFICATION_EVENT_TYPES = listOf(
    NotificationSettingsType.PushNotifications.NewFollows,
    NotificationSettingsType.PushNotifications.DirectMessages,
    NotificationSettingsType.PushNotifications.Reactions,
    NotificationSettingsType.PushNotifications.Replies,
)

@Composable
private fun NotificationSettingsType.PushNotifications.toEventTitle(): String =
    when (this) {
        NotificationSettingsType.PushNotifications.NewFollows ->
            stringResource(R.string.settings_notifications_group_new_followers)
        NotificationSettingsType.PushNotifications.DirectMessages ->
            stringResource(R.string.settings_notifications_group_direct_messages)
        NotificationSettingsType.PushNotifications.Reactions ->
            stringResource(R.string.settings_notifications_group_likes)
        NotificationSettingsType.PushNotifications.Replies ->
            stringResource(R.string.settings_notifications_group_comments)
        else -> toTitle()
    }

@Composable
private fun NotificationEventFilterRow(
    title: String,
    enabled: Boolean,
    icon: ImageVector,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!enabled) }
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(
            modifier = Modifier.size(26.dp),
            imageVector = icon,
            contentDescription = null,
            tint = AppTheme.colorScheme.primary,
        )
        Text(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            text = title,
            fontWeight = FontWeight.W400,
            fontSize = 16.sp,
            lineHeight = 20.sp,
            color = AppTheme.extraColorScheme.onSurfaceVariantAlt1,
        )
        Checkbox(
            checked = enabled,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = AppTheme.colorScheme.primary,
                uncheckedColor = AppTheme.colorScheme.outline,
                checkmarkColor = AppTheme.colorScheme.onPrimary,
            ),
        )
    }
}

@Composable
private fun NotificationSettingsRow(
    title: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    longTitleText: Boolean = false,
    icon: ImageVector? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = onCheckedChange != null, onClick = { onCheckedChange?.invoke(!enabled) })
            .padding(horizontal = 16.dp)
            .padding(vertical = if (longTitleText) 8.dp else 0.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            androidx.compose.material3.Icon(
                modifier = Modifier.size(28.dp),
                imageVector = icon,
                contentDescription = null,
                tint = AppTheme.colorScheme.primary,
            )
        }

        Text(
            modifier = Modifier
                .fillMaxWidth(fraction = 0.75f)
                .padding(top = 2.dp)
                .padding(end = if (longTitleText) 6.dp else 0.dp),
            text = title,
            fontWeight = FontWeight.W400,
            fontSize = 16.sp,
            lineHeight = 20.sp,
            color = AppTheme.extraColorScheme.onSurfaceVariantAlt1,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        PrimalSwitch(
            checked = enabled,
            onCheckedChange = onCheckedChange,
        )
    }
}

@ExperimentalPermissionsApi
@Composable
private fun PushNotificationSection(
    modifier: Modifier,
    pushNotificationsEnabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val notificationsPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberPermissionState(permission = android.Manifest.permission.POST_NOTIFICATIONS) { result ->
            onChange(result)
        }
    } else {
        null
    }

    val systemSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        onChange(enabled)
    }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .background(
                    color = AppTheme.extraColorScheme.surfaceVariantAlt3,
                    shape = RoundedCornerShape(12.dp),
                )
                .fillMaxWidth()
                .clip(RoundedCornerShape(size = 12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            NotificationSettingsRow(
                modifier = Modifier.height(48.dp),
                title = stringResource(R.string.settings_notifications_enable_push_notifications),
                enabled = pushNotificationsEnabled,
                icon = PrimalIcons.Notifications,
                onCheckedChange = { newEnabled ->
                    if (newEnabled) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            if (notificationsPermission?.status?.isGranted == true) {
                                onChange(true)
                            } else {
                                // Request POST_NOTIFICATIONS from Android the first time the
                                // user enables push notifications.
                                notificationsPermission?.launchPermissionRequest()
                            }
                        } else if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                            onChange(true)
                        } else {
                            systemSettingsLauncher.launch(context.getNotificationSettingsIntent())
                        }
                    } else {
                        onChange(false)
                    }
                },
            )
        }

    }
}

@Composable
@Deprecated("Replace with SnackbarErrorHandler")
fun LaunchedErrorHandler(viewModel: NotificationsSettingsViewModel) {
    val context = LocalContext.current
    val uiScope = rememberCoroutineScope()
    LaunchedEffect(viewModel) {
        viewModel.state
            .mapNotNull { it.error }
            .map {
                context.getString(
                    when (it) {
                        is ApiError.FetchAppSettingsError -> R.string.settings_notifications_error_fetch_settings
                        is ApiError.UpdateAppSettingsError -> R.string.settings_notifications_error_update_settings
                    },
                )
            }
            .collect {
                viewModel.setEvent(NotificationsSettingsContract.UiEvent.DismissErrors)
                uiScope.launch {
                    Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                }
            }
    }
}

@Suppress("CyclomaticComplexMethod")
@Composable
private fun NotificationSettingsType.toTitle(): String =
    when (this) {
        NotificationSettingsType.Preferences.ReplyRoReply -> stringResource(
            R.string.settings_notifications_group_preferences_reply_to_reply,
        )

        NotificationSettingsType.Preferences.DMsFromFollows -> stringResource(
            R.string.settings_notifications_group_preferences_DMS_from_follows,
        )

        NotificationSettingsType.Preferences.HellThread -> stringResource(
            R.string.settings_notifications_group_preferences_hell_thread,
        )

        NotificationSettingsType.Preferences.ReactionsFromFollows -> stringResource(
            R.string.settings_notifications_group_preferences_reactions_from_follows,
        )

        NotificationSettingsType.PushNotifications.DirectMessages -> stringResource(
            R.string.settings_notifications_group_direct_messages,
        )

        NotificationSettingsType.PushNotifications.Mentions -> stringResource(
            R.string.settings_notifications_group_mentions,
        )

        NotificationSettingsType.PushNotifications.NewFollows -> stringResource(
            R.string.settings_notifications_group_new_followers,
        )

        NotificationSettingsType.PushNotifications.Reactions -> stringResource(
            R.string.settings_notifications_group_reactions,
        )

        NotificationSettingsType.PushNotifications.Replies -> stringResource(
            R.string.settings_notifications_group_replies,
        )

        NotificationSettingsType.PushNotifications.Reposts -> stringResource(
            R.string.settings_notifications_group_reposts,
        )

        NotificationSettingsType.PushNotifications.WalletTransactions -> stringResource(
            R.string.settings_notifications_group_wallet_txs,
        )

        NotificationSettingsType.PushNotifications.Zaps -> stringResource(R.string.settings_notifications_group_zaps)
        NotificationSettingsType.TabNotifications.Mentions -> stringResource(
            R.string.settings_notifications_group_mentions,
        )

        NotificationSettingsType.TabNotifications.NewFollows -> stringResource(
            R.string.settings_notifications_group_new_followers,
        )

        NotificationSettingsType.TabNotifications.Reactions -> stringResource(
            R.string.settings_notifications_group_reactions,
        )

        NotificationSettingsType.TabNotifications.Replies -> stringResource(
            R.string.settings_notifications_group_replies,
        )

        NotificationSettingsType.TabNotifications.Reposts -> stringResource(
            R.string.settings_notifications_group_reposts,
        )

        NotificationSettingsType.TabNotifications.Zaps -> stringResource(R.string.settings_notifications_group_zaps)

        NotificationSettingsType.TabNotifications.LiveEvents -> stringResource(
            id = R.string.settings_notifications_live_streams,
        )

        NotificationSettingsType.PushNotifications.LiveEvents -> stringResource(
            id = R.string.settings_notifications_live_streams,
        )
    }

private fun NotificationSettingsType.icon(): ImageVector =
    when (this) {
        NotificationSettingsType.Preferences.ReplyRoReply -> PrimalIcons.FeedReplyOutline
        NotificationSettingsType.Preferences.HellThread -> PrimalIcons.Mute
        NotificationSettingsType.Preferences.DMsFromFollows -> PrimalIcons.Messages
        NotificationSettingsType.Preferences.ReactionsFromFollows -> PrimalIcons.FeedLikeOutline
        NotificationSettingsType.PushNotifications.DirectMessages -> PrimalIcons.Message
        NotificationSettingsType.PushNotifications.Mentions -> PrimalIcons.Message
        NotificationSettingsType.PushNotifications.NewFollows -> PrimalIcons.Follow
        NotificationSettingsType.PushNotifications.Reactions -> PrimalIcons.FeedLikeOutline
        NotificationSettingsType.PushNotifications.Replies -> PrimalIcons.FeedReplyOutline
        NotificationSettingsType.PushNotifications.Reposts -> PrimalIcons.FeedRepostsOutline
        NotificationSettingsType.PushNotifications.WalletTransactions -> PrimalIcons.NavWallet
        NotificationSettingsType.PushNotifications.Zaps -> PrimalIcons.Zap
        NotificationSettingsType.PushNotifications.LiveEvents -> PrimalIcons.Play
        NotificationSettingsType.TabNotifications.Mentions -> PrimalIcons.Message
        NotificationSettingsType.TabNotifications.NewFollows -> PrimalIcons.Follow
        NotificationSettingsType.TabNotifications.Reactions -> PrimalIcons.FeedLikeOutline
        NotificationSettingsType.TabNotifications.Replies -> PrimalIcons.FeedReplyOutline
        NotificationSettingsType.TabNotifications.Reposts -> PrimalIcons.FeedRepostsOutline
        NotificationSettingsType.TabNotifications.Zaps -> PrimalIcons.Zap
        NotificationSettingsType.TabNotifications.LiveEvents -> PrimalIcons.Play
    }

@Preview
@Composable
fun PreviewNotificationsSettingsScreen() {
    PrimalPreview(primalTheme = PrimalTheme.Midnight) {
        NotificationsSettingsScreen(
            state = NotificationsSettingsContract.UiState(),
            onClose = {},
            eventPublisher = {},
        )
    }
}
