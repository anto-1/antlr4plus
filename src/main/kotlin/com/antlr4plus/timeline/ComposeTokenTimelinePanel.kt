package com.antlr4plus.timeline

import androidx.compose.foundation.*
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antlr4plus.color.darken
import com.antlr4plus.color.isIntelliJDarkTheme
import com.antlr4plus.color.rememberIsLightTheme
import com.antlr4plus.color.rememberThemeBackgroundColor
import com.antlr4plus.color.rememberThemeScrollbarColor
import com.antlr4plus.color.rememberThemeTextColor
import com.antlr4plus.compat.compatClickable
import com.antlr4plus.compose.ComposeCompat
import com.antlr4plus.compose.CopyMarkdownButton
import com.antlr4plus.compose.previewScrollbarStyle
import com.antlr4plus.notifications.NotificationsUtils
import com.antlr4plus.preview.CenteredSelectionScrollPolicy
import com.antlr4plus.state.PreviewState
import com.antlr4plus.util.rethrowIfControlFlow
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.codeStyle.NameUtil
import java.awt.datatransfer.StringSelection
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import org.jetbrains.jewel.ui.Orientation as JewelOrientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text

class ComposeTokenTimelinePanel(private val project: Project) {

    private data class VerticalScrollRequest(
        val tokenIndex: Int,
        val immediate: Boolean,
        val requestId: Long,
    )

    companion object {
        val TOKEN_HIGHLIGHT_KEY: com.intellij.openapi.util.Key<Boolean> =
            com.intellij.openapi.util.Key.create("ANTLR4Plus.TokenHighlight")
    }

    private var timeline by mutableStateOf<TokenTimeline?>(null)
    private var previewState by mutableStateOf<PreviewState?>(null)
    private var previewFile: VirtualFile? = null

    // Include a request ID so repeated requests for the same offset rerun LaunchedEffect.
    private var pendingScrollToOffset by mutableStateOf<Pair<Int, Long>?>(null)
    private var scrollRequestId = 0L

    private var isHighlightingEnabled by mutableStateOf(false)
    private val highlightingGeneration = AtomicLong()
    private val selectionGeneration = AtomicLong()

    // Prevent timeline-originated caret movement from feeding back into a single-token selection.
    private val editorSelectionWriteDepth = AtomicInteger()

    private var breathingStopper: Runnable? = null

    private val modeColors =
        listOf(
            Color(0xFF4A9EFF),
            Color(0xFF7CBA5B),
            Color(0xFFF0A040),
            Color(0xFFE05858),
            Color(0xFF9B59B6),
            Color(0xFF1ABC9C),
            Color(0xFFF39C12),
            Color(0xFFE74C3C),
        )

    private val tokenTypeColors =
        listOf(
            Color(0xFFE06C75),
            Color(0xFF98C379),
            Color(0xFFE5C07B),
            Color(0xFF61AFEF),
            Color(0xFFC678DD),
            Color(0xFF56B6C2),
            Color(0xFFD19A66),
            Color(0xFF7EC699),
            Color(0xFFE0C285),
            Color(0xFF82AAFF),
            Color(0xFFBB80FF),
            Color(0xFF89DDFF),
            Color(0xFFABB2BF),
            Color(0xFF5C6370),
            Color(0xFFFF7B72),
            Color(0xFF7EE787),
        )

    private val tokenTypeColorCache = mutableMapOf<String, Color>()

    fun getComponent(): ComposePanel = ComposeCompat.createComposePanel {
        TokenTimelineUI()
    }

    fun setTimelineData(newTimeline: TokenTimeline?, state: PreviewState?) {
        val highlightRequestId = highlightingGeneration.incrementAndGet()
        selectionGeneration.incrementAndGet()
        stopBreathing()
        val wasHighlightingEnabled = isHighlightingEnabled

        // Clear old-file highlights before replacing the preview state.
        val oldFile = previewFile
        val newFile = state?.g4pInputFile
        val isFileSwitch = oldFile != null && newFile != null && oldFile.path != newFile.path
        if (isFileSwitch) {
            clearTokenHighlighting()
        }

        // Publish related Compose state in one mutable snapshot.
        Snapshot.withMutableSnapshot {
            timeline = newTimeline
            previewState = state
        }
        previewFile = newFile

        val persistedColorize =
            try {
                project.service<com.antlr4plus.settings.ProjectSettingsService>().state.colorizeTokens
            } catch (_: Exception) {
                false
            }

        if (newTimeline != null && (wasHighlightingEnabled || persistedColorize) && !isFileSwitch) {
            isHighlightingEnabled = true
            applyTokenHighlighting(newTimeline, newFile, highlightRequestId)
        } else if (newTimeline != null && isFileSwitch && persistedColorize) {
            isHighlightingEnabled = true
            applyTokenHighlighting(newTimeline, newFile, highlightRequestId)
        } else if (newTimeline == null || isFileSwitch) {
            isHighlightingEnabled = false
        }
    }

    fun resetData() {
        highlightingGeneration.incrementAndGet()
        selectionGeneration.incrementAndGet()
        stopBreathing()
        Snapshot.withMutableSnapshot {
            timeline = null
            previewState = null
            pendingScrollToOffset = null
            // Highlight enablement is reset separately by clearHighlightingState.
        }
    }

    fun getTokensForExport(): List<TokenWithMode>? = timeline?.tokens

    fun clearHighlightingState() {
        highlightingGeneration.incrementAndGet()
        if (isHighlightingEnabled) {
            clearTokenHighlighting()
            isHighlightingEnabled = false
        }
        stopBreathing()
    }

    fun stopBreathing() {
        breathingStopper?.run()
        breathingStopper = null
    }

    fun isHighlightingActive(): Boolean = isHighlightingEnabled

    fun enableHighlighting() {
        if (!isHighlightingEnabled) {
            isHighlightingEnabled = true
            val t = timeline ?: return
            val file = previewFile ?: return
            val highlightRequestId = highlightingGeneration.incrementAndGet()
            focusInputFileEditor(file, highlightRequestId) {
                applyTokenHighlighting(t, file, highlightRequestId)
            }
        }
    }

    fun disableHighlighting() {
        if (isHighlightingEnabled) {
            highlightingGeneration.incrementAndGet()
            clearTokenHighlighting()
            isHighlightingEnabled = false
        }
    }

    fun selectTokenAtOffset(editorOffset: Int) {
        if (editorSelectionWriteDepth.get() > 0) return
        selectionGeneration.incrementAndGet()
        Snapshot.withMutableSnapshot {
            pendingScrollToOffset = Pair(editorOffset, ++scrollRequestId)
        }
    }

    @Composable
    private fun TokenTimelineUI() {
        val isLightTheme = rememberIsLightTheme()
        val backgroundColor = rememberThemeBackgroundColor()
        val highlightBackgroundColor =
            remember(isLightTheme) {
                val baseColor = Color(0xFFA6D2FF)
                if (isLightTheme) baseColor else baseColor.darken(0.6f)
            }
        val scrollbarColor = rememberThemeScrollbarColor()
        val scrollbarStyle =
            remember(scrollbarColor) {
                previewScrollbarStyle(scrollbarColor, unhoverAlpha = 0.12f, hoverAlpha = 0.25f)
            }
        val textColorCompose = rememberThemeTextColor()
        var tokenSelection by remember(timeline) { mutableStateOf(TokenSelectionState()) }
        val selectedTokenIndices = tokenSelection.indices
        val lazyListState = rememberLazyListState()
        val focusRequester = remember { FocusRequester() }
        var verticalScrollRequest by remember { mutableStateOf<VerticalScrollRequest?>(null) }
        val verticalScrollTiming = remember { longArrayOf(0L, 0L) }
        var hiddenChannels by remember { mutableStateOf(emptySet<Int>()) }
        var searchQuery by remember { mutableStateOf("") }

        val currentPendingOffset = pendingScrollToOffset
        val currentTimeline = timeline
        val visibleTokensWithIndices: List<Pair<Int, TokenWithMode>> =
            remember(
                currentTimeline,
                hiddenChannels,
                searchQuery,
            ) {
                if (currentTimeline == null) {
                    emptyList()
                } else {
                    val matcher =
                        if (searchQuery.isBlank()) {
                            null
                        } else {
                            NameUtil.buildMatcher("*$searchQuery").build()
                        }
                    currentTimeline.tokens.mapIndexedNotNull { index, token ->
                        if (token.channel in hiddenChannels) return@mapIndexedNotNull null
                        if (matcher != null && !matcher.matches(currentTimeline.getTokenTypeName(token))) {
                            return@mapIndexedNotNull null
                        }
                        index to token
                    }
                }
            }
        val visibleTokenIndices =
            remember(visibleTokensWithIndices) {
                visibleTokensWithIndices.map { it.first }
            }
        fun requestVerticalCenter(tokenIndex: Int) {
            val currentTime = System.nanoTime()
            val immediate =
                CenteredSelectionScrollPolicy.isHighFrequency(
                    verticalScrollTiming[0],
                    currentTime,
                )
            verticalScrollTiming[0] = currentTime
            verticalScrollTiming[1] += 1L
            verticalScrollRequest =
                VerticalScrollRequest(
                    tokenIndex = tokenIndex,
                    immediate = immediate,
                    requestId = verticalScrollTiming[1],
                )
        }
        LaunchedEffect(verticalScrollRequest) {
            val request = verticalScrollRequest ?: return@LaunchedEffect
            val displayIndex = visibleTokenIndices.indexOf(request.tokenIndex)
            if (displayIndex >= 0) {
                lazyListState.centerItemVertically(displayIndex, request.immediate)
            }
        }
        LaunchedEffect(currentPendingOffset) {
            if (currentPendingOffset != null && currentTimeline != null) {
                val editorOffset = currentPendingOffset.first
                // Past or between tokens, select the nearest preceding token.
                var tokenIndex =
                    currentTimeline.tokens.indexOfFirst { token ->
                        editorOffset >= token.startIndex && editorOffset <= token.stopIndex
                    }
                if (tokenIndex < 0) {
                    tokenIndex = currentTimeline.tokens.indexOfLast { it.startIndex <= editorOffset }
                }
                if (tokenIndex >= 0) {
                    tokenSelection = TokenSelectionState.single(tokenIndex)

                    val displayIndex = visibleTokenIndices.indexOf(tokenIndex)

                    if (displayIndex >= 0) {
                        requestVerticalCenter(tokenIndex)
                    }
                }
                pendingScrollToOffset = null
            }
        }

        Column(
            modifier =
                Modifier.fillMaxSize()
                    .background(backgroundColor)
                    .focusable()
                    .focusRequester(focusRequester)
                    .onKeyEvent { keyEvent ->
                        try {
                            if (keyEvent.type == KeyEventType.KeyDown) {
                                val awtEvent = java.awt.EventQueue.getCurrentEvent()
                                val modifiers = (awtEvent as? java.awt.event.InputEvent)?.modifiersEx ?: 0
                                val isCtrlOrCmd =
                                    (modifiers and java.awt.event.InputEvent.CTRL_DOWN_MASK) != 0 ||
                                        (modifiers and java.awt.event.InputEvent.META_DOWN_MASK) != 0
                                val isShift = (modifiers and java.awt.event.InputEvent.SHIFT_DOWN_MASK) != 0

                                when {
                                    isCtrlOrCmd && keyEvent.key == Key.A -> {
                                        currentTimeline?.let { timeline ->
                                            tokenSelection = TokenSelectionState.all(visibleTokenIndices)
                                            if (tokenSelection.indices.isNotEmpty()) {
                                                highlightSelectionInEditor(
                                                    timeline.tokens,
                                                    tokenSelection.indices,
                                                    selectionGeneration.incrementAndGet(),
                                                    activeIndex = tokenSelection.activeIndex,
                                                )
                                            }
                                        }
                                        true
                                    }
                                    !isCtrlOrCmd &&
                                        (keyEvent.key == Key.DirectionUp || keyEvent.key == Key.DirectionDown) -> {
                                        val direction =
                                            if (keyEvent.key == Key.DirectionUp) {
                                                TokenNavigationDirection.PREVIOUS
                                            } else {
                                                TokenNavigationDirection.NEXT
                                            }
                                        val nextSelection =
                                            tokenSelection.move(
                                                visibleIndices = visibleTokenIndices,
                                                direction = direction,
                                                extend = isShift,
                                            )
                                        if (nextSelection != tokenSelection) {
                                            tokenSelection = nextSelection
                                            val selectionRequestId = selectionGeneration.incrementAndGet()
                                            val activeIndex = nextSelection.activeIndex
                                            if (nextSelection.indices.isNotEmpty()) {
                                                currentTimeline?.let { timeline ->
                                                    highlightSelectionInEditor(
                                                        timeline.tokens,
                                                        nextSelection.indices,
                                                        selectionRequestId,
                                                        activeIndex = activeIndex,
                                                    )
                                                }
                                            }

                                            if (activeIndex != null) {
                                                requestVerticalCenter(activeIndex)
                                            }
                                        }
                                        true
                                    }
                                    keyEvent.key == Key.Escape -> {
                                        if (searchQuery.isNotEmpty()) {
                                            searchQuery = ""
                                            true
                                        } else {
                                            tokenSelection = TokenSelectionState()
                                            selectionGeneration.incrementAndGet()
                                            true
                                        }
                                    }
                                    keyEvent.key == Key.Backspace -> {
                                        if (searchQuery.isNotEmpty()) {
                                            searchQuery = searchQuery.dropLast(1)
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                    !isCtrlOrCmd -> {
                                        val codePoint = keyEvent.utf16CodePoint
                                        if (codePoint > 0 && Character.isLetterOrDigit(codePoint)) {
                                            val char = codePoint.toChar()
                                            searchQuery += char
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                    else -> false
                                }
                            } else {
                                false
                            }
                        } catch (e: IllegalStateException) {
                            rethrowIfControlFlow(e)
                            false
                        }
                    },
        ) {
            ModeHeader(
                backgroundColor = backgroundColor,
                textColorCompose = textColorCompose,
                hiddenChannels = hiddenChannels,
                onToggleChannel = { channel ->
                    hiddenChannels =
                        if (channel in hiddenChannels) {
                            hiddenChannels - channel
                        } else {
                            hiddenChannels + channel
                        }
                },
            )

            Divider(
                orientation = JewelOrientation.Horizontal,
                color = Color(30, 31, 34),
                thickness = 1.dp,
            )

            Box(modifier = Modifier.weight(1f)) {
                if (currentTimeline == null || currentTimeline.tokens.isEmpty()) {
                    var showEmptyState by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        delay(4000L.milliseconds)
                        showEmptyState = true
                    }
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (showEmptyState) {
                            Text(
                                text = "No tokens to display. Parse a file to see the token timeline.",
                                color = timelineTextColorWithAlpha(textColorCompose, 0.6f),
                                fontSize = 14.sp,
                                fontFamily = FontFamily.SansSerif,
                            )
                        }
                    }
                } else {
                    TimelineVisualization(
                        timeline = currentTimeline,
                        selectedTokenIndices = selectedTokenIndices,
                        visibleTokensWithIndices = visibleTokensWithIndices,
                        onTokenClick = { index, isShift, isCtrl ->
                            tokenSelection =
                                tokenSelection.click(
                                    index = index,
                                    extend = isShift,
                                    toggle = isCtrl,
                                    selectionAxis = visibleTokenIndices,
                                )
                            val selectionRequestId = selectionGeneration.incrementAndGet()
                            if (tokenSelection.indices.isNotEmpty()) {
                                highlightSelectionInEditor(
                                    currentTimeline.tokens,
                                    tokenSelection.indices,
                                    selectionRequestId,
                                    activeIndex = index,
                                )
                            }
                            // Restore timeline focus for speed search after selection updates.
                            focusRequester.requestFocus()
                        },
                        lazyListState = lazyListState,
                        textColorCompose = textColorCompose,
                        highlightBackgroundColor = highlightBackgroundColor,
                        scrollbarStyle = scrollbarStyle,
                        onCopyToMarkdown = { copyTimelineToMarkdown(selectedTokenIndices) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                if (searchQuery.isNotEmpty()) {
                    Box(
                        modifier =
                            Modifier.align(Alignment.TopStart)
                                .padding(12.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (isLightTheme) Color(0xFFF5F5F5) else Color(0xFF3C3F41),
                                )
                                .border(
                                    width = 1.dp,
                                    color = Color(0xFF4A9EFF),
                                    shape = RoundedCornerShape(6.dp),
                                )
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = searchQuery,
                                color = textColorCompose,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium,
                            )
                            val matchCount = visibleTokensWithIndices.size
                            Text(
                                text = "($matchCount)",
                                color = if (matchCount > 0) Color(0xFF4A9EFF) else Color(0xFFE05858),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ModeHeader(
        backgroundColor: Color,
        textColorCompose: Color,
        hiddenChannels: Set<Int>,
        onToggleChannel: (Int) -> Unit,
    ) {
        val currentTimeline = timeline
        val usedModes = currentTimeline?.getUsedModes() ?: emptyList()
        val usedChannels = currentTimeline?.getUsedChannels() ?: emptyList()

        Row(
            modifier = Modifier.fillMaxWidth().height(32.dp).background(backgroundColor).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Lexer",
                color = textColorCompose,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif,
                maxLines = 1,
                softWrap = false,
            )

            Spacer(modifier = Modifier.width(12.dp))

            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(usedModes.size) { index ->
                    val modeName = usedModes[index]
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(
                            modifier = Modifier.size(12.dp).background(getModeColor(index), RoundedCornerShape(2.dp)),
                        )
                        Text(
                            text = modeName,
                            color = timelineTextColorWithAlpha(textColorCompose, 0.8f),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }

            if (usedChannels.size > 1) {
                Spacer(modifier = Modifier.width(12.dp))

                Box(
                    modifier = Modifier.width(1.dp).height(16.dp).background(textColorCompose.copy(alpha = 0.3f)),
                )

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = "Hide:",
                    color = timelineTextColorWithAlpha(textColorCompose, 0.5f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    softWrap = false,
                )

                Spacer(modifier = Modifier.width(6.dp))

                usedChannels.forEach { (channel, channelName) ->
                    val isHidden = channel in hiddenChannels
                    Row(
                        modifier =
                            Modifier.clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (isHidden) textColorCompose.copy(alpha = 0.15f) else Color.Transparent,
                                )
                                .compatClickable { onToggleChannel(channel) }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = channelName,
                            color =
                                if (isHidden) timelineTextColorWithAlpha(textColorCompose, 0.9f)
                                else timelineTextColorWithAlpha(textColorCompose, 0.5f),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (isHidden) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Text(
                text = "${currentTimeline?.tokens?.size ?: 0} tokens",
                color = timelineTextColorWithAlpha(textColorCompose, 0.6f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                softWrap = false,
            )
        }
    }

    @Composable
    private fun TimelineVisualization(
        timeline: TokenTimeline,
        selectedTokenIndices: Set<Int>,
        visibleTokensWithIndices: List<Pair<Int, TokenWithMode>>,
        onTokenClick: (index: Int, isShift: Boolean, isCtrl: Boolean) -> Unit,
        lazyListState: androidx.compose.foundation.lazy.LazyListState,
        textColorCompose: Color,
        highlightBackgroundColor: Color,
        scrollbarStyle: ScrollbarStyle,
        onCopyToMarkdown: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        // Debounce viewport-driven width estimates to avoid resizing on every scroll frame.
        var columnWidths by remember {
            mutableStateOf(TokenTimelineColumnWidths(indexWidth = 100.dp, tokenTypeWidth = 80.dp))
        }

        @OptIn(FlowPreview::class)
        LaunchedEffect(visibleTokensWithIndices) {
            snapshotFlow {
                    lazyListState.firstVisibleItemIndex to lazyListState.layoutInfo.visibleItemsInfo.size
                }
                .debounce(50.milliseconds)
                .collect { (_, _) ->
                    val visibleItems = lazyListState.layoutInfo.visibleItemsInfo
                    if (visibleItems.isNotEmpty() && visibleTokensWithIndices.isNotEmpty()) {
                        var maxIndexChars = 0
                        var maxTokenTypeChars = 0

                        for (itemInfo in visibleItems) {
                            val idx = itemInfo.index
                            if (idx >= 0 && idx < visibleTokensWithIndices.size) {
                                val (originalIndex, tokenWithMode) = visibleTokensWithIndices[idx]
                                val indexText =
                                    "$originalIndex: lvl:${tokenWithMode.modeDepth} ${tokenWithMode.line}:${tokenWithMode.charPositionInLine}"
                                maxIndexChars = maxOf(maxIndexChars, indexText.length)
                                val typeName = timeline.getTokenTypeName(tokenWithMode)
                                maxTokenTypeChars = maxOf(maxTokenTypeChars, typeName.length)
                            }
                        }

                        // Estimate monospace character width at this row font size.
                        val charWidth = 6.5f
                        val indexWidth = (maxIndexChars * charWidth + 8f).dp
                        val tokenTypeWidth = (maxTokenTypeChars * charWidth + 8f).dp

                        columnWidths =
                            TokenTimelineColumnWidths(
                                indexWidth = indexWidth.coerceIn(60.dp, 180.dp),
                                tokenTypeWidth = tokenTypeWidth.coerceIn(50.dp, 160.dp),
                            )
                    }
                }
        }

        val minContentWidth = 700.dp
        var containerWidthPx by remember { mutableStateOf(0) }
        val density = LocalDensity.current
        val containerWidth: Dp =
            with(density) {
                maxOf(containerWidthPx.toDp(), minContentWidth)
            }
        val horizontalScrollState = rememberScrollState()

        Box(modifier = modifier) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier =
                        Modifier.weight(1f)
                            .fillMaxHeight()
                            .onSizeChanged { size ->
                                containerWidthPx = size.width
                            }
                            .horizontalScroll(horizontalScrollState),
                ) {
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier.width(containerWidth).fillMaxHeight().padding(end = 12.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        itemsIndexed(
                            items = visibleTokensWithIndices,
                            key = { _, (originalIndex, _) -> originalIndex },
                        ) { displayIndex, (originalIndex, tokenWithMode) ->
                            val isSelected = originalIndex in selectedTokenIndices
                            val modeColor = getModeColor(tokenWithMode.mode)
                            val tokenTypeName = timeline.getTokenTypeName(tokenWithMode)
                            val tokenTypeColor = getTokenTypeColor(tokenTypeName)
                            val prevVisibleToken =
                                if (displayIndex > 0) visibleTokensWithIndices[displayIndex - 1].second else null
                            val modeChanged = prevVisibleToken != null && prevVisibleToken.mode != tokenWithMode.mode

                            TokenRow(
                                tokenWithMode = tokenWithMode,
                                tokenTypeName = tokenTypeName,
                                index = originalIndex,
                                isSelected = isSelected,
                                modeColor = modeColor,
                                tokenTypeColor = tokenTypeColor,
                                modeChanged = modeChanged,
                                textColorCompose = textColorCompose,
                                highlightBackgroundColor = highlightBackgroundColor,
                                columnWidths = columnWidths,
                                onTokenClick = onTokenClick,
                            )
                        }
                    }
                }

                VerticalScrollbar(
                    modifier = Modifier.fillMaxHeight(),
                    adapter = rememberScrollbarAdapter(lazyListState),
                    style = scrollbarStyle,
                )
            }

            HorizontalScrollbar(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(end = 12.dp),
                adapter = rememberScrollbarAdapter(horizontalScrollState),
                style = scrollbarStyle,
            )

            // Consume pointer events here so underlying rows do not show tooltips.
            Column(
                modifier =
                    Modifier.align(Alignment.BottomEnd).padding(16.dp).pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                            }
                        }
                    },
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.End,
            ) {
                CopyMarkdownButton(
                    onClick = onCopyToMarkdown,
                    selectedCount = selectedTokenIndices.size,
                )
            }
        }
    }

    private val isLightTheme: Boolean
        get() = !isIntelliJDarkTheme()

    private fun getModeColor(modeIndex: Int): Color {
        val baseColor = modeColors[modeIndex % modeColors.size]
        return if (isLightTheme) baseColor.darken(0.4f) else baseColor
    }

    private fun getTokenTypeColor(tokenTypeName: String): Color {
        val baseColor =
            tokenTypeColorCache.getOrPut(tokenTypeName) {
                val hash = tokenTypeName.hashCode()
                tokenTypeColors[kotlin.math.abs(hash) % tokenTypeColors.size]
            }
        return if (isLightTheme) baseColor.darken(0.4f) else baseColor
    }

    /** Highlights the span from the first selected token to the last, including any gaps. */
    private fun highlightSelectionInEditor(
        tokens: List<TokenWithMode>,
        selectedIndices: Set<Int>,
        selectionRequestId: Long,
        activeIndex: Int? = null,
    ) {
        if (selectedIndices.isEmpty()) return

        val file = previewFile ?: return

        if (project.isDisposed) return

        val selectedTokens = selectedIndices.mapNotNull { index ->
            tokens.getOrNull(index)
        }
        if (selectedTokens.isEmpty()) return

        val aggregateStart = selectedTokens.minOf { it.startIndex }
        val aggregateEnd = selectedTokens.maxOf { it.stopIndex }
        val activeOffset = activeIndex?.let(tokens::getOrNull)?.startIndex ?: aggregateStart

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            if (selectionRequestId != selectionGeneration.get() || previewFile?.path != file.path) {
                return@invokeLater
            }

            // Opening a closed editor may synchronously emit its initial caret event. Guard the
            // whole operation so that event cannot collapse a Shift range before we position it.
            editorSelectionWriteDepth.incrementAndGet()
            try {
                val fileEditorManager = FileEditorManager.getInstance(project)
                fileEditorManager.openFile(file, false)
                if (selectionRequestId != selectionGeneration.get() || previewFile?.path != file.path) {
                    return@invokeLater
                }
                val editors = fileEditorManager.getEditors(file)
                val editor = editors.filterIsInstance<TextEditor>().firstOrNull()?.editor ?: return@invokeLater

                ApplicationManager.getApplication().runReadAction {
                    if (
                        aggregateStart >= 0 &&
                            aggregateEnd >= aggregateStart &&
                            aggregateEnd < editor.document.textLength
                    ) {
                        editor.caretModel.moveToOffset(
                            activeOffset.coerceIn(aggregateStart, aggregateEnd + 1),
                        )
                        editor.selectionModel.setSelection(aggregateStart, aggregateEnd + 1)
                        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
                        stopBreathing()
                        breathingStopper =
                            com.antlr4plus.editor.EditorAutoScroll.breathingRange(
                                editor,
                                aggregateStart,
                                aggregateEnd + 1,
                            )
                    }
                }
            } finally {
                // Some editor implementations enqueue their caret event instead of firing it
                // inline. Keep the guard through the next EDT turn so that delayed feedback is
                // treated as part of this same programmatic selection write.
                ApplicationManager.getApplication().invokeLater {
                    editorSelectionWriteDepth.decrementAndGet()
                }
            }
        }
    }

    /** Copies selected tokens as Markdown, or every token when the selection is empty. */
    private fun copyTimelineToMarkdown(selectedTokenIndices: Set<Int>) {
        val currentTimeline = timeline ?: return
        val sb = StringBuilder()

        val tokensToExport =
            if (selectedTokenIndices.isEmpty()) {
                currentTimeline.tokens.mapIndexed { index, token -> index to token }
            } else {
                selectedTokenIndices.sorted().mapNotNull { index ->
                    if (index >= 0 && index < currentTimeline.tokens.size) {
                        index to currentTimeline.tokens[index]
                    } else null
                }
            }

        val isPartialExport = selectedTokenIndices.isNotEmpty()

        sb.appendTokenTimelineMarkdown(currentTimeline, tokensToExport, isPartialExport)

        val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(sb.toString()), null)

        val message =
            if (isPartialExport) {
                "${tokensToExport.size} selected tokens copied to clipboard as Markdown"
            } else {
                "Token timeline copied to clipboard as Markdown"
            }
        NotificationsUtils.showToast(project, message)
    }

    private fun applyTokenHighlighting(
        timeline: TokenTimeline,
        file: VirtualFile?,
        highlightRequestId: Long,
    ) {
        file ?: return
        if (project.isDisposed) return

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            if (highlightRequestId != highlightingGeneration.get() || !isHighlightingEnabled) return@invokeLater
            if (this.timeline !== timeline || previewFile?.path != file.path) return@invokeLater

            val fileEditorManager = FileEditorManager.getInstance(project)
            var editors = fileEditorManager.getEditors(file)

            if (editors.isEmpty()) {
                fileEditorManager.openFile(file, false)
                editors = fileEditorManager.getEditors(file)
            }

            for (fileEditor in editors) {
                if (fileEditor is TextEditor) {
                    val editor = fileEditor.editor
                    // Remove and replace highlighters within the same EDT callback.
                    removeHighlightersFromEditor(editor)
                    applyHighlightersToEditor(editor, timeline)
                }
            }
        }
    }

    /** Uses subtle backgrounds for the most common token type and whitespace-like types. */
    private fun applyHighlightersToEditor(editor: Editor, timeline: TokenTimeline) {
        val markupModel = editor.markupModel
        val docLength = editor.document.textLength

        val subtleHighlightTypes = setOf("WS", "NL", "NEWLINE", "WHITESPACE", "EOL", "LINE_BREAK")

        val tokenTypeCounts = mutableMapOf<String, Int>()
        for (token in timeline.tokens) {
            val typeName = timeline.getTokenTypeName(token)
            tokenTypeCounts[typeName] = tokenTypeCounts.getOrDefault(typeName, 0) + 1
        }
        val mostCommonType = tokenTypeCounts.maxByOrNull { it.value }?.key

        val editorBgColor = editor.colorsScheme.defaultBackground
        val subtleHighlightColor =
            if (isLightTheme) {
                java.awt.Color(
                    (editorBgColor.red * 0.98f).toInt().coerceIn(0, 255),
                    (editorBgColor.green * 0.98f).toInt().coerceIn(0, 255),
                    (editorBgColor.blue * 0.98f).toInt().coerceIn(0, 255),
                )
            } else {
                java.awt.Color(
                    (editorBgColor.red + (255 - editorBgColor.red) * 0.02f).toInt().coerceIn(0, 255),
                    (editorBgColor.green + (255 - editorBgColor.green) * 0.02f).toInt().coerceIn(0, 255),
                    (editorBgColor.blue + (255 - editorBgColor.blue) * 0.02f).toInt().coerceIn(0, 255),
                )
            }

        val backgroundAlpha = if (isLightTheme) 0.2f else 0.35f

        for (token in timeline.tokens) {
            val start = token.startIndex
            val end = token.stopIndex + 1

            if (start < 0 || end > docLength || start >= end) continue

            val typeName = timeline.getTokenTypeName(token)

            val useSubtleHighlight =
                typeName == mostCommonType || subtleHighlightTypes.any { typeName.equals(it, ignoreCase = true) }

            val awtColor =
                if (useSubtleHighlight) {
                    subtleHighlightColor
                } else {
                    val tokenColor = getTokenTypeColor(typeName)
                    java.awt.Color(
                        (tokenColor.red * 255).toInt(),
                        (tokenColor.green * 255).toInt(),
                        (tokenColor.blue * 255).toInt(),
                        (backgroundAlpha * 255).toInt(),
                    )
                }

            val attr =
                TextAttributes().apply {
                    backgroundColor = awtColor
                }

            val highlighter =
                markupModel.addRangeHighlighter(
                    start,
                    end,
                    HighlighterLayer.ADDITIONAL_SYNTAX,
                    attr,
                    HighlighterTargetArea.EXACT_RANGE,
                )

            highlighter.putUserData(TOKEN_HIGHLIGHT_KEY, true)
        }
    }

    private fun clearTokenHighlighting() {
        val file = previewFile ?: return

        if (project.isDisposed) return

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            val fileEditorManager = FileEditorManager.getInstance(project)
            val editors = fileEditorManager.getEditors(file)

            for (fileEditor in editors) {
                if (fileEditor is TextEditor) {
                    val editor = fileEditor.editor
                    removeHighlightersFromEditor(editor)
                }
            }
        }
    }

    /** Focuses the input editor, then schedules the optional callback for the next EDT cycle. */
    private fun focusInputFileEditor(
        file: VirtualFile,
        highlightRequestId: Long,
        afterFocus: (() -> Unit)? = null,
    ) {
        if (project.isDisposed) return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            if (highlightRequestId != highlightingGeneration.get() || previewFile?.path != file.path) return@invokeLater
            FileEditorManager.getInstance(project).openFile(file, true)
            if (afterFocus != null) {
                ApplicationManager.getApplication().invokeLater {
                    if (
                        !project.isDisposed &&
                            highlightRequestId == highlightingGeneration.get() &&
                            previewFile?.path == file.path
                    )
                        afterFocus()
                }
            }
        }
    }

    private fun removeHighlightersFromEditor(editor: Editor) {
        val markupModel = editor.markupModel
        val toRemove =
            markupModel.allHighlighters.filter {
                it.getUserData(TOKEN_HIGHLIGHT_KEY) == true
            }
        toRemove.forEach { markupModel.removeHighlighter(it) }
    }
}

/** Centers a selected token just like the hierarchy centers its selected tree row. */
private suspend fun LazyListState.centerItemVertically(index: Int, immediate: Boolean) {
    val currentLayout = layoutInfo
    val targetHeight =
        currentLayout.visibleItemsInfo.firstOrNull { it.index == index }?.size
            ?: currentLayout.visibleItemsInfo.firstOrNull()?.size
            ?: 30
    val centeredOffset =
        CenteredSelectionScrollPolicy.centeredLazyListOffset(
            viewportSize = currentLayout.viewportSize.height,
            itemSize = targetHeight,
        )

    if (immediate) {
        scrollToItem(index, centeredOffset)
    } else {
        animateScrollToItem(index, centeredOffset)
    }
}
