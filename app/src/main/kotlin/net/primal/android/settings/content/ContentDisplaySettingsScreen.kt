package net.primal.android.settings.content

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.primal.android.R
import net.primal.android.core.compose.PrimalScaffold
import net.primal.android.core.compose.PrimalSwitch
import net.primal.android.core.compose.PrimalTopAppBar
import net.primal.android.core.compose.icons.PrimalIcons
import net.primal.android.core.compose.icons.primaliconpack.ArrowBack
import net.primal.android.core.compose.icons.primaliconpack.AvatarNostrich
import net.primal.android.core.compose.icons.primaliconpack.Play
import net.primal.android.core.compose.icons.primaliconpack.FeedPicker
import net.primal.android.core.compose.icons.primaliconpack.VideoPlay
import net.primal.android.core.compose.settings.SettingsItem
import net.primal.android.settings.content.ContentDisplaySettingsContract.UiEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import net.primal.android.core.compose.PrimalSliderThumb
import androidx.compose.runtime.remember
import net.primal.android.theme.AppTheme
import net.primal.android.user.domain.ContentDisplaySettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentDisplaySettingsScreen(
    viewModel: ContentDisplaySettingsViewModel,
    onClose: () -> Unit,
    embedded: Boolean = false,
) {
    val uiState = viewModel.uiState.collectAsState()

    ContentDisplaySettingsScreen(
        state = uiState.value,
        onClose = onClose,
        embedded = embedded,
        eventPublisher = { viewModel.setEvent(it) },
    )
}

@Composable
@ExperimentalMaterial3Api
private fun ContentDisplaySettingsScreen(
    state: ContentDisplaySettingsContract.UiState,
    onClose: () -> Unit,
    embedded: Boolean = false,
    eventPublisher: (UiEvent) -> Unit,
) {
    PrimalScaffold(
        modifier = Modifier,
        topBar = if (embedded) {
            null
        } else {
            {
            PrimalTopAppBar(
                title = stringResource(id = R.string.settings_content_display_title),
                navigationIcon = PrimalIcons.ArrowBack,
                navigationIconContentDescription = stringResource(id = R.string.accessibility_back_button),
                onNavigationIconClick = onClose,
            )
            }
        },
        content = { paddingValues ->
            Column(
                modifier = Modifier
                    .background(color = AppTheme.colorScheme.surfaceVariant)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(paddingValues),
                verticalArrangement = Arrangement.Top,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                SettingsItem(
                    headlineText = stringResource(id = R.string.settings_content_display_auto_update_feed),
                    supportText = stringResource(id = R.string.settings_content_display_auto_update_feed_hint),
                    leadingIcon = PrimalIcons.FeedPicker,
                    trailingContent = {
                        PrimalSwitch(
                            checked = state.autoUpdateFeed,
                            onCheckedChange = { eventPublisher(UiEvent.UpdateAutoUpdateFeed(enabled = it)) },
                        )
                    },
                    onClick = {
                        eventPublisher(UiEvent.UpdateAutoUpdateFeed(enabled = !state.autoUpdateFeed))
                    },
                )

                Spacer(modifier = Modifier.height(8.dp))

                SettingsItem(
                    headlineText = stringResource(id = R.string.settings_content_display_undo_post_timer),
                    supportText = stringResource(id = R.string.settings_content_display_undo_post_timer_hint),
                    leadingIcon = PrimalIcons.FeedPicker,
                    trailingContent = {
                        PrimalSwitch(
                            checked = state.undoPostTimerEnabled,
                            onCheckedChange = { eventPublisher(UiEvent.UpdateUndoPostTimerEnabled(enabled = it)) },
                        )
                    },
                    onClick = {
                        eventPublisher(UiEvent.UpdateUndoPostTimerEnabled(enabled = !state.undoPostTimerEnabled))
                    },
                )

                if (state.undoPostTimerEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))

                    val interactionSource = remember { MutableInteractionSource() }
                    val sliderColors = SliderDefaults.colors(
                        thumbColor = AppTheme.colorScheme.primary,
                        activeTrackColor = AppTheme.colorScheme.primary,
                        inactiveTrackColor = AppTheme.extraColorScheme.surfaceVariantAlt1,
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(id = R.string.settings_content_display_undo_post_seconds),
                            style = AppTheme.typography.bodyMedium,
                            color = AppTheme.extraColorScheme.onSurfaceVariantAlt1,
                        )
                        Slider(
                            modifier = Modifier.weight(1f),
                            interactionSource = interactionSource,
                            colors = sliderColors,
                            thumb = {
                                PrimalSliderThumb(
                                    interactionSource = interactionSource,
                                    colors = sliderColors,
                                )
                            },
                            value = state.undoPostTimerSeconds.toFloat(),
                            onValueChange = {
                                eventPublisher(UiEvent.UpdateUndoPostTimerSeconds(seconds = it.toInt()))
                            },
                            // One stop per whole second between the two ends.
                            steps = ContentDisplaySettings.MAX_UNDO_POST_SECONDS -
                                ContentDisplaySettings.MIN_UNDO_POST_SECONDS - 1,
                            valueRange = ContentDisplaySettings.MIN_UNDO_POST_SECONDS.toFloat()..
                                ContentDisplaySettings.MAX_UNDO_POST_SECONDS.toFloat(),
                        )
                        Text(
                            text = "${state.undoPostTimerSeconds}s",
                            style = AppTheme.typography.bodyMedium,
                            color = AppTheme.colorScheme.onSurface,
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    SettingsItem(
                        headlineText = stringResource(id = R.string.settings_content_display_undo_post_timer_replies),
                        leadingIcon = PrimalIcons.FeedPicker,
                        trailingContent = {
                            PrimalSwitch(
                                checked = state.undoPostTimerForReplies,
                                onCheckedChange = {
                                    eventPublisher(UiEvent.UpdateUndoPostTimerForReplies(enabled = it))
                                },
                            )
                        },
                        onClick = {
                            eventPublisher(
                                UiEvent.UpdateUndoPostTimerForReplies(enabled = !state.undoPostTimerForReplies),
                            )
                        },
                    )
                }

                SettingsItem(
                    headlineText = stringResource(id = R.string.settings_content_display_auto_play_videos),
                    supportText = stringResource(id = R.string.settings_content_display_auto_play_videos_hint),
                    leadingIcon = PrimalIcons.VideoPlay,
                    trailingContent = {
                        PrimalSwitch(
                            checked = state.autoPlayVideos == ContentDisplaySettings.AUTO_PLAY_VIDEO_ALWAYS,
                            onCheckedChange = {
                                eventPublisher(
                                    UiEvent.UpdateAutoPlayVideos(
                                        code = if (it) {
                                            ContentDisplaySettings.AUTO_PLAY_VIDEO_ALWAYS
                                        } else {
                                            ContentDisplaySettings.AUTO_PLAY_VIDEO_NEVER
                                        },
                                    ),
                                )
                            },
                        )
                    },
                    onClick = {
                        eventPublisher(
                            UiEvent.UpdateAutoPlayVideos(
                                code = if (state.autoPlayVideos != ContentDisplaySettings.AUTO_PLAY_VIDEO_ALWAYS) {
                                    ContentDisplaySettings.AUTO_PLAY_VIDEO_ALWAYS
                                } else {
                                    ContentDisplaySettings.AUTO_PLAY_VIDEO_NEVER
                                },
                            ),
                        )
                    },
                )

                Spacer(modifier = Modifier.height(8.dp))

                SettingsItem(
                    headlineText = stringResource(id = R.string.settings_content_display_animated_avatars),
                    supportText = stringResource(id = R.string.settings_content_display_animated_avatars_hint),
                    leadingIcon = PrimalIcons.AvatarNostrich,
                    trailingContent = {
                        PrimalSwitch(
                            checked = state.showAnimatedAvatars,
                            onCheckedChange = {
                                eventPublisher(UiEvent.UpdateShowAnimatedAvatars(enabled = it))
                            },
                        )
                    },
                    onClick = {
                        eventPublisher(UiEvent.UpdateShowAnimatedAvatars(enabled = !state.showAnimatedAvatars))
                    },
                )

                Spacer(modifier = Modifier.height(8.dp))

                SettingsItem(
                    headlineText = stringResource(id = R.string.settings_content_display_live_streams),
                    supportText = stringResource(id = R.string.settings_content_display_live_streams_hint),
                    leadingIcon = PrimalIcons.Play,
                    trailingContent = {
                        PrimalSwitch(
                            checked = state.showLiveStreams,
                            onCheckedChange = {
                                eventPublisher(UiEvent.UpdateShowLiveStreams(enabled = it))
                            },
                        )
                    },
                    onClick = {
                        eventPublisher(UiEvent.UpdateShowLiveStreams(enabled = !state.showLiveStreams))
                    },
                )
            }
        },
    )
}
