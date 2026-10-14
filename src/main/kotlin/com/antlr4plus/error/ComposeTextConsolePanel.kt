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
import com.antlr4plus.previewService.PreviewService
import com.antlr4plus.state.PreviewState
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Cursor
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import org.jetbrains.jewel.ui.component.Text

internal class ComposeTextConsolePanel(
    private val project: Project,
    parentDisposable: Disposable,
    private val emptyConsoleMessage: String,
    private val markdownHeading: String,
    private val copyConfirmation: String,
    private val isSuccessLine: (String) -> Boolean,
) {

    companion object {
        private const val MAX_CONSOLE_LINES = 10_000
        private const val TRIM_CHUNK_LINES = 1_000
    }

    private val messages = mutableStateListOf<String>()
    private val messagesLock = Any()

    init {
        val connection = project.messageBus.connect(parentDisposable)

        connection.subscribe(
            PreviewService.FILE_SWITCHED_TOPIC,
            object : PreviewService.FileSwitchedListener {
                override fun onFileSwitched(previewState: PreviewState) {
                    clearMessages()
                }
            },
        )

        connection.subscribe(
            PreviewService.PLUGIN_TOGGLE_TOPIC,
            object : PreviewService.PluginToggleListener {
                override fun onPluginToggled(enabled: Boolean) {
                    if (!enabled) {
                        clearMessages()
                    }
                }
            },
        )
    }

    fun getLineCount(): Int = messages.count { !isSuccessLine(it) }

    fun appendLine(line: String) {
        appendMessages(listOf(line))
    }

    fun appendMultiLine(message: String) {
        appendMessages(message.lines().filter { it.isNotBlank() })
    }

    fun clear() {
        clearMessages()
    }

    private fun appendMessages(lines: List<String>) {
        if (lines.isEmpty()) return
        synchronized(messagesLock) {
            Snapshot.withMutableSnapshot {
                val retained = lines.takeLast(MAX_CONSOLE_LINES)
                if (retained.size == MAX_CONSOLE_LINES) {
                    messages.clear()
                } else if (messages.size + retained.size > MAX_CONSOLE_LINES) {
                    val overflow = messages.size + retained.size - MAX_CONSOLE_LINES
                    val removeCount = maxOf(overflow, TRIM_CHUNK_LINES).coerceAtMost(messages.size)
                    messages.subList(0, removeCount).clear()
                }
                messages.addAll(retained)
            }
        }
    }

    private fun clearMessages() {
        synchronized(messagesLock) {
            Snapshot.withMutableSnapshot { messages.clear() }
        }
    }

    /** Snapshot of all retained console lines for export. */
    fun getErrorsForExport(): List<String>? =
        synchronized(messagesLock) { messages.toList().takeIf { it.isNotEmpty() } }

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

        val currentMessages = messages
        val scrollbarStyle = previewScrollbarStyle(scrollbarColor, unhoverAlpha = 0.4f, hoverAlpha = 0.7f)

        Box(
            modifier =
                Modifier.fillMaxSize()
                    .background(backgroundColor)
                    .focusable()
                    .consoleSelectionKeys(
                        selectAll = { selectedIndices = currentMessages.indices.toSet() },
                        clearSelection = {
                            selectedIndices = emptySet()
                            lastClickedIndex = -1
                        },
                    ),
        ) {
            if (currentMessages.isEmpty()) {
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
                        items(count = currentMessages.size) { index ->
                            val message = currentMessages[index]
                            val isSelected = selectedIndices.contains(index)
                            val isError = message.contains("error", ignoreCase = true)

                            MessageRow(
                                message = message,
                                index = index,
                                isSelected = isSelected,
                                isError = isError,
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
                                onDoubleClick = { navigateToErrorLocation(message) },
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
                onClick = { copyToMarkdown(selectedIndices, currentMessages) },
                selectedCount = selectedIndices.size,
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            )
        }
    }

    @Composable
    private fun MessageRow(
        message: String,
        index: Int,
        isSelected: Boolean,
        isError: Boolean,
        textColor: Color,
        highlightBackgroundColor: Color,
        onClick: (Int, Boolean, Boolean) -> Unit,
        onDoubleClick: () -> Unit,
    ) {
        val messageColor = if (isError) Color(0xFFE57373) else textColor

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
                        onDoubleClick = onDoubleClick,
                        onClick = onClick,
                    )
                    .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                    .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                color = messageColor,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
    }

    private fun navigateToErrorLocation(line: String) {
        // Diagnostic format: filename.(java|kt):line[:column]: message.
        val regex = Regex("""^([^:]+\.(?:java|kt)):(\d+)(?::(\d+))?:""")
        val match = regex.find(line) ?: return

        val fileName = match.groupValues[1]
        val lineNumber = match.groupValues[2].toIntOrNull() ?: return
        val column = match.groupValues.getOrNull(3)?.toIntOrNull() ?: 0

        navigateToResolvedFile(project, lineNumber - 1, column) {
            findSourceFile(fileName)
        }
    }

    private fun findSourceFile(fileName: String): VirtualFile? {
        if (fileName.contains("/") || fileName.contains("\\")) {
            val file = LocalFileSystem.getInstance().findFileByPath(fileName)
            if (file != null) return file
        }

        val basePath = project.basePath ?: return null
        val isKotlin = fileName.endsWith(".kt")

        val searchPaths =
            mutableListOf(
                "$basePath/$fileName",
            )

        if (isKotlin) {
            searchPaths.addAll(
                listOf(
                    "$basePath/build/generated-src/antlr/main/$fileName",
                    "$basePath/target/generated-sources/antlr4/$fileName",
                    "$basePath/src/main/kotlin/$fileName",
                ),
            )
        } else {
            searchPaths.addAll(
                listOf(
                    "$basePath/build/generated-src/antlr/main/$fileName",
                    "$basePath/target/generated-sources/antlr4/$fileName",
                    "$basePath/src/main/java/$fileName",
                ),
            )
        }

        for (path in searchPaths) {
            val file = LocalFileSystem.getInstance().findFileByPath(path)
            if (file != null) return file
        }

        return null
    }

    private fun copyToMarkdown(selectedIndices: Set<Int>, messages: List<String>) {
        val items =
            if (selectedIndices.isNotEmpty()) {
                selectedIndices.sorted().mapNotNull { messages.getOrNull(it) }
            } else {
                messages
            }

        if (items.isEmpty()) return

        val sb = StringBuilder()
        sb.appendLine(markdownHeading)
        sb.appendLine()

        if (selectedIndices.isNotEmpty()) {
            sb.appendLine("**Selected:** ${items.size} of ${messages.size}")
        } else {
            sb.appendLine("**Total messages:** ${items.size}")
        }
        sb.appendLine()

        sb.appendLine("| File | Line:Col | Message |")
        sb.appendLine("|------|----------|---------|")

        val regex = Regex("""^([^:]+\.(?:java|kt)):(\d+)(?::(\d+))?:\s*(.*)$""")
        items.forEach { line ->
            val match = regex.find(line)
            if (match != null) {
                val file = match.groupValues[1]
                val lineNum = match.groupValues[2]
                val col = match.groupValues.getOrNull(3)?.takeIf { it.isNotEmpty() } ?: "0"
                val location = "$lineNum:$col"
                val message = match.groupValues[4].replace("|", "\\|")
                sb.appendLine("| $file | $location | $message |")
            } else {
                sb.appendLine("| - | - | ${line.replace("|", "\\|")} |")
            }
        }

        val stringSelection = StringSelection(sb.toString())
        Toolkit.getDefaultToolkit().systemClipboard.setContents(stringSelection, null)

        NotificationsUtils.showToast(project, copyConfirmation)
    }
}
