package net.primal.android.core.compose

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlinx.coroutines.launch
import net.primal.android.core.compose.icons.PrimalIcons
import net.primal.android.core.compose.icons.primaliconpack.Close
import net.primal.android.core.compose.icons.primaliconpack.Search
import net.primal.android.core.compose.foundation.keyboardVisibilityAsState
import net.primal.android.explore.search.SearchContract
import net.primal.android.explore.search.SearchViewModel
import net.primal.android.explore.search.ui.UserProfileListItem
import net.primal.android.premium.legend.domain.LegendaryCustomization
import net.primal.android.theme.AppTheme
import net.primal.domain.links.CdnImage

private val WIPE_BAR_WIDTH = 20.dp
private const val SWITCH_ANIMATION_DURATION_MS = 300
private const val SWITCH_ANIMATION_MIDPOINT = 0.5f
private val AvatarSwipeThreshold = 24.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrimalTopLevelAppBar(
    title: String,
    avatarCdnImage: CdnImage?,
    avatarLegendaryCustomization: LegendaryCustomization?,
    avatarBlossoms: List<String>,
    onAvatarClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleOverride: String? = null,
    subtitleOverride: String? = null,
    showTitleChevron: Boolean = false,
    chevronExpanded: Boolean = false,
    onTitleClick: (() -> Unit)? = null,
    onAvatarSwipeDown: (() -> Unit)? = null,
    showDivider: Boolean = true,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    pagerState: PagerState? = null,
    pages: List<AppBarPage> = emptyList(),
    onSearchClick: (() -> Unit)? = null,
    searchPlaceholder: String? = null,
    showAvatar: Boolean = true,
    onSearchSubmit: ((String) -> Unit)? = null,
    onSearchProfileClick: ((String) -> Unit)? = null,
) {
    val effectiveTitle = titleOverride ?: title
    val effectiveSubtitle = subtitleOverride ?: subtitle
    val effectiveShowChevron = if (titleOverride != null) false else showTitleChevron
    val effectiveOnTitleClick = if (titleOverride != null) null else onTitleClick
    val chevronRotation by animateFloatAsState(
        targetValue = if (chevronExpanded) 180f else 0f,
        label = "ChevronRotation",
    )

    Column(modifier = modifier) {
        TopAppBar(
            title = {
                if (onSearchClick != null && titleOverride == null) {
                    HomeSearchBar(
                        placeholder = searchPlaceholder.orEmpty(),
                        onClick = onSearchClick,
                        onSubmit = onSearchSubmit,
                        onProfileClick = onSearchProfileClick,
                    )
                } else if (titleOverride != null) {
                    AppBarTitle(
                        title = effectiveTitle,
                        subtitle = effectiveSubtitle,
                        showChevron = effectiveShowChevron,
                        chevronRotation = chevronRotation,
                        onTitleClick = effectiveOnTitleClick,
                    )
                } else if (pagerState != null && pages.size > 1) {
                    SwipingAppBarTitle(
                        pagerState = pagerState,
                        pages = pages,
                        showChevron = effectiveShowChevron,
                        chevronRotation = chevronRotation,
                        onTitleClick = effectiveOnTitleClick,
                    )
                } else {
                    AppBarTitle(
                        title = effectiveTitle,
                        subtitle = effectiveSubtitle,
                        showChevron = effectiveShowChevron,
                        chevronRotation = chevronRotation,
                        onTitleClick = effectiveOnTitleClick,
                    )
                }
            },
            actions = {
                if (showAvatar) {
                    SwipeableAvatar(
                        avatarCdnImage = avatarCdnImage,
                        avatarBlossoms = avatarBlossoms,
                        avatarLegendaryCustomization = avatarLegendaryCustomization,
                        onAvatarClick = onAvatarClick,
                        onAvatarSwipeDown = onAvatarSwipeDown,
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = AppTheme.colorScheme.surface,
                scrolledContainerColor = AppTheme.colorScheme.surface,
            ),
            scrollBehavior = scrollBehavior,
        )

        if (showDivider) {
            PrimalDivider()
        }
    }
}

@Composable
private fun HomeSearchBar(
    placeholder: String,
    onClick: () -> Unit,
    onSubmit: ((String) -> Unit)?,
    onProfileClick: ((String) -> Unit)?,
) {
    var active by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var suggestionsVisible by rememberSaveable { mutableStateOf(false) }
    var searchBarBounds by remember { mutableStateOf(IntRect.Zero) }
    val searchViewModel: SearchViewModel = hiltViewModel()
    val searchState by searchViewModel.state.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val keyboardVisible by keyboardVisibilityAsState()
    val density = LocalDensity.current
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.roundToPx() }
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val focusRequester = remember { FocusRequester() }
    var keyboardWasVisible by remember { mutableStateOf(false) }
    val lavender = AppTheme.colorScheme.primary
    val onSurface = AppTheme.colorScheme.onSurface
    val panelVisible = active && suggestionsVisible
    val barShape = if (panelVisible) {
        RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 0.dp, bottomEnd = 0.dp)
    } else {
        CircleShape
    }
    val panelShape = RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp)
    val glassBrush = Brush.verticalGradient(
        colors = listOf(
            lavender.copy(alpha = 0.10f),
            lavender.copy(alpha = 0.04f),
        ),
    )

    fun submitSearch() {
        if (query.isBlank()) return
        searchViewModel.setEvent(SearchContract.UiEvent.SearchSubmitted(query = query))
        suggestionsVisible = false
        keyboard?.hide()
        onSubmit?.invoke(query)
    }

    fun selectRecentSearch(recentQuery: String) {
        query = recentQuery
        suggestionsVisible = true
        searchViewModel.setEvent(SearchContract.UiEvent.SearchQueryUpdated(query = recentQuery))
    }

    fun clearSearch() {
        query = ""
        suggestionsVisible = true
        searchViewModel.setEvent(SearchContract.UiEvent.ResetSearchQuery)
        focusRequester.requestFocus()
    }

    fun closeSearch() {
        active = false
        suggestionsVisible = false
        query = ""
        searchViewModel.setEvent(SearchContract.UiEvent.ResetSearchQuery)
        keyboard?.hide()
    }

    BackHandler(enabled = active) {
        closeSearch()
    }

    LaunchedEffect(active, keyboardVisible) {
        if (active && keyboardWasVisible && !keyboardVisible) {
            closeSearch()
        }
        keyboardWasVisible = active && keyboardVisible
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .zIndex(1f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .onGloballyPositioned { searchBarBounds = it.boundsInWindow().let { bounds ->
                    IntRect(
                        left = bounds.left.toInt(),
                        top = bounds.top.toInt(),
                        right = bounds.right.toInt(),
                        bottom = bounds.bottom.toInt(),
                    )
                } }
                .clip(barShape)
                .background(color = AppTheme.colorScheme.surface)
                .background(brush = glassBrush)
                .border(width = 1.dp, color = lavender.copy(alpha = 0.16f), shape = barShape)
                .clickable {
                    active = true
                    suggestionsVisible = true
                    onClick()
                }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = PrimalIcons.Search,
                contentDescription = null,
                tint = onSurface.copy(alpha = 0.55f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (active) {
                BasicTextField(
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    value = query,
                    onValueChange = {
                        query = it
                        suggestionsVisible = true
                        searchViewModel.setEvent(
                            if (it.isBlank()) {
                                SearchContract.UiEvent.ResetSearchQuery
                            } else {
                                SearchContract.UiEvent.SearchQueryUpdated(query = it)
                            },
                        )
                    },
                    singleLine = true,
                    textStyle = AppTheme.typography.bodyMedium.copy(color = onSurface),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        submitSearch()
                    }),
                    decorationBox = { innerTextField ->
                        Box {
                            if (query.isBlank()) {
                                Text(
                                    text = placeholder,
                                    style = AppTheme.typography.bodyMedium,
                                    color = onSurface.copy(alpha = 0.45f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
            } else {
                Text(
                    text = placeholder,
                    style = AppTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Normal),
                    color = onSurface.copy(alpha = 0.45f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (active && query.isNotEmpty()) {
                Spacer(modifier = Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(onClick = ::clearSearch),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        modifier = Modifier.size(16.dp),
                        imageVector = PrimalIcons.Close,
                        contentDescription = "Clear search",
                        tint = onSurface.copy(alpha = 0.7f),
                    )
                }
            }
        }

        if (panelVisible && searchBarBounds.width > 0) {
            Popup(
                popupPositionProvider = SearchSuggestionsPositionProvider(searchBarBounds),
                properties = PopupProperties(
                    focusable = false,
                    clippingEnabled = false,
                ),
            ) {
            Column(
                modifier = Modifier
                    .width(with(LocalDensity.current) { searchBarBounds.width.toDp() })
                    .heightIn(
                        max = with(density) {
                            (screenHeightPx - imeBottomPx - searchBarBounds.bottom)
                                .coerceAtLeast(0)
                                .toDp()
                        },
                    )
                    .offset(y = (-1).dp)
                    .clip(panelShape)
                    .background(color = AppTheme.colorScheme.surface)
                    .background(brush = glassBrush)
                    .shadow(elevation = 8.dp, shape = panelShape)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clickable {
                            submitSearch()
                        }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    ) {
                    Text(
                        text = if (query.isBlank()) "Recent searches" else "Search notes and profiles",
                        style = AppTheme.typography.bodyMedium,
                        color = onSurface.copy(alpha = 0.75f),
                    )
                }
                if (query.isBlank()) {
                    searchState.recentSearches.take(MAX_SEARCH_SUGGESTIONS).forEach { recentQuery ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .clickable { selectRecentSearch(recentQuery) }
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = PrimalIcons.Search,
                                contentDescription = null,
                                tint = onSurface.copy(alpha = 0.45f),
                                modifier = Modifier.padding(end = 10.dp),
                            )
                            Text(
                                text = recentQuery,
                                style = AppTheme.typography.bodyMedium,
                                color = onSurface.copy(alpha = 0.75f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (searchState.recentUsers.isNotEmpty()) {
                        Text(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            text = "Recent profiles",
                            style = AppTheme.typography.labelMedium,
                            color = onSurface.copy(alpha = 0.55f),
                        )
                        searchState.recentUsers.take(MAX_SEARCH_SUGGESTIONS).forEach { profile ->
                            UserProfileListItem(
                                data = profile,
                                avatarSize = 36.dp,
                                colors = androidx.compose.material3.ListItemDefaults.colors(
                                    containerColor = Color.Transparent,
                                ),
                                onClick = {
                                    searchViewModel.setEvent(
                                        SearchContract.UiEvent.ProfileSelected(profileId = profile.profileId),
                                    )
                                    suggestionsVisible = false
                                    keyboard?.hide()
                                    onProfileClick?.invoke(profile.profileId)
                                },
                            )
                        }
                    }
                } else {
                    if (searchState.searching && searchState.searchResults.isEmpty()) {
                        Text(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            text = "Searching…",
                            style = AppTheme.typography.bodySmall,
                            color = onSurface.copy(alpha = 0.6f),
                        )
                    } else if (searchState.searchResults.isEmpty()) {
                        Text(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            text = "No matching profiles",
                            style = AppTheme.typography.bodySmall,
                            color = onSurface.copy(alpha = 0.6f),
                        )
                    } else {
                        searchState.searchResults.take(MAX_SEARCH_SUGGESTIONS).forEach { profile ->
                            UserProfileListItem(
                                data = profile,
                                avatarSize = 36.dp,
                                colors = androidx.compose.material3.ListItemDefaults.colors(
                                    containerColor = Color.Transparent,
                                ),
                                onClick = {
                                    searchViewModel.setEvent(
                                        SearchContract.UiEvent.ProfileSelected(profileId = profile.profileId),
                                    )
                                    suggestionsVisible = false
                                    keyboard?.hide()
                                    onProfileClick?.invoke(profile.profileId)
                                },
                            )
                        }
                    }
                }
            }
            }
        }
    }
}

private class SearchSuggestionsPositionProvider(
    private val anchorBounds: IntRect,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(
        x = this.anchorBounds.left,
        y = this.anchorBounds.bottom,
    )
}

private const val MAX_SEARCH_SUGGESTIONS = 5

@Composable
private fun AppBarTitle(
    title: String,
    subtitle: String?,
    showChevron: Boolean,
    chevronRotation: Float,
    onTitleClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val titleColumnModifier = if (onTitleClick != null) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onTitleClick,
        )
    } else {
        Modifier
    }

    Column(
        modifier = titleColumnModifier.then(modifier),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = AppTheme.typography.headlineSmall.copy(
                    fontSize = 25.sp,
                    lineHeight = 25.sp,
                ),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showChevron) {
                Icon(
                    modifier = Modifier.rotate(chevronRotation),
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = null,
                )
            }
        }
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = AppTheme.typography.bodyMedium.copy(
                    fontSize = 15.sp,
                    lineHeight = 15.sp,
                    letterSpacing = 0.sp,
                ),
                color = AppTheme.extraColorScheme.onSurfaceVariantAlt2,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
    }
}

@Composable
private fun SwipingAppBarTitle(
    pagerState: PagerState,
    pages: List<AppBarPage>,
    showChevron: Boolean,
    chevronRotation: Float,
    onTitleClick: (() -> Unit)?,
) {
    val maxIndex = (pages.size - 1).coerceAtLeast(0)
    val settledPage = pagerState.settledPage.coerceIn(0, maxIndex)

    val current = pages.getOrNull(settledPage)
    val prev = if (settledPage > 0) pages.getOrNull(settledPage - 1) else null
    val next = if (settledPage < maxIndex) pages.getOrNull(settledPage + 1) else null

    Box(modifier = Modifier.clipToBounds()) {
        AppBarTitle(
            modifier = Modifier
                .fillMaxWidth()
                .wipeClip(pagerState = pagerState, settledPage = settledPage, role = WipeRole.CURRENT),
            title = current?.title.orEmpty(),
            subtitle = current?.subtitle?.ifBlank { null },
            showChevron = showChevron,
            chevronRotation = chevronRotation,
            onTitleClick = onTitleClick,
        )

        if (prev != null) {
            AppBarTitle(
                modifier = Modifier
                    .fillMaxWidth()
                    .wipeClip(pagerState = pagerState, settledPage = settledPage, role = WipeRole.PREV),
                title = prev.title,
                subtitle = prev.subtitle?.ifBlank { null },
                showChevron = showChevron,
                chevronRotation = chevronRotation,
                onTitleClick = onTitleClick,
            )
        }

        if (next != null) {
            AppBarTitle(
                modifier = Modifier
                    .fillMaxWidth()
                    .wipeClip(pagerState = pagerState, settledPage = settledPage, role = WipeRole.NEXT),
                title = next.title,
                subtitle = next.subtitle?.ifBlank { null },
                showChevron = showChevron,
                chevronRotation = chevronRotation,
                onTitleClick = onTitleClick,
            )
        }
    }
}

private enum class WipeRole { CURRENT, PREV, NEXT }

private fun Modifier.wipeClip(
    pagerState: PagerState,
    settledPage: Int,
    role: WipeRole,
): Modifier =
    drawWithContent {
        val offset = (pagerState.currentPage - settledPage).toFloat() + pagerState.currentPageOffsetFraction
        if (role == WipeRole.NEXT && offset <= 0f) return@drawWithContent
        if (role == WipeRole.PREV && offset >= 0f) return@drawWithContent

        val width = size.width
        val halfBar = WIPE_BAR_WIDTH.toPx() / 2f
        val progress = abs(offset).coerceIn(0f, 1f)
        val swipingForward = offset > 0f
        val wipeCenter = if (swipingForward) {
            width + halfBar - progress * (width + halfBar * 2f)
        } else {
            -halfBar + progress * (width + halfBar * 2f)
        }
        val leadingEdge = (wipeCenter - halfBar).coerceAtLeast(0f)
        val trailingEdge = (wipeCenter + halfBar).coerceAtMost(width)

        when (role) {
            WipeRole.CURRENT -> if (swipingForward) {
                clipRect(right = leadingEdge) { this@drawWithContent.drawContent() }
            } else {
                clipRect(left = trailingEdge) { this@drawWithContent.drawContent() }
            }
            WipeRole.NEXT -> clipRect(left = trailingEdge) { this@drawWithContent.drawContent() }
            WipeRole.PREV -> clipRect(right = leadingEdge) { this@drawWithContent.drawContent() }
        }
    }

@Composable
private fun SwipeableAvatar(
    avatarCdnImage: CdnImage?,
    avatarBlossoms: List<String>,
    avatarLegendaryCustomization: LegendaryCustomization?,
    onAvatarClick: () -> Unit,
    onAvatarSwipeDown: (() -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    val switchProgress = remember { Animatable(0f) }

    val swipeModifier = if (onAvatarSwipeDown != null) {
        Modifier.pointerInput(Unit) {
            val thresholdPx = AvatarSwipeThreshold.toPx()
            var acc = 0f
            var fired = false
            detectDragGestures(
                onDragStart = {
                    acc = 0f
                    fired = false
                },
                onDragCancel = {
                    acc = 0f
                    fired = false
                },
                onDragEnd = {
                    acc = 0f
                    fired = false
                },
            ) { _, drag ->
                acc += drag.y
                if (!fired && acc >= thresholdPx) {
                    fired = true
                    scope.launch {
                        onAvatarSwipeDown.invoke()
                        switchProgress.snapTo(0f)
                        switchProgress.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(durationMillis = SWITCH_ANIMATION_DURATION_MS),
                        )
                        switchProgress.snapTo(0f)
                    }
                }
            }
        }
    } else {
        Modifier
    }

    val progress = switchProgress.value
    val scale = if (progress <= SWITCH_ANIMATION_MIDPOINT) {
        1f - (progress / SWITCH_ANIMATION_MIDPOINT)
    } else {
        (progress - SWITCH_ANIMATION_MIDPOINT) / SWITCH_ANIMATION_MIDPOINT
    }
    val slideDown = if (progress <= SWITCH_ANIMATION_MIDPOINT) {
        progress / SWITCH_ANIMATION_MIDPOINT
    } else {
        0f
    }

    Box(
        modifier = swipeModifier.padding(end = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        UniversalAvatarThumbnail(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationY = slideDown * size.height * SWITCH_ANIMATION_MIDPOINT
                }
                .clip(CircleShape),
            avatarCdnImage = avatarCdnImage,
            avatarSize = 40.dp,
            avatarBlossoms = avatarBlossoms,
            legendaryCustomization = avatarLegendaryCustomization,
            onClick = onAvatarClick,
        )
    }
}
