package net.primal.android.settings.appearance

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.primal.android.R
import net.primal.android.core.compose.PrimalScaffold
import net.primal.android.core.compose.PrimalSwitch
import net.primal.android.core.compose.PrimalTopAppBar
import net.primal.android.core.compose.icons.PrimalIcons
import net.primal.android.core.compose.icons.primaliconpack.ArrowBack
import net.primal.android.core.compose.icons.primaliconpack.DarkMode
import net.primal.android.core.compose.preview.PrimalPreview
import net.primal.android.core.compose.settings.SettingsItem
import net.primal.android.settings.appearance.AppearanceSettingsContract.UiEvent
import net.primal.android.theme.AppTheme
import net.primal.android.theme.domain.PrimalTheme

// 4x2 grid for the current 8 themes, per explicit design decision.
private const val THEME_GRID_COLUMNS = 4

@Composable
fun AppearanceSettingsScreen(
    viewModel: AppearanceSettingsViewModel,
    onClose: () -> Unit,
    embedded: Boolean = false,
) {
    val uiState = viewModel.state.collectAsState()
    AppearanceSettingsScreen(
        state = uiState.value,
        onClose = onClose,
        embedded = embedded,
        eventPublisher = { viewModel.setEvent(it) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(
    state: AppearanceSettingsContract.UiState,
    onClose: () -> Unit,
    embedded: Boolean = false,
    eventPublisher: (UiEvent) -> Unit,
) {
    val isSystemInDarkTheme = isSystemInDarkTheme()
    PrimalScaffold(
        modifier = Modifier,
        topBar = if (embedded) {
            null
        } else {
            {
            PrimalTopAppBar(
                title = stringResource(id = R.string.settings_appearance_title),
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
                    .padding(paddingValues)
                    .imePadding(),
                verticalArrangement = Arrangement.Top,
                horizontalAlignment = Alignment.Start,
            ) {
                ThemeSection(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    themes = state.themes,
                    selectedThemeName = state.selectedThemeName,
                    onThemeChange = {
                        eventPublisher(UiEvent.ChangeTheme(themeName = it))
                    },
                )

                SettingsItem(
                    headlineText = stringResource(id = R.string.settings_appearance_auto_adjust_dark_mode),
                    supportText = stringResource(id = R.string.settings_appearance_auto_adjust_dark_mode_hint),
                    leadingIcon = PrimalIcons.DarkMode,
                    trailingContent = {
                        PrimalSwitch(
                            checked = state.selectedThemeName.isNullOrEmpty(),
                            onCheckedChange = {
                                eventPublisher(
                                    UiEvent.ToggleAutoAdjustDarkTheme(
                                        enabled = it,
                                        isSystemInDarkTheme = isSystemInDarkTheme,
                                    ),
                                )
                            },
                        )
                    },
                    onClick = {
                        eventPublisher(
                            UiEvent.ToggleAutoAdjustDarkTheme(
                                enabled = !state.selectedThemeName.isNullOrEmpty(),
                                isSystemInDarkTheme = isSystemInDarkTheme,
                            ),
                        )
                    },
                )

            }
        },
    )
}

@Composable
private fun ThemeSection(
    modifier: Modifier = Modifier,
    themes: List<PrimalTheme>,
    selectedThemeName: String?,
    onThemeChange: (String) -> Unit,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(id = R.string.settings_appearance_theme_section_title).uppercase(),
            fontWeight = FontWeight.W500,
            fontSize = 14.sp,
            lineHeight = 16.sp,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            themes.chunked(THEME_GRID_COLUMNS).forEach { rowThemes ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    rowThemes.forEach { primalTheme ->
                        ThemeBox(
                            modifier = Modifier.weight(1f),
                            primalTheme = primalTheme,
                            selectedThemeName = selectedThemeName,
                            onThemeChange = onThemeChange,
                        )
                    }
                    repeat(THEME_GRID_COLUMNS - rowThemes.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeBox(
    modifier: Modifier = Modifier,
    primalTheme: PrimalTheme,
    selectedThemeName: String?,
    onThemeChange: (String) -> Unit,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val selected = primalTheme.themeName == selectedThemeName
        val borderBrush = if (selected) {
            Brush.linearGradient(
                colors = listOf(
                    primalTheme.colorScheme.primary,
                    primalTheme.colorScheme.primary,
                ),
            )
        } else {
            if (primalTheme.isDarkTheme) {
                Brush.linearGradient(
                    colors = listOf(
                        primalTheme.colorScheme.outline,
                        primalTheme.colorScheme.outline,
                    ),
                )
            } else {
                Brush.linearGradient(
                    colors = listOf(
                        primalTheme.colorScheme.outline,
                        primalTheme.colorScheme.outline,
                    ),
                )
            }
        }

        Box(
            modifier = Modifier
                .clip(AppTheme.shapes.small)
                .border(
                    width = 1.dp,
                    brush = borderBrush,
                    shape = AppTheme.shapes.small,
                )
                .clickable { onThemeChange(primalTheme.themeName) }
                .background(color = primalTheme.colorScheme.background)
                .size(72.dp),
        ) {
            ThemePaletteIcon(
                modifier = Modifier.align(Alignment.Center),
                primalTheme = primalTheme,
            )

            if (selected) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(topStart = 8.dp))
                        .background(color = AppTheme.colorScheme.primary)
                        .size(16.dp)
                        .align(alignment = Alignment.BottomEnd),
                ) {
                    Icon(
                        modifier = Modifier
                            .size(14.dp)
                            .align(alignment = Alignment.Center),
                        imageVector = Icons.Default.Check,
                        tint = Color.White,
                        contentDescription = null,
                    )
                }
            }
        }

        Text(
            modifier = Modifier.padding(top = 8.dp),
            text = primalTheme.displayName,
            fontWeight = FontWeight.W400,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            textAlign = TextAlign.Center,
            color = AppTheme.extraColorScheme.onSurfaceVariantAlt2,
        )
    }
}

@Composable
private fun ThemePaletteIcon(modifier: Modifier = Modifier, primalTheme: PrimalTheme) {
    val iconShape = if (primalTheme.isPixelTheme) {
        RoundedCornerShape(4.dp)
    } else {
        CircleShape
    }

    Box(
        modifier = modifier
            .size(48.dp)
            .clip(iconShape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        primalTheme.colorScheme.primary,
                        primalTheme.colorScheme.secondary,
                    ),
                ),
                shape = iconShape,
            ),
    ) {
        Image(
            modifier = Modifier
                .size(32.dp)
                .align(Alignment.Center),
            painter = painterResource(id = primalTheme.logoId),
            colorFilter = ColorFilter.tint(primalTheme.colorScheme.onPrimary),
            contentDescription = primalTheme.displayName,
        )
        Box(
            modifier = Modifier
                .size(12.dp)
                .align(Alignment.TopEnd)
                .background(
                    color = primalTheme.colorScheme.tertiary,
                    shape = if (primalTheme.isPixelTheme) RoundedCornerShape(1.dp) else CircleShape,
                ),
        )
    }
}

class AppearanceSettingsUiStateProvider :
    PreviewParameterProvider<AppearanceSettingsContract.UiState> {
    override val values: Sequence<AppearanceSettingsContract.UiState>
        get() = PrimalTheme.entries.map {
            return@map AppearanceSettingsContract.UiState(
                selectedThemeName = it.themeName,
                themes = PrimalTheme.entries,
            )
        }.asSequence()
}

@Preview
@Composable
fun PreviewAppearanceSettingsScreen(
    @PreviewParameter(AppearanceSettingsUiStateProvider::class)
    state: AppearanceSettingsContract.UiState,
) {
    checkNotNull(state.selectedThemeName)
    PrimalPreview(primalTheme = PrimalTheme.valueOf(themeName = state.selectedThemeName)!!) {
        AppearanceSettingsScreen(
            state = state,
            onClose = {},
            eventPublisher = {},
        )
    }
}
