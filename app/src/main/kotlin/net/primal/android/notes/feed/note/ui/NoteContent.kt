package net.primal.android.notes.feed.note.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.aakira.napier.Napier
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.launch
import net.primal.android.R
import net.primal.android.notes.translate.TranslateApiFactory
import net.primal.android.core.activity.LocalContentDisplaySettings
import net.primal.android.core.activity.LocalPrimalTheme
import net.primal.android.core.compose.PrimalClickableText
import net.primal.android.core.compose.icons.PrimalIcons
import net.primal.android.core.compose.icons.primaliconpack.Document
import net.primal.android.core.compose.preview.PrimalPreview
import net.primal.android.core.compose.zaps.ReferencedNoteZap
import net.primal.android.core.compose.zaps.ReferencedZap
import net.primal.android.notes.feed.model.HASHTAG_ANNOTATION_TAG
import net.primal.android.notes.feed.model.NOSTR_ADDRESS_ANNOTATION_TAG
import net.primal.android.notes.feed.model.NOTE_ANNOTATION_TAG
import net.primal.android.notes.feed.model.NoteContentUi
import net.primal.android.notes.feed.model.NoteNostrUriUi
import net.primal.android.notes.feed.model.PROFILE_ID_ANNOTATION_TAG
import net.primal.android.notes.feed.model.RenderedNoteContent
import net.primal.android.notes.feed.model.URL_ANNOTATION_TAG
import net.primal.android.notes.feed.model.asNoteNostrUriUi
import net.primal.android.notes.feed.model.computeRenderedNoteContent
import net.primal.android.notes.feed.model.toAnnotatedString
import net.primal.android.notes.feed.note.ui.attachment.NoteAttachments
import net.primal.android.notes.feed.note.ui.events.InvoicePayClickEvent
import net.primal.android.notes.feed.note.ui.events.NoteCallbacks
import net.primal.android.stream.player.LocalStreamState
import net.primal.android.theme.AppTheme
import net.primal.android.theme.domain.PrimalTheme
import net.primal.domain.links.EventUriNostrType
import net.primal.domain.links.ReferencedNote
import net.primal.domain.links.ReferencedUser
import net.primal.domain.nostr.NostrEventKind

internal const val NOT_FOUND_NOTICE_CUT_OFF_LEVEL = 2

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteContent(
    modifier: Modifier = Modifier,
    data: NoteContentUi,
    rendered: RenderedNoteContent? = null,
    expanded: Boolean,
    noteCallbacks: NoteCallbacks,
    nestingLevel: Int = 0,
    nestingCutOffLimit: Int = Int.MAX_VALUE,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textSelectable: Boolean = false,
    referencedEventsHaveBorder: Boolean = true,
    couldAutoPlay: Boolean = false,
    highlightColor: Color = AppTheme.colorScheme.secondary,
    contentColor: Color = AppTheme.colorScheme.onSurface,
    referencedEventsContainerColor: Color = AppTheme.extraColorScheme.surfaceVariantAlt1,
    onClick: ((offset: Offset) -> Unit)? = null,
    onUrlClick: ((url: String) -> Unit)? = null,
    onVideoSoundToggle: ((soundOn: Boolean) -> Unit)? = null,
    onPollOptionSelected: ((optionId: String) -> Unit)? = null,
) {
    val isDarkTheme = LocalPrimalTheme.current.isDarkTheme
    val displaySettings = LocalContentDisplaySettings.current
    val seeMoreText = stringResource(id = R.string.feed_see_more)
    val contentText = remember(data, rendered, expanded, seeMoreText, highlightColor) {
        rendered?.toAnnotatedString(seeMoreText = seeMoreText, highlightColor = highlightColor)
            ?: renderContentAsAnnotatedString(
                data = data,
                expanded = expanded,
                seeMoreText = seeMoreText,
                highlightColor = highlightColor,
            )
    }

    var translationState by remember(data.noteId) {
        mutableStateOf<NoteTranslationState>(NoteTranslationState.Original)
    }
    val translateScope = rememberCoroutineScope()
    val translateApi = remember { TranslateApiFactory.create() }
    val deviceLanguage = remember { Locale.getDefault().language }

    Column(modifier = modifier) {
        if (contentText.isNotEmpty()) {
            val clickHandler = remember(contentText, noteCallbacks, onUrlClick, onClick) {
                { position: Int, offset: Offset ->
                    val annotation = contentText.getStringAnnotations(
                        start = position,
                        end = position,
                    ).firstOrNull()

                    annotation?.handleAnnotationClick(
                        onProfileClick = noteCallbacks.onProfileClick,
                        onUrlClick = { onUrlClick?.invoke(it) },
                        onPostClick = noteCallbacks.onNoteClick,
                        onHashtagClick = noteCallbacks.onHashtagClick,
                        onArticleClick = noteCallbacks.onArticleClick,
                    ) ?: onClick?.invoke(offset) ?: Unit
                }
            }

            val currentTranslationState = translationState
            if (currentTranslationState is NoteTranslationState.Translated) {
                Text(
                    modifier = Modifier.padding(bottom = 4.dp),
                    text = currentTranslationState.text,
                    style = AppTheme.typography.bodyMedium.copy(
                        color = contentColor,
                        fontSize = displaySettings.contentAppearance.noteBodyFontSize,
                        lineHeight = displaySettings.contentAppearance.noteBodyLineHeight,
                    ),
                )
            } else {
                PrimalClickableText(
                    modifier = Modifier.padding(bottom = 4.dp),
                    style = AppTheme.typography.bodyMedium.copy(
                        color = contentColor,
                        fontSize = displaySettings.contentAppearance.noteBodyFontSize,
                        lineHeight = displaySettings.contentAppearance.noteBodyLineHeight,
                    ),
                    text = contentText,
                    maxLines = maxLines,
                    overflow = overflow,
                    textSelectable = textSelectable,
                    onClick = clickHandler,
                )
            }

            if (displaySettings.translateNotesEnabled && displaySettings.translateServerUrl.isNotBlank()) {
                NoteTranslateAction(
                    state = currentTranslationState,
                    onTranslateClick = {
                        translationState = NoteTranslationState.Loading
                        translateScope.launch {
                            translationState = try {
                                val translated = translateApi.translate(
                                    serverUrl = displaySettings.translateServerUrl,
                                    text = data.content,
                                    targetLanguage = deviceLanguage,
                                )
                                NoteTranslationState.Translated(text = translated)
                            } catch (error: Throwable) {
                                Napier.w(throwable = error) { "Failed to translate note ${data.noteId}." }
                                NoteTranslationState.Error
                            }
                        }
                    },
                    onSeeOriginalClick = { translationState = NoteTranslationState.Original },
                )
            }
        }

        val referencedStreams = data.partitions.referencedStreams

        if (referencedStreams.isNotEmpty()) {
            val streamState = LocalStreamState.current

            referencedStreams.forEachIndexed { index, stream ->
                ReferencedStream(
                    stream = stream,
                    onClick = { streamState.start(it) },
                    onProfileClick = { noteCallbacks.onProfileClick?.invoke(it) },
                )

                if (index < referencedStreams.size - 1) {
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }

        val referencedHighlights = data.partitions.referencedHighlights
        if (referencedHighlights.isNotEmpty()) {
            referencedHighlights.forEachIndexed { index, highlight ->
                ReferencedHighlight(
                    highlight = highlight,
                    isDarkTheme = isDarkTheme,
                    onClick = { naddr -> noteCallbacks.onArticleClick?.invoke(naddr) },
                )

                if (index < referencedHighlights.size - 1) {
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }

        if (data.invoices.isNotEmpty()) {
            NoteLightningInvoice(
                modifier = Modifier.padding(top = if (contentText.isEmpty()) 4.dp else 6.dp),
                invoice = data.invoices.first(),
                onPayClick = { lnbc -> noteCallbacks.onPayInvoiceClick?.invoke(InvoicePayClickEvent(lnbc = lnbc)) },
            )
        }

        if (data.uris.isNotEmpty()) {
            NoteAttachments(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (contentText.isEmpty()) 4.dp else 6.dp)
                    .heightIn(min = 0.dp, max = 500.dp),
                eventUris = data.partitions.filteredEventUris,
                blossoms = data.blossoms,
                expanded = expanded,
                couldAutoPlay = couldAutoPlay,
                onUrlClick = { url -> onUrlClick?.invoke(url) },
                onMediaClick = noteCallbacks.onMediaClick,
                onVideoSoundToggle = onVideoSoundToggle,
            )
        }

        val referencedPostResources = data.partitions.referencedNotes
        if (referencedPostResources.isNotEmpty() && (nestingLevel < nestingCutOffLimit || expanded)) {
            ReferencedNotesColumn(
                modifier = Modifier.padding(top = 4.dp),
                nestingLevel = nestingLevel,
                nestingCutOffLimit = nestingCutOffLimit,
                postResources = referencedPostResources,
                expanded = expanded,
                containerColor = referencedEventsContainerColor,
                noteCallbacks = noteCallbacks,
                hasBorder = referencedEventsHaveBorder,
                couldAutoPlay = couldAutoPlay,
                onVideoSoundToggle = onVideoSoundToggle,
            )
        }

        val referencedArticleResources = data.partitions.referencedArticles
        if (referencedArticleResources.isNotEmpty()) {
            ReferencedArticlesColumn(
                modifier = Modifier.padding(top = 4.dp),
                articleResources = referencedArticleResources,
                expanded = expanded,
                containerColor = referencedEventsContainerColor,
                noteCallbacks = noteCallbacks,
                hasBorder = referencedEventsHaveBorder,
            )
        }

        val referencedZaps = data.partitions.referencedZaps
        referencedZaps.forEach { zap ->
            val zappedEventId = zap.zappedEventId
            val zappedEventContent = zap.zappedEventContent
            if (zappedEventId != null && zappedEventContent?.isNotEmpty() == true) {
                ReferencedNoteZap(
                    senderId = zap.senderId,
                    receiverId = zap.receiverId,
                    noteContentUi = NoteContentUi(
                        noteId = zappedEventId,
                        content = zappedEventContent,
                        nostrUris = zap.zappedEventNostrUris.map { it.asNoteNostrUriUi() },
                        hashtags = zap.zappedEventHashtags,
                    ),
                    amountInSats = zap.amountInSats.toULong(),
                    createdAt = Instant.ofEpochSecond(zap.createdAt),
                    noteCallbacks = noteCallbacks,
                    message = zap.message,
                    senderAvatarCdnImage = zap.senderAvatarCdnImage,
                    receiverDisplayName = zap.receiverDisplayName,
                    receiverAvatarCdnImage = zap.receiverAvatarCdnImage,
                )
            } else {
                ReferencedZap(
                    senderId = zap.senderId,
                    senderAvatarCdnImage = zap.senderAvatarCdnImage,
                    receiverId = zap.receiverId,
                    receiverDisplayName = zap.receiverDisplayName,
                    receiverAvatarCdnImage = zap.receiverAvatarCdnImage,
                    amountInSats = zap.amountInSats,
                    message = zap.message,
                    noteCallbacks = noteCallbacks,
                )
            }
        }

        val genericEvents = data.partitions.unsupportedEvents
        if (genericEvents.isNotEmpty() && (nestingLevel < NOT_FOUND_NOTICE_CUT_OFF_LEVEL)) {
            genericEvents.forEachIndexed { index, nostrUriUi ->
                NoteUnknownEvent(
                    modifier = Modifier.fillMaxWidth(),
                    icon = nostrUriUi.uri.nostrUriToMissingEventIcon(),
                    altDescription = nostrUriUi.referencedEventAlt
                        ?: nostrUriUi.uri.nostrUriToMissingEventAltDescription(),
                )

                if (index < genericEvents.size - 1) {
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }

        if (data.poll != null) {
            NotePollContent(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (contentText.isEmpty()) 4.dp else 6.dp),
                poll = data.poll,
                onOptionSelected = { optionId -> onPollOptionSelected?.invoke(optionId) },
                onVotesClick = noteCallbacks.onPollVotesClick?.let { callback ->
                    { callback(data.noteId) }
                },
            )
        }
    }
}

private sealed class NoteTranslationState {
    data object Original : NoteTranslationState()
    data object Loading : NoteTranslationState()
    data class Translated(val text: String) : NoteTranslationState()
    data object Error : NoteTranslationState()
}

/**
 * A small, muted, system-styled affordance distinct from the reply/zap/like/repost row below it,
 * so it reads as the app offering a translation rather than something the note's author wrote.
 */
@Composable
private fun NoteTranslateAction(
    state: NoteTranslationState,
    onTranslateClick: () -> Unit,
    onSeeOriginalClick: () -> Unit,
) {
    val mutedColor = AppTheme.extraColorScheme.onSurfaceVariantAlt1

    Row(
        modifier = Modifier.padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (state) {
            is NoteTranslationState.Original -> {
                Icon(
                    modifier = Modifier.size(14.dp),
                    imageVector = Icons.Filled.Translate,
                    contentDescription = null,
                    tint = mutedColor,
                )
                Text(
                    modifier = Modifier.clickable(onClick = onTranslateClick),
                    text = stringResource(id = R.string.note_translate_button),
                    style = AppTheme.typography.bodySmall,
                    color = mutedColor,
                )
            }

            is NoteTranslationState.Loading -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    color = mutedColor,
                    strokeWidth = 2.dp,
                )
                Text(
                    text = stringResource(id = R.string.note_translate_loading),
                    style = AppTheme.typography.bodySmall,
                    color = mutedColor,
                )
            }

            is NoteTranslationState.Translated -> {
                Icon(
                    modifier = Modifier.size(14.dp),
                    imageVector = Icons.Filled.Translate,
                    contentDescription = null,
                    tint = mutedColor,
                )
                Text(
                    text = stringResource(id = R.string.note_translated_footer),
                    style = AppTheme.typography.bodySmall,
                    color = mutedColor,
                )
                Text(
                    modifier = Modifier.clickable(onClick = onSeeOriginalClick),
                    text = stringResource(id = R.string.note_see_original),
                    style = AppTheme.typography.bodySmall,
                    color = mutedColor,
                    textDecoration = TextDecoration.Underline,
                )
            }

            is NoteTranslationState.Error -> {
                Text(
                    modifier = Modifier.clickable(onClick = onTranslateClick),
                    text = stringResource(id = R.string.note_translate_error),
                    style = AppTheme.typography.bodySmall,
                    color = mutedColor,
                )
            }
        }
    }
}

@Composable
private fun String.nostrUriToMissingEventAltDescription(): String {
    return if (contains("note1")) {
        stringResource(R.string.feed_missing_event_alt_description_note)
    } else {
        stringResource(R.string.feed_missing_event_alt_description_event)
    }
}

@Composable
private fun String.nostrUriToMissingEventIcon(): ImageVector {
    return if (contains("note1")) {
        Icons.Outlined.ErrorOutline
    } else {
        PrimalIcons.Document
    }
}

private fun AnnotatedString.Range<String>.handleAnnotationClick(
    onProfileClick: ((String) -> Unit)?,
    onUrlClick: ((String) -> Unit)?,
    onPostClick: ((String) -> Unit)?,
    onHashtagClick: ((String) -> Unit)?,
    onArticleClick: ((naddr: String) -> Unit)?,
) = when (this.tag) {
    PROFILE_ID_ANNOTATION_TAG -> onProfileClick?.invoke(this.item)
    URL_ANNOTATION_TAG -> onUrlClick?.invoke(this.item)
    NOTE_ANNOTATION_TAG -> onPostClick?.invoke(this.item)
    HASHTAG_ANNOTATION_TAG -> onHashtagClick?.invoke(this.item)
    NOSTR_ADDRESS_ANNOTATION_TAG -> {
        this.item.split(":").lastOrNull()?.let { address ->
            onArticleClick?.invoke(address)
        }
    }

    else -> Unit
}

fun renderContentAsAnnotatedString(
    data: NoteContentUi,
    expanded: Boolean,
    seeMoreText: String,
    highlightColor: Color,
    shouldKeepNostrNoteUris: Boolean = false,
): AnnotatedString =
    computeRenderedNoteContent(
        data = data,
        expanded = expanded,
        shouldKeepNostrNoteUris = shouldKeepNostrNoteUris,
    ).toAnnotatedString(seeMoreText = seeMoreText, highlightColor = highlightColor)

@Preview
@Composable
fun PreviewPostContent() {
    PrimalPreview(primalTheme = PrimalTheme.Midnight) {
        Surface {
            NoteContent(
                data = NoteContentUi(
                    noteId = "",
                    content = """
                        Hey there nostr:referencedUser, how is life? #nostr 
                    """.trimIndent(),
                    uris = emptyList(),
                    nostrUris = listOf(
                        NoteNostrUriUi(
                            uri = "nostr:referencedUser",
                            type = EventUriNostrType.Profile,
                            referencedEventAlt = null,
                            referencedNote = null,
                            referencedUser = ReferencedUser(
                                userId = "nostr:referencedUser",
                                handle = "alex",
                            ),
                            referencedArticle = null,
                            referencedHighlight = null,
                            referencedZap = null,
                            referencedStream = null,
                            position = 0,
                        ),
                    ),
                    hashtags = listOf("#nostr"),
                ),
                expanded = false,
                onClick = {},
                onUrlClick = {},
                noteCallbacks = NoteCallbacks(),
            )
        }
    }
}

@Preview
@Composable
fun PreviewPostUnknownReferencedEventWithAlt() {
    PrimalPreview(primalTheme = PrimalTheme.Midnight) {
        Surface(modifier = Modifier.fillMaxWidth()) {
            NoteContent(
                data = NoteContentUi(
                    noteId = "",
                    content = "This is amazing! nostr:nevent124124124214123412",
                    uris = emptyList(),
                    nostrUris = listOf(
                        NoteNostrUriUi(
                            uri = "nostr:nevent124124124214123412",
                            type = EventUriNostrType.Unsupported,
                            referencedEventAlt = "This is a music song.",
                            referencedNote = null,
                            referencedUser = null,
                            referencedArticle = null,
                            referencedHighlight = null,
                            referencedZap = null,
                            referencedStream = null,
                            position = 0,
                        ),
                    ),
                    hashtags = listOf("#nostr"),
                ),
                expanded = false,
                onClick = {},
                onUrlClick = {},
                noteCallbacks = NoteCallbacks(),
            )
        }
    }
}

@Preview
@Composable
fun PreviewPostUnknownReferencedEventWithoutAlt() {
    PrimalPreview(primalTheme = PrimalTheme.Midnight) {
        Surface(modifier = Modifier.fillMaxWidth()) {
            NoteContent(
                data = NoteContentUi(
                    noteId = "",
                    content = "This is amazing! nostr:note111",
                    uris = emptyList(),
                    nostrUris = listOf(
                        NoteNostrUriUi(
                            uri = "nostr:note111",
                            type = EventUriNostrType.Unsupported,
                            referencedEventAlt = null,
                            referencedNote = null,
                            referencedUser = null,
                            referencedArticle = null,
                            referencedHighlight = null,
                            referencedZap = null,
                            referencedStream = null,
                            position = 0,
                        ),
                    ),
                    hashtags = listOf("#nostr"),
                ),
                expanded = false,
                onClick = {},
                onUrlClick = {},
                noteCallbacks = NoteCallbacks(),
            )
        }
    }
}

@Preview
@Composable
@Suppress("LongMethod")
fun PreviewPostContentWithReferencedPost() {
    PrimalPreview(primalTheme = PrimalTheme.Midnight) {
        Surface {
            NoteContent(
                data = NoteContentUi(
                    noteId = "",
                    content = """
                        Unfortunately the days of using pseudonyms in metaspace are numbered. #nostr
                        
                        nostr:referencedPost
                        
                        Or maybe not.
                        
                        nostr:referenced2Post
                    """.trimIndent(),
                    uris = emptyList(),
                    nostrUris = listOf(
                        NoteNostrUriUi(
                            uri = "nostr:referencedPost",
                            type = EventUriNostrType.Note,
                            referencedNote = ReferencedNote(
                                postId = "postId",
                                kind = NostrEventKind.ShortTextNote.value,
                                createdAt = 0,
                                content = "This is referenced post.",
                                authorId = "authorId",
                                authorName = "librenostr",
                                authorAvatarCdnImage = null,
                                authorInternetIdentifier = "hi@nostrich.org",
                                authorLightningAddress = "h@getalby.com",
                                attachments = emptyList(),
                                nostrUris = emptyList(),
                                authorLegendProfile = null,
                                raw = "",
                            ),
                            referencedUser = null,
                            referencedArticle = null,
                            referencedEventAlt = null,
                            referencedHighlight = null,
                            referencedZap = null,
                            referencedStream = null,
                            position = 0,
                        ),
                        NoteNostrUriUi(
                            uri = "nostr:referenced2Post",
                            type = EventUriNostrType.Note,
                            referencedNote = ReferencedNote(
                                postId = "postId",
                                kind = NostrEventKind.ShortTextNote.value,
                                createdAt = 0,
                                content = "This is referenced post #2.",
                                authorId = "authorId",
                                authorName = "librenostr",
                                authorAvatarCdnImage = null,
                                authorInternetIdentifier = "hi@nostrich.org",
                                authorLightningAddress = "h@getalby.com",
                                attachments = emptyList(),
                                nostrUris = emptyList(),
                                authorLegendProfile = null,
                                raw = "",
                            ),
                            referencedUser = null,
                            referencedArticle = null,
                            referencedEventAlt = null,
                            referencedHighlight = null,
                            referencedZap = null,
                            referencedStream = null,
                            position = 1,
                        ),
                    ),
                    hashtags = listOf("#nostr"),
                    invoices = listOf(
                        "lnbc888550n1pnp6fz9pp5als09l5nfj9pkqk7mpj6cz6075nd4v95ljz0p65n8zkz03p75t3" +
                            "sdp9wdshgueqvehhygr3v9q8qunfd4skctnwv46r5cqzzsxqrrs0fppqyyu34ypjxgclynk64hz2r6" +
                            "ddudpaf5mesp5c8mv8xdu67pra93m3j9aw9mxh08gk09upmjsdpspjxcgcrfjyc0s9qyyssqng6uu0" +
                            "z84h7wlcrlyqywl6jlfd4630k4yd056d3q9h9rg9tzmza5adpzjn489fees4vq0armdskuqgxxvug3" +
                            "et34cqdxj6ldu8lkd2cqcvx5am",
                    ),
                ),
                expanded = false,
                onClick = {},
                onUrlClick = {},
                noteCallbacks = NoteCallbacks(),
            )
        }
    }
}
