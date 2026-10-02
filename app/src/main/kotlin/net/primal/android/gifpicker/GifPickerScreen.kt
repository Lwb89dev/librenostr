package net.primal.android.gifpicker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import net.primal.android.R
import net.primal.android.core.compose.PrimalAsyncImage
import net.primal.android.core.compose.PrimalScaffold
import net.primal.android.core.compose.SnackbarErrorHandler
import net.primal.android.core.compose.icons.PrimalIcons
import net.primal.android.core.compose.icons.primaliconpack.Search
import net.primal.android.core.errors.resolveUiErrorMessage
import net.primal.android.gifpicker.GifPickerContract.UiEvent
import net.primal.android.gifpicker.domain.GifItem
import net.primal.android.theme.AppTheme
import net.primal.data.remote.api.gifs.model.GifSource

@Composable
fun GifPickerScreen(viewModel: GifPickerViewModel, callbacks: GifPickerContract.ScreenCallbacks) {
    val uiState = viewModel.state.collectAsState()

    LaunchedEffect(viewModel, callbacks) {
        viewModel.effect.collect {
            when (it) {
                is GifPickerContract.SideEffect.GifSelected -> callbacks.onGifSelected(it.gif)
            }
        }
    }
    LaunchedEffect(viewModel) { viewModel.setEvent(UiEvent.PickerShown) }

    GifPickerScreen(
        state = uiState.value,
        callbacks = callbacks,
        eventPublisher = viewModel::setEvent,
    )
}

/**
 * Compact picker shown above the composer toolbar.
 *
 * It used to stay empty until something was typed, because the Wikimedia Commons results it had to
 * offer were not worth the space. It now opens straight onto GIFs and topic chips: a picker that
 * shows something worth tapping the moment it opens is the point of having one.
 */
@Composable
fun GifPickerInlineContent(
    viewModel: GifPickerViewModel,
    onDismiss: () -> Unit,
    onGifSelected: (GifItem) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            if (effect is GifPickerContract.SideEffect.GifSelected) {
                onGifSelected(effect.gif)
                onDismiss()
            }
        }
    }
    LaunchedEffect(viewModel) { viewModel.setEvent(UiEvent.PickerShown) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Keep the inline picker within the space above the composer
            // toolbar. Without a cap that is small enough for the IME layout,
            // the result grid grows to its 290.dp height and bottom-aligning
            // the column pushes the search field underneath the top bar.
            // Slightly taller than when it held only a search field, to fit
            // the topic chips and a first row of results under it.
            .heightIn(max = 280.dp)
            .shadow(18.dp, RoundedCornerShape(22.dp))
            .background(AppTheme.colorScheme.surfaceVariant, RoundedCornerShape(22.dp))
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GifSearchBar(
                modifier = Modifier.weight(1f),
                query = state.searchQuery,
                onQueryChange = { viewModel.setEvent(UiEvent.UpdateSearchQuery(it)) },
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close GIF picker",
                    tint = AppTheme.colorScheme.onSurface,
                )
            }
        }
        GifTopicChips(
            topics = state.topics,
            onTopicClick = { viewModel.setEvent(UiEvent.UpdateSearchQuery(it)) },
        )
        GifGridContent(
            state = state,
            eventPublisher = viewModel::setEvent,
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 290.dp),
        )
        GifSourceAttribution(source = state.source)
    }
}

@Composable
fun GifPickerScreen(
    state: GifPickerContract.UiState,
    callbacks: GifPickerContract.ScreenCallbacks,
    eventPublisher: (UiEvent) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    SnackbarErrorHandler(
        error = state.error,
        snackbarHostState = snackbarHostState,
        errorMessageResolver = { it.resolveUiErrorMessage(context) },
        onErrorDismiss = { eventPublisher(UiEvent.DismissError) },
    )

    PrimalScaffold(
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.navigationBarsPadding(),
            )
        },
        content = { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 4.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GifSearchBar(
                        modifier = Modifier.weight(1f),
                        query = state.searchQuery,
                        onQueryChange = { eventPublisher(UiEvent.UpdateSearchQuery(it)) },
                    )
                    TextButton(onClick = callbacks.onClose) {
                        Text(
                            modifier = Modifier.padding(top = 1.dp),
                            text = stringResource(id = R.string.gif_picker_cancel),
                            color = AppTheme.colorScheme.onSurface,
                        )
                    }
                }

                GifTopicChips(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    topics = state.topics,
                    onTopicClick = { eventPublisher(UiEvent.UpdateSearchQuery(it)) },
                )

                GifGridContent(
                    state = state,
                    eventPublisher = eventPublisher,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )

                GifSourceAttribution(
                    modifier = Modifier.padding(vertical = 8.dp),
                    source = state.source,
                )
            }
        },
    )
}

/**
 * One-tap queries: Nostr staples before anything is typed, the provider's autocomplete after.
 * Tapping one fills the search field, so what is being shown is always spelled out there.
 */
@Composable
private fun GifTopicChips(
    topics: List<String>,
    onTopicClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (topics.isEmpty()) return
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(topics, key = { it }) { topic ->
            Text(
                text = topic,
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(AppTheme.extraColorScheme.surfaceVariantAlt1)
                    .clickable { onTopicClick(topic) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = AppTheme.typography.bodySmall,
                color = AppTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

/** "Powered by nostr.build" — or by GIFverse while nostr.build is not answering us. */
@Composable
private fun GifSourceAttribution(source: GifSource?, modifier: Modifier = Modifier) {
    val sourceName = when (source) {
        GifSource.NostrBuild -> stringResource(id = R.string.gif_picker_source_nostr_build)
        GifSource.Gifverse -> stringResource(id = R.string.gif_picker_source_gifverse)
        null -> return
    }
    Text(
        text = buildAnnotatedString {
            append(stringResource(id = R.string.gif_picker_powered_by))
            append(" ")
            withStyle(SpanStyle(color = AppTheme.extraColorScheme.onSurfaceVariantAlt2)) {
                append(sourceName)
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        textAlign = TextAlign.Center,
        style = AppTheme.typography.bodySmall,
        color = AppTheme.extraColorScheme.onSurfaceVariantAlt3,
    )
}

@Composable
internal fun GifSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        modifier = modifier.height(48.dp),
        value = query,
        onValueChange = onQueryChange,
        placeholder = {
            Text(
                text = stringResource(id = R.string.gif_picker_search_placeholder),
                style = AppTheme.typography.bodyMedium,
                color = AppTheme.extraColorScheme.onSurfaceVariantAlt3,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = PrimalIcons.Search,
                contentDescription = null,
                tint = AppTheme.extraColorScheme.onSurfaceVariantAlt3,
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = null,
                        tint = AppTheme.extraColorScheme.onSurfaceVariantAlt3,
                    )
                }
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            focusedContainerColor = AppTheme.extraColorScheme.surfaceVariantAlt1,
            unfocusedContainerColor = AppTheme.extraColorScheme.surfaceVariantAlt1,
        ),
        shape = AppTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        singleLine = true,
        textStyle = AppTheme.typography.bodyMedium,
    )
}

@Composable
internal fun GifGridContent(
    state: GifPickerContract.UiState,
    eventPublisher: (UiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        val gridState = rememberLazyGridState()

        LazyVerticalGrid(
            columns = GridCells.Fixed(GIF_GRID_COLUMN_COUNT),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(state.gifItems, key = { it.id }) { gif ->
                GifGridItem(
                    gif = gif,
                    onClick = { eventPublisher(UiEvent.SelectGif(gif)) },
                )
            }
        }

        val shouldLoadMore by remember {
            derivedStateOf {
                val layoutInfo = gridState.layoutInfo
                val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                val totalItems = layoutInfo.totalItemsCount
                totalItems > 0 && lastVisibleIndex >= totalItems - LOAD_MORE_THRESHOLD
            }
        }

        LaunchedEffect(shouldLoadMore) {
            if (shouldLoadMore) {
                eventPublisher(UiEvent.LoadMoreGifs)
            }
        }

        if (state.searching && state.gifItems.isEmpty()) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = AppTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun GifGridItem(gif: GifItem, onClick: () -> Unit) {
    PrimalAsyncImage(
        model = gif.previewUrl,
        contentDescription = gif.contentDescription,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .aspectRatio(1f)
            .clickable { onClick() },
    )
}

private const val GIF_GRID_COLUMN_COUNT = 3
private const val LOAD_MORE_THRESHOLD = 6
