package com.antlr4plus.error

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antlr4plus.color.getThemeBackgroundColor
import com.antlr4plus.color.getThemeScrollbarColor
import com.antlr4plus.color.rememberThemeTextColor
import com.antlr4plus.compose.ComposeCompat
import com.antlr4plus.compose.CopyMarkdownButton
import com.antlr4plus.compose.previewScrollbarStyle
import com.antlr4plus.notifications.NotificationsUtils
import com.antlr4plus.parsing.InputTextCoordinates
import com.antlr4plus.previewService.PreviewService
import com.antlr4plus.state.PreviewState
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import java.awt.Cursor
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import org.antlr.intellij.adaptor.parser.SyntaxError
import org.jetbrains.jewel.ui.component.Text

class ComposeParsingErrorConsolePanel(
    private val project: Project,
    parentDisposable: Disposable = project,
) {

    private val emptyConsoleMessage = "Parsing console. Errors related to parsing the .g4p file will appear here."

    private var errors by mutableStateOf<List<SyntaxError>>(emptyList())
    private var previewState by mutableStateOf<PreviewState?>(null)

    init {
        val connection = project.messageBus.connect(parentDisposable)

        connection.subscribe(
            PreviewService.PARSING_COMPLETION_TOPIC,
            object : PreviewService.ParsingCompletionListener {
                override fun onParsingCompleted(previewState: PreviewState) {
                    Snapshot.withMutableSnapshot {
                        this@ComposeParsingErrorConsolePanel.previewState = previewState
                        errors =
                            previewState.parsingResult?.syntaxErrorListener?.syntaxErrors?.sortedBy {
                                it.line
                            } ?: emptyList()
                    }
                }
            },
        )

        connection.subscribe(
            PreviewService.FILE_SWITCHED_TOPIC,
            object : PreviewService.FileSwitchedListener {
                override fun onFileSwitched(previewState: PreviewState) {
                    Snapshot.withMutableSnapshot {
                        this@ComposeParsingErrorConsolePanel.previewState = previewState
                        errors = emptyList()
                    }
                }
            },
        )

        connection.subscribe(
            PreviewService.PLUGIN_TOGGLE_TOPIC,
            object : PreviewService.PluginToggleListener {
                override fun onPluginToggled(enabled: Boolean) {
                    if (!enabled) {
                        Snapshot.withMutableSnapshot {
                            previewState = null
                            errors = emptyList()
                        }
                    }
                }
            },
        )
    }

    fun getLineCount(): Int = errors.size

    fun getComponent(): ComposePanel = ComposeCompat.createComposePanel {
        ConsoleUI()
    }

    @Composable
    private fun ConsoleUI() {
        val backgroundColor = getThemeBackgroundColor()
        val textColor = rememberThemeTextColor()
        val scrollbarColor = getThemeScrollbarColor()
        val highlightBackgroundColor =
            remember(backgroundColor) {
                Color(0x40, 0x60, 0x80, 0x60)
            }

        val lazyListState = rememberLazyListState()
        var selectedIndices by remember { mutableStateOf(setOf<Int>()) }
        var lastClickedIndex by remember { mutableStateOf(-1) }

        val currentErrors = errors
        val scrollbarStyle = previewScrollbarStyle(scrollbarColor, unhoverAlpha = 0.4f, hoverAlpha = 0.7f)

        Box(
            modifier =
                Modifier.fillMaxSize()
                    .background(backgroundColor)
                    .focusable()
                    .consoleSelectionKeys(
                        selectAll = { selectedIndices = currentErrors.indices.toSet() },
                        clearSelection = {
                            selectedIndices = emptySet()
                            lastClickedIndex = -1
                        },
                    ),
        ) {
            if (currentErrors.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = emptyConsoleMessage,
                        color = textColor.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                    )
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier.fillMaxSize().padding(end = 12.dp),
                    ) {
                        items(count = currentErrors.size) { index ->
                            val error = currentErrors[index]
                            val isSelected = selectedIndices.contains(index)

                            ErrorRow(
                                error = error,
                                index = index,
                                isSelected = isSelected,
                                textColor = textColor,
                                highlightBackgroundColor = highlightBackgroundColor,
                                onClick = { idx, isShift, isCtrl ->
                                    val newSelection =
                                        when {
                                            isShift && lastClickedIndex >= 0 -> {
                                                val range =
                                                    if (idx > lastClickedIndex) {
                                                        lastClickedIndex..idx
                                                    } else {
                                                        idx..lastClickedIndex
                                                    }
                                                selectedIndices + range.toSet()
                                            }
                                            isCtrl -> {
                                                lastClickedIndex = idx
                                                if (idx in selectedIndices) {
                                                    selectedIndices - idx
                                                } else {
                                                    selectedIndices + idx
                                                }
                                            }
                                            else -> {
                                                lastClickedIndex = idx
                                                setOf(idx)
                                            }
                                        }
                                    selectedIndices = newSelection
                                },
                                onDoubleClick = { navigateToErrorLocation(error) },
                            )
                        }
                    }

                    VerticalScrollbar(
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        adapter = rememberScrollbarAdapter(lazyListState),
                        style = scrollbarStyle,
                    )
                }
            }

            CopyMarkdownButton(
                onClick = { copyToMarkdown(selectedIndices, currentErrors) },
                selectedCount = selectedIndices.size,
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            )
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun ErrorRow(
        error: SyntaxError,
        index: Int,
        isSelected: Boolean,
        textColor: Color,
        highlightBackgroundColor: Color,
        onClick: (Int, Boolean, Boolean) -> Unit,
        onDoubleClick: () -> Unit,
    ) {
        val displayText = "${error.line}:${error.charPositionInLine} ${error.message}"

        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .height(24.dp)
                    .background(
                        if (isSelected) highlightBackgroundColor else Color.Transparent,
                        RoundedCornerShape(4.dp),
                    )
                    .consoleRowClicks(
                        index = index,
                        onDoubleClick = { onDoubleClick() },
                        onClick = onClick,
                    )
                    .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                    .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = displayText,
                color = textColor,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
    }

    private fun navigateToErrorLocation(error: SyntaxError) {
        val g4pFile = previewState?.g4pInputFile ?: return
        val document = FileDocumentManager.getInstance().getDocument(g4pFile) ?: return
        val range = syntaxErrorEditorRange(error, InputTextCoordinates(document.text)) ?: return
        val descriptor = OpenFileDescriptor(project, g4pFile, range.startOffset)
        FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
    }

    private fun copyToMarkdown(selectedIndices: Set<Int>, errors: List<SyntaxError>) {
        val items =
            if (selectedIndices.isNotEmpty()) {
                selectedIndices.sorted().mapNotNull { errors.getOrNull(it) }
            } else {
                errors
            }

        if (items.isEmpty()) return

        val sb = StringBuilder()
        sb.appendLine("## Parsing Errors")
        sb.appendLine()

        if (selectedIndices.isNotEmpty()) {
            sb.appendLine("**Selected:** ${items.size} of ${errors.size}")
        } else {
            sb.appendLine("**Total errors:** ${items.size}")
        }
        sb.appendLine()

        sb.appendLine("| Line:Col | Message |")
        sb.appendLine("|----------|---------|")

        items.forEach { error ->
            val location = "${error.line}:${error.charPositionInLine}"
            val message = error.message?.replace("|", "\\|") ?: ""
            sb.appendLine("| $location | $message |")
        }

        val stringSelection = StringSelection(sb.toString())
        Toolkit.getDefaultToolkit().systemClipboard.setContents(stringSelection, null)

        NotificationsUtils.showToast(project, "Parsing errors copied to clipboard as Markdown")
    }
}
