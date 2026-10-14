package com.antlr4plus.statistics

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.Orientation as GestureOrientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.antlr4plus.benchmark.BenchmarkRunner
import com.antlr4plus.color.Color.AMBIGUITY_COLOR
import com.antlr4plus.color.Color.DEEPESTLOOK_COLOR
import com.antlr4plus.color.Color.FULLCTX_COLOR
import com.antlr4plus.color.Color.PREDEVAL_COLOR
import com.antlr4plus.color.darken
import com.antlr4plus.color.rememberIsLightTheme
import com.antlr4plus.color.rememberThemeBackgroundColor
import com.antlr4plus.color.rememberThemeScrollbarColor
import com.antlr4plus.color.rememberThemeTextColor
import com.antlr4plus.compat.compatClickable
import com.antlr4plus.compose.ComposeCompat
import com.antlr4plus.compose.SimpleCheckbox
import com.antlr4plus.compose.SimpleCircularProgressIndicator
import com.antlr4plus.compose.previewScrollbarStyle
import com.antlr4plus.dotG4Support.highlighting.Highlighter
import com.antlr4plus.editor.EditorUtils
import com.antlr4plus.notifications.NotificationsUtils
import com.antlr4plus.parsing.PreviewParser
import com.antlr4plus.parsing.ruleCharacterRange
import com.antlr4plus.previewService.PreviewService
import com.antlr4plus.settings.LiveSettings
import com.antlr4plus.state.PreviewState
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.markup.*
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import java.awt.Cursor
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SortOrder
import kotlin.math.max
import kotlinx.coroutines.*
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.atn.DecisionEventInfo
import org.antlr.v4.runtime.atn.DecisionInfo
import org.antlr.v4.runtime.tree.ParseTree
import org.jetbrains.jewel.ui.Orientation as JewelOrientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text

class ComposeProfilerPanel(
    private val project: Project,
    parentDisposable: Disposable = project,
) : Disposable {

    // The loading overlay handles cancellation messages and retry controls.
    sealed class DisplayState {
        object PluginDisabled : DisplayState()

        object Loading : DisplayState()

        object WaitingForFile : DisplayState()

        object HasData : DisplayState()

        object LexerOnly : DisplayState()

        object Empty : DisplayState()
    }

    private var isPluginEnabled by mutableStateOf(true)

    private var displayState by mutableStateOf<DisplayState>(DisplayState.WaitingForFile)

    init {
        val connection = project.messageBus.connect(parentDisposable)

        connection.subscribe(
            PreviewService.PLUGIN_TOGGLE_TOPIC,
            object : PreviewService.PluginToggleListener {
                override fun onPluginToggled(enabled: Boolean) {
                    isPluginEnabled = enabled
                    displayState = if (enabled) DisplayState.WaitingForFile else DisplayState.PluginDisabled
                }
            },
        )

        connection.subscribe(
            PreviewService.STATUS_UPDATE_TOPIC,
            object : PreviewService.StatusUpdateListener {
                override fun onStatusUpdate(message: String, isLoading: Boolean) {
                    if (isLoading && message.isNotEmpty()) {
                        displayState = DisplayState.Loading
                    }
                }
            },
        )

        connection.subscribe(
            PreviewService.PARSING_COMPLETION_TOPIC,
            object : PreviewService.ParsingCompletionListener {
                override fun onParsingCompleted(previewState: PreviewState) {
                    displayState =
                        when {
                            previewState.isLexerOnlyMode -> DisplayState.LexerOnly
                            previewState.parsingResult == null -> DisplayState.Empty
                            else -> DisplayState.HasData
                        }
                }
            },
        )
    }

    fun resetData() {
        previewState = null
        benchmarkInputText = null
        initialStats = null
        benchmarkResult = null
        benchmarkFailed = false
        benchmarkFailedTime = 0L
        cancelBenchmark()
        clearSelectionAndHighlights()
        if (isPluginEnabled) {
            displayState = DisplayState.WaitingForFile
        }
    }

    data class InitialStats(
        val parseTimeMs: Double,
        val inputChars: Int,
        val inputLines: Int,
        val numTokens: Int,
        val predictionTimeMs: Double,
        val predictionPercent: Double,
        val lookaheadBurden: Double,
        val cacheMissRate: Double,
    )

    private var initialStats by mutableStateOf<InitialStats?>(null)

    private var currentTableModel: ProfilerTableDataModel? = null

    private var benchmarkResult by mutableStateOf<BenchmarkRunner.BenchmarkResult?>(null)
    private var benchmarkFailed by mutableStateOf(false)
    private var benchmarkFailedTime by mutableStateOf(0L)
    private var isBenchmarkRunning by mutableStateOf(false)
    private val benchmarkScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var benchmarkJob: Job? = null
    private val benchmarkGeneration = AtomicLong()
    // Benchmarks must describe the same text as the displayed parser decisions, including after later editor edits.
    private var benchmarkInputText: String? = null

    fun runBenchmark() {
        val state = previewState ?: return
        val inputText = benchmarkInputText ?: return
        if (isBenchmarkRunning || !benchmarkScope.isActive) return
        val benchmarkInput =
            BenchmarkRunner.captureInput(state, inputText)
                ?: run {
                    benchmarkResult = null
                    benchmarkFailed = true
                    benchmarkFailedTime = System.currentTimeMillis()
                    return
                }

        isBenchmarkRunning = true
        benchmarkResult = null
        benchmarkFailed = false
        val runId = benchmarkGeneration.incrementAndGet()
        val parsingResultAtStart = state.parsingResult

        val liveSettings = state.liveSettings
        val config =
            if (liveSettings != null) {
                BenchmarkRunner.BenchmarkConfig(
                    warmupDurationMs = liveSettings.benchmarkWarmupDurationMs,
                    measurementDurationMs = liveSettings.benchmarkMeasurementDurationMs,
                    parsingTimeoutMs = liveSettings.parsingTimeoutMs,
                )
            } else {
                BenchmarkRunner.BenchmarkConfig()
            }

        benchmarkJob = benchmarkScope.launch {
            try {
                val runner = BenchmarkRunner()
                val result =
                    runner.runParsingBenchmark(
                        input = benchmarkInput,
                        project = project,
                        config = config,
                    )
                withContext(Dispatchers.Main) {
                    if (
                        runId != benchmarkGeneration.get() ||
                            previewState !== state ||
                            state.parsingResult !== parsingResultAtStart
                    )
                        return@withContext
                    if (result != null) {
                        benchmarkResult = result
                        benchmarkFailed = false
                        if (result.iterations > 0) {
                            initialStats =
                                InitialStats(
                                    parseTimeMs = result.meanMs,
                                    inputChars = result.inputChars,
                                    inputLines = result.inputLines,
                                    numTokens = result.numTokens,
                                    predictionTimeMs = result.avgPredictionTimeMs,
                                    predictionPercent = result.avgPredictionPercent,
                                    lookaheadBurden = result.avgLookaheadBurden,
                                    cacheMissRate = result.avgCacheMissRate,
                                )
                        }
                    } else {
                        benchmarkResult = null
                        benchmarkFailed = true
                        benchmarkFailedTime = System.currentTimeMillis()
                    }
                }
            } finally {
                // Finish even for cancelled jobs, propagated control flow, and discarded results.
                // A superseded run must never clear a newer run's indicator.
                withContext(NonCancellable + Dispatchers.Main) {
                    if (runId == benchmarkGeneration.get()) {
                        isBenchmarkRunning = false
                    }
                }
            }
        }
    }

    fun cancelBenchmark() {
        benchmarkGeneration.incrementAndGet()
        benchmarkJob?.cancel()
        isBenchmarkRunning = false
    }

    override fun dispose() {
        cancelBenchmark()
        benchmarkScope.cancel()
    }

    companion object {
        private val LOG = Logger.getInstance(ComposeProfilerPanel::class.java)
        val HIGHLIGHT_G4P_RULE_INFO_KEY = com.intellij.openapi.util.Key.create<DecisionInfo>("DECISION_RULE_INFO_KEY")
        val HIGHLIGHT_GRAMMAR_INFO_KEY = com.intellij.openapi.util.Key.create<Int>("DECISION_INFO_KEY")
        val HIGHLIGHT_G4P_INFO_KEY = com.intellij.openapi.util.Key.create<DecisionEventInfo>("DECISION_EVENT_INFO_KEY")
    }

    private var previewState by mutableStateOf<PreviewState?>(null)
    private var selectionResetVersion by mutableStateOf(0)

    fun getComponent(): ComposePanel = ComposeCompat.createComposePanel {
        ProfilerUI()
    }

    private fun createTableModel(isExpertMode: Boolean): ProfilerTableDataModel {
        val parsingResult = previewState?.parsingResult
        val expectedTotal = benchmarkResult?.takeIf { it.iterations > 0 }?.meanMs ?: initialStats?.parseTimeMs

        if (parsingResult?.isKotlin == true) {
            return KotlinProfilerTableDataModel.create(
                parsingResult,
                expectedTotal,
                parsingResult.javaTree,
            ) ?: SimpleProfilerTableDataModel.EmptyModel()
        }

        val initialParser = parsingResult?.parser
        val parser = benchmarkResult?.lastParser ?: initialParser
        val parseInfo = parser?.parseInfo ?: return SimpleProfilerTableDataModel.EmptyModel()
        val manualInvocations = (initialParser as? PreviewParser)?.decisionInvocations
        val parseTree = parsingResult?.tree as? ParseTree
        val ruleExecutions = countDecisionInvocationsFromParseTree(parser, parseTree)

        return if (isExpertMode) {
            ExpertProfilerTableDataModel(
                parseInfo,
                parser,
                manualInvocations,
                expectedTotal,
                ruleExecutions,
            )
        } else {
            SimpleProfilerTableDataModel(
                parseInfo,
                parser,
                manualInvocations,
                expectedTotal,
                ruleExecutions,
            )
        }
    }

    sealed class FilterType {
        object None : FilterType()

        object Ambiguity : FilterType()

        object ContextSensitivity : FilterType()

        object PredicateEvaluation : FilterType()

        object DeepestLookahead : FilterType()
    }

    @Composable
    private fun ProfilerUI() {
        val focusRequester = remember { FocusRequester() }
        val backgroundColor = rememberThemeBackgroundColor()
        val scrollbarColor = rememberThemeScrollbarColor()
        val scrollbarStyle =
            remember(scrollbarColor) {
                previewScrollbarStyle(scrollbarColor, unhoverAlpha = 0.12f, hoverAlpha = 0.25f)
            }
        var isExpertMode by remember { mutableStateOf(false) }
        var selectedRow by remember(previewState, selectionResetVersion) { mutableStateOf(-1) }
        var columnWidthsDp by remember { mutableStateOf<List<Dp>>(emptyList()) }

        // Capture old state and service references before disposal; service lookup can initialize
        // disposed services.
        val currentPreviewState = previewState
        val previewService = project.service<PreviewService>()
        DisposableEffect(currentPreviewState) {
            onDispose {
                // Defer editor cleanup until Compose has finished disposing this composition.
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater

                    currentPreviewState?.g4pInputFile?.let { file ->
                        EditorUtils.getOpenEditor(project, file)?.let { editor ->
                            Highlighter.removeDecisionEventHighlighters(editor)
                        }
                    }
                    currentPreviewState?.grammarFile?.let { file ->
                        previewService.getEditorForFile(file)?.let { editor ->
                            val markupModel = editor.markupModel
                            markupModel.allHighlighters.forEach { highlighter ->
                                if (highlighter.getUserData(HIGHLIGHT_GRAMMAR_INFO_KEY) != null) {
                                    markupModel.removeHighlighter(highlighter)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Prefer the benchmark's final profile sample after warmup.
        val tableModel =
            remember(isExpertMode, previewState, benchmarkResult, initialStats) {
                createTableModel(isExpertMode)
            }
        SideEffect { currentTableModel = tableModel }

        var filterType by remember { mutableStateOf<FilterType>(FilterType.None) }

        LaunchedEffect(isPluginEnabled) {
            if (!isPluginEnabled) {
                filterType = FilterType.None
            }
        }

        LaunchedEffect(previewState) {
            filterType = FilterType.None
        }

        Column(
            modifier =
                Modifier.fillMaxSize()
                    .background(backgroundColor)
                    .focusable()
                    .focusRequester(focusRequester)
                    .onKeyEvent { keyEvent ->
                        if (keyEvent.key.keyCode == Key.Escape.keyCode && keyEvent.type == KeyEventType.KeyDown) {
                            selectedRow = -1
                            focusRequester.requestFocus()
                            true
                        } else {
                            false
                        }
                    },
        ) {
            when (displayState) {
                DisplayState.PluginDisabled -> {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Plugin is off",
                            color = Color.Gray,
                            fontSize = 14.sp,
                        )
                    }
                }

                DisplayState.Loading -> {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth())
                }

                DisplayState.WaitingForFile -> {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Select a .g4p file\nfrom the Project Tree\nto generate a preview.",
                            color = Color.Gray,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 20.sp,
                        )
                    }
                }

                DisplayState.LexerOnly,
                DisplayState.Empty -> {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Nothing to Show",
                            color = Color.Gray,
                            fontSize = 14.sp,
                        )
                    }
                }

                DisplayState.HasData -> {
                    TableSection(
                        tableModel = tableModel,
                        selectedRow = selectedRow,
                        onRowSelected = { row ->
                            previewState?.let { state: PreviewState ->
                                if (selectedRow == row) {
                                    selectedRow = -1
                                    state.g4pInputFile?.let { file ->
                                        // Cleanup must not open an editor.
                                        EditorUtils.getOpenEditor(project, file)?.let { editor ->
                                            Highlighter.removeDecisionEventHighlighters(editor)
                                        }
                                    }
                                    state.grammarFile?.let { file ->
                                        val grammarEditor = previewService.getEditorForFile(file)
                                        grammarEditor?.let { editor ->
                                            val markupModel = editor.markupModel
                                            markupModel.allHighlighters.forEach { highlighter ->
                                                if (
                                                    highlighter.getUserData(
                                                        HIGHLIGHT_GRAMMAR_INFO_KEY,
                                                    ) != null
                                                ) {
                                                    markupModel.removeHighlighter(highlighter)
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    selectedRow = row
                                    if (row != -1) {
                                        highlightG4p(state, row)
                                        highlightGrammar(state, row)
                                    } else Unit
                                }
                            }
                            focusRequester.requestFocus()
                        },
                        backgroundColor = backgroundColor,
                        onColumnWidthsCalculated = { columnWidthsDp = it },
                        filterType = filterType,
                        isExpertMode = isExpertMode,
                        liveSettings = previewState?.liveSettings,
                        isPluginEnabled = isPluginEnabled,
                        scrollbarStyle = scrollbarStyle,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        val dividerCount = if (columnWidthsDp.isNotEmpty()) columnWidthsDp.size - 1 else 0
                        val totalWidth = columnWidthsDp.sumOf { it.value.toDouble() } + dividerCount * 8.0
                        Divider(
                            orientation = JewelOrientation.Horizontal,
                            color = Color(30, 31, 34),
                            thickness = 1.dp,
                            modifier = Modifier.width(totalWidth.dp),
                        )
                    }
                    SouthPanel(
                        isExpertMode = isExpertMode,
                        onExpertModeChanged = { isExpertMode = it },
                        backgroundColor = backgroundColor,
                        filterType = filterType,
                        onFilterToggle = { newFilter -> filterType = newFilter },
                        tableModel = tableModel,
                        isPluginEnabled = isPluginEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun TableSection(
        tableModel: ProfilerTableDataModel,
        selectedRow: Int,
        onRowSelected: (Int) -> Unit,
        backgroundColor: Color,
        onColumnWidthsCalculated: (List<Dp>) -> Unit,
        filterType: FilterType,
        isExpertMode: Boolean,
        liveSettings: LiveSettings?,
        isPluginEnabled: Boolean,
        scrollbarStyle: ScrollbarStyle,
        modifier: Modifier = Modifier,
    ) {
        val isLightTheme = rememberIsLightTheme()
        val textColorCompose = rememberThemeTextColor()
        val defaultTextColor = if (!isLightTheme) textColorCompose.copy(alpha = 0.8f) else textColorCompose

        val columnSizing =
            rememberProfilerColumnWidths(
                tableModel = tableModel,
                isExpertMode = isExpertMode,
                liveSettings = liveSettings,
                onColumnWidthsCalculated = onColumnWidthsCalculated,
            )
        val columnWidthsPx = columnSizing.widthsPx
        val columnWidthsDp = columnSizing.widthsDp

        val horizontalScrollState = rememberScrollState()
        val lazyListState = rememberLazyListState()
        val msColumnIndex =
            remember(tableModel) {
                (0 until tableModel.columnCount).firstOrNull {
                    tableModel.getColumnName(it) == "ms"
                }
            }
        // Keep sorting across benchmark updates; reset when the preview state changes.
        var currentSortColumn by remember(previewState) { mutableStateOf(msColumnIndex) }
        var currentSortOrder by
            remember(previewState) {
                mutableStateOf(if (msColumnIndex != null) SortOrder.ASCENDING else null)
            }

        LaunchedEffect(isPluginEnabled) {
            if (!isPluginEnabled) {
                currentSortColumn = null
                currentSortOrder = null
            }
        }

        val highlightBackgroundColor =
            remember(isLightTheme) {
                val baseColor = Color(0xFFA6D2FF)
                if (!isLightTheme) baseColor.darken(0.6f) else baseColor
            }
        val tooltipBackgroundColor =
            if (isLightTheme) {
                Color(0xFFE8EEF5)
            } else {
                Color(50, 52, 55)
            }
        val tooltipTextColor =
            if (isLightTheme) {
                Color(0xFF1E1E1E)
            } else {
                Color.White
            }
        val tooltipSecondaryColor =
            if (isLightTheme) {
                Color(0xFF505050)
            } else {
                Color(200, 200, 200)
            }

        fun onHeaderClick(column: Int) {
            if (currentSortColumn != column) {
                currentSortColumn = column
                currentSortOrder = SortOrder.DESCENDING
            } else if (currentSortOrder == SortOrder.DESCENDING) {
                currentSortOrder = SortOrder.ASCENDING
            } else if (currentSortOrder == SortOrder.ASCENDING) {
                currentSortColumn = null
                currentSortOrder = null
            }
        }

        val displayRows =
            remember(tableModel, currentSortColumn, currentSortOrder, filterType) {
                val filteredRows =
                    if (filterType == FilterType.None) {
                        (0 until tableModel.rowCount).toList()
                    } else {
                        (0 until tableModel.rowCount).filter { row ->
                            val colorInfo = getDecisionColorInfo(previewState?.parsingResult, row)
                            when (filterType) {
                                FilterType.Ambiguity -> colorInfo?.hasAmbiguities == true
                                FilterType.ContextSensitivity -> colorInfo?.hasContextSensitivities == true
                                FilterType.PredicateEvaluation -> colorInfo?.hasPredicateEvals == true
                                FilterType.DeepestLookahead -> (colorInfo?.maxLookahead ?: 0) > 1
                                else -> true
                            }
                        }
                    }
                if (currentSortColumn == null) {
                    filteredRows
                } else {
                    val column = currentSortColumn!!
                    val order = currentSortOrder!!
                    val comparator =
                        Comparator<Int> { row1, row2 ->
                            val value1 = tableModel.getValueAt(row1, column)
                            val value2 = tableModel.getValueAt(row2, column)
                            val key1 =
                                when (value1) {
                                    is Number -> value1.toDouble()
                                    else -> value1.toString()
                                }
                            val key2 =
                                when (value2) {
                                    is Number -> value2.toDouble()
                                    else -> value2.toString()
                                }
                            @Suppress("UNCHECKED_CAST") (key1 as Comparable<Any>).compareTo(key2 as Any)
                        }
                    if (order != SortOrder.ASCENDING) {
                        filteredRows.sortedWith(comparator)
                    } else {
                        filteredRows.sortedWith(comparator.reversed())
                    }
                }
            }

        LaunchedEffect(currentSortColumn, currentSortOrder) {
            lazyListState.scrollToItem(0)
        }

        val scope = rememberCoroutineScope()

        Column(modifier = modifier.background(backgroundColor)) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxHeight().horizontalScroll(horizontalScrollState),
                ) {
                    Column {
                        val headerAlpha = if (isPluginEnabled) 1f else 0.4f
                        Row(
                            modifier =
                                Modifier.background(backgroundColor)
                                    .height(ProfilerColumnConfig.headerRowHeight)
                                    .alpha(headerAlpha),
                        ) {
                            for (i in 0 until tableModel.columnCount) {
                                val headerText = tableModel.getColumnName(i)
                                val sortIndicatorText =
                                    when {
                                        currentSortColumn == i && currentSortOrder == SortOrder.ASCENDING -> " ▼"
                                        currentSortColumn == i && currentSortOrder == SortOrder.DESCENDING -> " ▲"
                                        else -> ""
                                    }
                                val fullHeaderText = headerText + sortIndicatorText

                                TooltipArea(
                                    tooltip = {
                                        Box(
                                            modifier =
                                                Modifier.widthIn(max = 350.dp)
                                                    .background(
                                                        tooltipBackgroundColor,
                                                        RoundedCornerShape(6.dp),
                                                    )
                                                    .padding(12.dp),
                                        ) {
                                            Column {
                                                Text(
                                                    text = headerText,
                                                    color = tooltipTextColor,
                                                    fontSize = 13.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(bottom = 8.dp),
                                                )
                                                Text(
                                                    text =
                                                        profilerColumnHelpText(
                                                            headerText,
                                                            tableModel.getColumnToolTip(i),
                                                        ),
                                                    color = tooltipSecondaryColor,
                                                    fontSize = 12.sp,
                                                    fontFamily = FontFamily.SansSerif,
                                                    lineHeight = 18.sp,
                                                )
                                            }
                                        }
                                    },
                                    delayMillis = 200,
                                ) {
                                    Box(
                                        modifier =
                                            Modifier.width(columnWidthsDp[i])
                                                .fillMaxHeight()
                                                .background(
                                                    if (currentSortColumn == i) highlightBackgroundColor
                                                    else Color.Transparent,
                                                    RoundedCornerShape(4.dp),
                                                )
                                                .compatClickable { onHeaderClick(i) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = fullHeaderText,
                                            color = defaultTextColor,
                                            fontSize = 14.sp,
                                            textAlign = TextAlign.Start,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            overflow = TextOverflow.Ellipsis,
                                            softWrap = false,
                                            maxLines = 1,
                                            modifier =
                                                Modifier.fillMaxWidth()
                                                    .padding(
                                                        start = ProfilerColumnConfig.cellPadding,
                                                        end = 0.dp,
                                                    ),
                                        )
                                    }
                                }
                                if (i < tableModel.columnCount - 1) {
                                    // Capture divider drags before the surrounding scroll handler.
                                    val columnIndex = i
                                    Box(
                                        modifier =
                                            Modifier.width(8.dp)
                                                .height(ProfilerColumnConfig.headerRowHeight)
                                                .pointerHoverIcon(
                                                    PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)),
                                                )
                                                .draggable(
                                                    orientation = GestureOrientation.Horizontal,
                                                    startDragImmediately = true,
                                                    state =
                                                        rememberDraggableState { delta ->
                                                            val newWidth = columnWidthsPx[columnIndex] + delta
                                                            val minWidth =
                                                                columnSizing.getMinWidthPx(
                                                                    columnIndex,
                                                                )
                                                            columnWidthsPx[columnIndex] =
                                                                newWidth.coerceAtLeast(minWidth)
                                                        },
                                                ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Divider(
                                            orientation = JewelOrientation.Vertical,
                                            color = Color(30, 31, 34),
                                            thickness = 1.dp,
                                            modifier = Modifier.width(1.dp).fillMaxHeight(),
                                        )
                                    }
                                }
                            }
                        }
                        val totalTableWidth =
                            columnWidthsDp.sumOf { it.value.toDouble() } + (tableModel.columnCount - 1) * 8.0
                        Divider(
                            orientation = JewelOrientation.Horizontal,
                            color = Color(30, 31, 34),
                            thickness = 1.dp,
                            modifier = Modifier.width(totalTableWidth.dp),
                        )

                        LazyColumn(state = lazyListState, modifier = Modifier.fillMaxSize()) {
                            items(count = displayRows.size) { index ->
                                val originalRow = displayRows[index]
                                Row(
                                    modifier =
                                        Modifier.background(
                                                if (selectedRow == originalRow) highlightBackgroundColor
                                                else backgroundColor,
                                                RoundedCornerShape(4.dp),
                                            )
                                            .height(ProfilerColumnConfig.cellRowHeight)
                                            .pointerInput(originalRow, selectedRow) {
                                                detectTapGestures(
                                                    onTap = {
                                                        onRowSelected(originalRow)
                                                        scope.launch {
                                                            previewState?.grammarFile?.let { grammarFile ->
                                                                val editor =
                                                                    EditorUtils.getOrFocusEditor(
                                                                        project,
                                                                        grammarFile,
                                                                    )
                                                                if (editor != null) {
                                                                    val grammar = previewState?.grammar ?: return@let
                                                                    // The decision index can be
                                                                    // stale after a grammar change.
                                                                    if (
                                                                        originalRow < 0 ||
                                                                            originalRow >=
                                                                                grammar.atn.decisionToState.size
                                                                    )
                                                                        return@let
                                                                    val decisionState =
                                                                        grammar.atn.getDecisionState(
                                                                            originalRow,
                                                                        )
                                                                    val region =
                                                                        grammar.getStateToGrammarRegion(
                                                                            decisionState.stateNumber,
                                                                        )
                                                                    if (
                                                                        region != null && region.a >= 0 && region.b >= 0
                                                                    ) {
                                                                        val tokens = grammar.tokenStream
                                                                        if (
                                                                            region.a < tokens.size() &&
                                                                                region.b < tokens.size()
                                                                        ) {
                                                                            val startToken =
                                                                                tokens.get(region.a)
                                                                                    as org.antlr.runtime.CommonToken
                                                                            val stopToken =
                                                                                tokens.get(region.b)
                                                                                    as org.antlr.runtime.CommonToken
                                                                            ApplicationManager.getApplication()
                                                                                .invokeLater {
                                                                                    ApplicationManager.getApplication()
                                                                                        .runReadAction {
                                                                                            EditorUtils
                                                                                                .centerRangeInEditor(
                                                                                                    editor,
                                                                                                    startToken
                                                                                                        .startIndex,
                                                                                                    stopToken.stopIndex,
                                                                                                )
                                                                                        }
                                                                                }
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    },
                                                    onLongPress = {
                                                        if (selectedRow != originalRow) {
                                                            onRowSelected(originalRow)
                                                        }
                                                        scope.launch {
                                                            previewState?.let { state ->
                                                                state.g4pInputFile?.let inputFile@{ g4pFile ->
                                                                    val editor =
                                                                        EditorUtils.getOrFocusEditor(
                                                                            project,
                                                                            g4pFile,
                                                                        )
                                                                    if (editor != null && selectedRow == originalRow) {
                                                                        highlightG4p(
                                                                            state,
                                                                            originalRow,
                                                                        )
                                                                        val parseInfo =
                                                                            state.parsingResult?.parser?.parseInfo
                                                                                ?: return@inputFile
                                                                        // The decision index can be
                                                                        // stale after a new parse.
                                                                        if (
                                                                            originalRow < 0 ||
                                                                                originalRow >=
                                                                                    parseInfo.decisionInfo.size
                                                                        )
                                                                            return@inputFile
                                                                        val decisionInfo =
                                                                            parseInfo.decisionInfo[originalRow]
                                                                        var overallStart = Int.MAX_VALUE
                                                                        var overallStop = Int.MIN_VALUE
                                                                        fun updateOverallRange(
                                                                            info: DecisionEventInfo,
                                                                        ) {
                                                                            val (start, stop) =
                                                                                decisionEventEditorRange(
                                                                                    state.parsingResult
                                                                                        ?.parser
                                                                                        ?.inputStream,
                                                                                    info,
                                                                                ) ?: return
                                                                            if (
                                                                                start >= 0 &&
                                                                                    stop >= start &&
                                                                                    stop < editor.document.textLength
                                                                            ) {
                                                                                overallStart =
                                                                                    minOf(
                                                                                        overallStart,
                                                                                        start,
                                                                                    )
                                                                                overallStop =
                                                                                    maxOf(
                                                                                        overallStop,
                                                                                        stop,
                                                                                    )
                                                                            }
                                                                        }

                                                                        val maxLook =
                                                                            max(
                                                                                decisionInfo.LL_MaxLook,
                                                                                decisionInfo.SLL_MaxLook,
                                                                            )
                                                                        if (maxLook > 1) {
                                                                            val maxLookEvent =
                                                                                if (
                                                                                    decisionInfo.LL_MaxLook >
                                                                                        decisionInfo.SLL_MaxLook
                                                                                )
                                                                                    decisionInfo.LL_MaxLookEvent
                                                                                else decisionInfo.SLL_MaxLookEvent
                                                                            updateOverallRange(
                                                                                maxLookEvent,
                                                                            )
                                                                        }
                                                                        decisionInfo.predicateEvals.forEach {
                                                                            updateOverallRange(
                                                                                it,
                                                                            )
                                                                        }
                                                                        decisionInfo.contextSensitivities.forEach {
                                                                            updateOverallRange(
                                                                                it,
                                                                            )
                                                                        }
                                                                        decisionInfo.ambiguities.forEach {
                                                                            updateOverallRange(
                                                                                it,
                                                                            )
                                                                        }
                                                                        if (
                                                                            overallStart <= overallStop &&
                                                                                overallStart >= 0 &&
                                                                                overallStop < editor.document.textLength
                                                                        ) {
                                                                            val midOffset =
                                                                                (overallStart + overallStop) / 2
                                                                            ApplicationManager.getApplication()
                                                                                .invokeLater {
                                                                                    ApplicationManager.getApplication()
                                                                                        .runReadAction {
                                                                                            editor.caretModel
                                                                                                .moveToOffset(
                                                                                                    midOffset,
                                                                                                )
                                                                                            editor.scrollingModel
                                                                                                .scrollToCaret(
                                                                                                    ScrollType.CENTER,
                                                                                                )
                                                                                        }
                                                                                }
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    },
                                                )
                                            },
                                ) {
                                    for (column in 0 until tableModel.columnCount) {
                                        val value = tableModel.getValueAt(originalRow, column)
                                        val colorInfo =
                                            getDecisionColorInfo(
                                                previewState?.parsingResult,
                                                originalRow,
                                            )
                                        val baseColor =
                                            colorInfo?.let {
                                                profilerDecisionColor(it)
                                            } ?: defaultTextColor

                                        val isZeroValue =
                                            when (value) {
                                                is Number -> value.toDouble() == 0.0
                                                else ->
                                                    value.toString() == "0" ||
                                                        value.toString() == "0.0" ||
                                                        value.toString() == "0.0000"
                                            }
                                        val cellColor =
                                            if (isZeroValue) defaultTextColor.copy(alpha = 0.2f) else baseColor

                                        val columnName = tableModel.getColumnName(column)
                                        val numericValue =
                                            (value as? Number)?.toInt() ?: value.toString().toIntOrNull() ?: 0
                                        val cellBackground =
                                            when {
                                                filterType == FilterType.Ambiguity &&
                                                    columnName == "Ambiguities" &&
                                                    numericValue >= 1 ->
                                                    Color(
                                                        AMBIGUITY_COLOR.red,
                                                        AMBIGUITY_COLOR.green,
                                                        AMBIGUITY_COLOR.blue,
                                                        77,
                                                    )
                                                filterType == FilterType.ContextSensitivity &&
                                                    columnName == "Full context" &&
                                                    numericValue >= 1 ->
                                                    Color(
                                                        FULLCTX_COLOR.red,
                                                        FULLCTX_COLOR.green,
                                                        FULLCTX_COLOR.blue,
                                                        77,
                                                    )
                                                filterType == FilterType.PredicateEvaluation &&
                                                    columnName == "Predicates" &&
                                                    numericValue >= 1 ->
                                                    Color(
                                                        PREDEVAL_COLOR.red,
                                                        PREDEVAL_COLOR.green,
                                                        PREDEVAL_COLOR.blue,
                                                        77,
                                                    )
                                                filterType == FilterType.DeepestLookahead &&
                                                    columnName == "Max k" &&
                                                    numericValue > 1 ->
                                                    Color(
                                                        DEEPESTLOOK_COLOR.red,
                                                        DEEPESTLOOK_COLOR.green,
                                                        DEEPESTLOOK_COLOR.blue,
                                                        77,
                                                    )
                                                else -> Color.Transparent
                                            }

                                        Box(
                                            modifier =
                                                Modifier.width(columnWidthsDp[column])
                                                    .fillMaxHeight()
                                                    .background(cellBackground),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = value.toString(),
                                                modifier =
                                                    Modifier.fillMaxWidth()
                                                        .padding(
                                                            start = ProfilerColumnConfig.cellPadding,
                                                            end = ProfilerColumnConfig.cellPadding,
                                                        ),
                                                color = cellColor,
                                                fontSize = 12.sp,
                                                textAlign = TextAlign.Start,
                                                fontWeight =
                                                    if (selectedRow == originalRow) FontWeight.Bold
                                                    else FontWeight.Normal,
                                                fontFamily = FontFamily.Monospace,
                                                overflow = TextOverflow.Clip,
                                                softWrap = false,
                                                maxLines = 1,
                                            )
                                        }
                                        if (column < tableModel.columnCount - 1) {
                                            // Match header divider width to keep columns aligned.
                                            Box(
                                                modifier =
                                                    Modifier.width(8.dp).height(ProfilerColumnConfig.cellRowHeight),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Divider(
                                                    orientation = JewelOrientation.Vertical,
                                                    color = Color(30, 31, 34),
                                                    thickness = 1.dp,
                                                    modifier = Modifier.width(1.dp).fillMaxHeight(),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                CustomScrollbar(
                    adapter = rememberScrollbarAdapter(lazyListState),
                    tableModel = tableModel,
                    displayRows = displayRows,
                    selectedRow = selectedRow,
                    headerHeight = ProfilerColumnConfig.headerRowHeight + 1.dp,
                    backgroundColor = backgroundColor,
                    scrollbarStyle = scrollbarStyle,
                )
            }
            HorizontalScrollbar(
                modifier = Modifier.fillMaxWidth(),
                adapter = rememberScrollbarAdapter(horizontalScrollState),
                style = scrollbarStyle,
            )
        }
    }

    fun clearSelectionAndHighlights() {
        val fileEditorManager = FileEditorManager.getInstance(project)
        previewState?.let { state ->
            state.g4pInputFile?.let { file ->
                fileEditorManager.getEditors(file).forEach { fileEditor ->
                    if (fileEditor is TextEditor) {
                        Highlighter.removeDecisionEventHighlighters(fileEditor.editor)
                    }
                }
            }
            state.grammarFile?.let { file ->
                fileEditorManager.getEditors(file).forEach { fileEditor ->
                    if (fileEditor is TextEditor) {
                        Highlighter.removeHighlighters(
                            fileEditor.editor,
                            HIGHLIGHT_GRAMMAR_INFO_KEY,
                        )
                    }
                }
            }
        }
        ApplicationManager.getApplication().invokeLater {
            selectionResetVersion++
        }
    }

    @Composable
    private fun CustomScrollbar(
        adapter: ScrollbarAdapter,
        tableModel: ProfilerTableDataModel,
        displayRows: List<Int>,
        selectedRow: Int,
        headerHeight: Dp,
        backgroundColor: Color,
        scrollbarStyle: ScrollbarStyle,
    ) {
        val density = LocalDensity.current
        val totalItems = displayRows.size
        val eventPositions =
            remember(tableModel, displayRows) {
                displayRows.mapNotNull { row ->
                    val colorInfo = getDecisionColorInfo(previewState?.parsingResult, row)
                    colorInfo?.let {
                        val color = profilerDecisionColor(it)
                        color?.let { c -> Pair(row, c) }
                    }
                }
            }

        BoxWithConstraints(
            modifier = Modifier.fillMaxHeight().width(10.dp + scrollbarStyle.thickness),
        ) {
            val trackHeightPx = constraints.maxHeight.toFloat()
            val headerHeightPx = with(density) { headerHeight.toPx() }
            val markerTrackHeightPx = (trackHeightPx - headerHeightPx).coerceAtLeast(0f)

            Row {
                Box(
                    modifier = Modifier.width(10.dp).fillMaxHeight().background(backgroundColor).zIndex(1f),
                ) {
                    for ((row, color) in eventPositions) {
                        val index = displayRows.indexOf(row)
                        if (index != -1) {
                            val position = index / totalItems.toFloat()
                            val yOffsetPx = headerHeightPx + position * markerTrackHeightPx
                            val yOffsetDp = with(density) { yOffsetPx.toDp() }
                            Box(
                                modifier = Modifier.width(8.dp).height(2.dp).offset(y = yOffsetDp).background(color),
                            )
                        }
                    }
                    if (selectedRow != -1 && displayRows.contains(selectedRow)) {
                        val index = displayRows.indexOf(selectedRow)
                        if (index != -1) {
                            val position = index / totalItems.toFloat()
                            val yOffsetPx = headerHeightPx + position * markerTrackHeightPx
                            val yOffsetDp = with(density) { yOffsetPx.toDp() }
                            Box(
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .height(2.dp)
                                        .offset(y = yOffsetDp)
                                        .background(Color(86, 168, 245))
                                        .zIndex(2f),
                            )
                        }
                    }
                }

                VerticalScrollbar(
                    adapter = adapter,
                    modifier = Modifier.width(scrollbarStyle.thickness).fillMaxHeight(),
                    style = scrollbarStyle,
                )
            }
        }
    }

    /** Exports the current profiler model as Markdown. */
    fun getContextAsMarkdown(): String? = currentTableModel?.let { tableModel ->
        profilerMarkdown(tableModel, benchmarkResult, initialStats)
    }

    private fun copyProfilerContextToClipboard(tableModel: ProfilerTableDataModel) {
        val markdown = buildString {
            appendLine("## ANTLR4+ Profiler Data")
            appendLine()
            append(profilerMarkdown(tableModel, benchmarkResult, initialStats))
        }

        val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(java.awt.datatransfer.StringSelection(markdown), null)

        NotificationsUtils.showToast(project, "Profiler data copied to clipboard as Markdown")
    }

    @Composable
    private fun SouthPanel(
        isExpertMode: Boolean,
        onExpertModeChanged: (Boolean) -> Unit,
        backgroundColor: Color,
        filterType: FilterType,
        onFilterToggle: (FilterType) -> Unit,
        tableModel: ProfilerTableDataModel,
        isPluginEnabled: Boolean,
        modifier: Modifier = Modifier,
    ) {
        val textColorCompose = rememberThemeTextColor()
        val disabledAlpha = if (isPluginEnabled) 1f else 0.4f

        Box(modifier = modifier.padding(8.dp)) {
            Column(
                modifier = Modifier.align(Alignment.CenterEnd).alpha(disabledAlpha),
                horizontalAlignment = Alignment.End,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 4.dp),
                ) {
                    val copyIconBitmap = remember { iconToBitmap(AllIcons.Actions.Copy) }
                    Box(
                        modifier =
                            Modifier.compatClickable(enabled = isPluginEnabled) {
                                    copyProfilerContextToClipboard(tableModel)
                                }
                                .padding(4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                bitmap = copyIconBitmap,
                                contentDescription = "Copy Context",
                                modifier = Modifier.size(12.dp),
                                alpha = 0.7f,
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Copy Context",
                                color = textColorCompose,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(end = 4.dp),
                                softWrap = false,
                                overflow = TextOverflow.Clip,
                                maxLines = 1,
                            )
                        }
                    }
                }
                Row {
                    Text(
                        text = "Show expert columns",
                        color = textColorCompose,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(4.dp),
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        maxLines = 1,
                    )
                    SimpleCheckbox(
                        checked = isExpertMode,
                        onCheckedChange = { if (isPluginEnabled) onExpertModeChanged(it) },
                        enabled = isPluginEnabled,
                        modifier = Modifier.padding(4.dp).size(12.dp),
                    )
                }
                LegendItem(
                    "Ambiguity",
                    Color(AMBIGUITY_COLOR.red, AMBIGUITY_COLOR.green, AMBIGUITY_COLOR.blue),
                    FilterType.Ambiguity,
                    filterType,
                    onFilterToggle,
                    enabled = isPluginEnabled,
                    tooltip =
                        """
                        |A grammar ambiguity occurs when multiple valid parse trees exist for the same input.
                        |
                        |This means the grammar cannot uniquely determine how to parse certain phrases - ANTLR picks one interpretation, but others are equally valid.
                        |
                        |Common causes:
                        |• Operator precedence conflicts (e.g., a+b*c could group as (a+b)*c or a+(b*c))
                        |• Dangling else problem (if-else association)
                        |• Overlapping alternatives in a rule
                        |
                        |Impact: Semantic correctness issue. The parser may not choose the interpretation you intended.
                        |
                        |Fix: Refactor grammar to remove ambiguity, or use semantic predicates to guide the parser.
                        |
                        |Right-click highlighted regions in the input to visualize alternative parse trees.
                        """
                            .trimMargin(),
                )
                LegendItem(
                    "Context-sensitivity",
                    Color(FULLCTX_COLOR.red, FULLCTX_COLOR.green, FULLCTX_COLOR.blue),
                    FilterType.ContextSensitivity,
                    filterType,
                    onFilterToggle,
                    enabled = isPluginEnabled,
                    tooltip =
                        """
                        |A context-sensitivity occurs when ANTLR's fast SLL prediction fails and must fall back to slower full LL prediction.
                        |
                        |ANTLR uses a two-stage prediction strategy:
                        |1. SLL (Simpler LL) - Fast mode that ignores the parser call stack
                        |2. Full LL - Slower mode that uses the parser call stack (calling context)
                        |
                        |When SLL sees multiple viable alternatives and can't decide, it falls back to Full LL. If Full LL resolves the decision using the calling context (which rule invoked us), a context-sensitivity is recorded.
                        |
                        |Unlike ambiguity, there IS a unique correct parse - it just required extra work to find.
                        |
                        |Impact: Performance cost. Each context-sensitivity triggers expensive Full LL prediction.
                        |
                        |Fix: Consider refactoring to make decisions SLL-resolvable (often by left-factoring or restructuring rules).
                        """
                            .trimMargin(),
                )
                LegendItem(
                    "Predicate evaluation",
                    Color(PREDEVAL_COLOR.red, PREDEVAL_COLOR.green, PREDEVAL_COLOR.blue),
                    FilterType.PredicateEvaluation,
                    filterType,
                    onFilterToggle,
                    enabled = isPluginEnabled,
                    tooltip =
                        """
                        |A predicate evaluation occurs when ANTLR executes a semantic predicate ({...}?) during prediction.
                        |
                        |Semantic predicates are user-defined boolean expressions embedded in the grammar that guide parsing decisions at runtime.
                        |
                        |Example: rule: {isKeyword()}? ID | expr ;
                        |
                        |The parser evaluates {isKeyword()} to decide which alternative to take.
                        |
                        |Common uses:
                        |• Context-sensitive keywords (identifiers that are keywords in certain contexts)
                        |• Version-specific syntax (enabling features based on language version)
                        |• Disambiguating based on symbol table lookups
                        |
                        |Impact: Performance cost proportional to predicate complexity. Each evaluation runs your custom code during prediction.
                        |
                        |Fix: Minimize predicate usage where possible. Ensure predicates are fast and side-effect free.
                        """
                            .trimMargin(),
                )
                LegendItem(
                    "Deepest lookahead",
                    Color(DEEPESTLOOK_COLOR.red, DEEPESTLOOK_COLOR.green, DEEPESTLOOK_COLOR.blue),
                    FilterType.DeepestLookahead,
                    filterType,
                    onFilterToggle,
                    enabled = isPluginEnabled,
                    tooltip =
                        """
                        |Deepest lookahead indicates decisions that required looking ahead more than 1 token to resolve.
                        |
                        |Lookahead is the number of tokens the parser must examine beyond the current position to make a prediction decision.
                        |
                        |• k=1: Decision made by looking at current token only (fastest)
                        |• k>1: Parser had to look further ahead (slower)
                        |• High k values: May indicate grammar design issues
                        |
                        |Important: "Total k" vs "Max k"
                        |• Total k = Sum of lookahead across ALL invocations (high if many calls)
                        |• Max k = Deepest lookahead in any SINGLE prediction
                        |
                        |Rows are colored teal only when Max k > 1, not Total k. A row with Total k = 10 but Max k = 1 means 10 simple k=1 decisions occurred - no deep lookahead.
                        |
                        |Impact: Higher max lookahead = more tokens examined per decision = slower parsing.
                        |
                        |Fix: Consider left-factoring common prefixes, or restructuring rules to reduce lookahead requirements.
                        |
                        |Right-click highlighted regions to visualize what alternatives the parser was considering.
                        """
                            .trimMargin(),
                )
            }
            Box(
                modifier = Modifier.align(Alignment.CenterStart).background(backgroundColor).alpha(disabledAlpha),
            ) {
                val result = benchmarkResult
                val initial = initialStats
                val refreshIconBitmap = remember { iconToBitmap(AllIcons.Actions.Refresh) }
                val settingsIconBitmap = remember { iconToBitmap(AllIcons.General.Settings) }

                // Iteration counts refer to the measurement window, not the full benchmark budget.
                val liveSettings = previewState?.liveSettings
                val measurementMs = liveSettings?.benchmarkMeasurementDurationMs ?: 1000L
                val timeLabel = if (measurementMs >= 1000) "${measurementMs / 1000}s" else "${measurementMs}ms"

                val failedIndicatorDuration = 3000L
                val timeSinceFailed =
                    if (benchmarkFailed && benchmarkFailedTime > 0) {
                        System.currentTimeMillis() - benchmarkFailedTime
                    } else Long.MAX_VALUE
                val showFailedIndicator = benchmarkFailed && timeSinceFailed < failedIndicatorDuration
                val failedIndicatorAlpha =
                    if (showFailedIndicator) {
                        val fadeStart = failedIndicatorDuration - 1000L
                        if (timeSinceFailed < fadeStart) 1f
                        else 1f - ((timeSinceFailed - fadeStart) / 1000f).coerceIn(0f, 1f)
                    } else 0f

                LaunchedEffect(benchmarkFailed, benchmarkFailedTime) {
                    if (benchmarkFailed && benchmarkFailedTime > 0) {
                        delay(failedIndicatorDuration)
                        benchmarkFailed = false
                    }
                }

                fun showBenchmarkSettings() {
                    val settings = previewState?.liveSettings ?: return
                    ApplicationManager.getApplication().invokeLater {
                        BenchmarkSettingsDialog(settings) { runBenchmark() }.show()
                    }
                }

                Column {
                    if (result != null && result.iterations > 0) {
                        StatRow(
                            "Parse time (ms):",
                            String.format("%.3f ±%.3f", result.meanMs, result.stdDevMs),
                            14,
                        )
                        StatRow(
                            "Input size:",
                            String.format(
                                "%d char, %d lines",
                                result.inputChars,
                                result.inputLines,
                            ),
                        )
                        StatRow("Number of tokens:", result.numTokens.toString())
                        StatRow(
                            "Prediction time (ms):",
                            String.format(
                                "%.3f = %.2f%%",
                                result.avgPredictionTimeMs,
                                result.avgPredictionPercent,
                            ),
                        )
                        StatRow(
                            "Lookahead burden:",
                            String.format("%.2f", result.avgLookaheadBurden),
                        )
                        StatRow(
                            "DFA cache miss rate:",
                            String.format("%.2f%%", result.avgCacheMissRate),
                        )
                        // Scale throughput to the configured measurement window for the displayed
                        // count.
                        val measurementSecs = measurementMs / 1000.0
                        val expectedIterations = (result.throughputOpsPerSec * measurementSecs).toInt()
                        val formattedIterations =
                            java.text.DecimalFormat("#,###").format(expectedIterations).replace(',', ' ')
                        BenchmarkIterationsRow(
                            "$formattedIterations / $timeLabel",
                            refreshIconBitmap,
                            settingsIconBitmap,
                            !isBenchmarkRunning && isPluginEnabled,
                            { runBenchmark() },
                            { showBenchmarkSettings() },
                            failedIndicatorAlpha,
                        )
                    } else if (result != null && result.iterations == 0) {
                        if (initial != null) {
                            StatRow(
                                "Parse time (ms):",
                                String.format("%.3f", initial.parseTimeMs),
                                14,
                            )
                            StatRow(
                                "Input size:",
                                String.format(
                                    "%d char, %d lines",
                                    initial.inputChars,
                                    initial.inputLines,
                                ),
                            )
                            StatRow("Number of tokens:", initial.numTokens.toString())
                            StatRow(
                                "Prediction time (ms):",
                                String.format(
                                    "%.3f = %.2f%%",
                                    initial.predictionTimeMs,
                                    initial.predictionPercent,
                                ),
                            )
                            StatRow(
                                "Lookahead burden:",
                                String.format("%.2f", initial.lookaheadBurden),
                            )
                            StatRow(
                                "DFA cache miss rate:",
                                String.format("%.2f%%", initial.cacheMissRate),
                            )
                        } else {
                            StatRow("Parse time (ms):", "-", 14)
                            StatRow(
                                "Input size:",
                                String.format(
                                    "%d char, %d lines",
                                    result.inputChars,
                                    result.inputLines,
                                ),
                            )
                            StatRow("Number of tokens:", "-")
                            StatRow("Prediction time (ms):", "-")
                            StatRow("Lookahead burden:", "-")
                            StatRow("DFA cache miss rate:", "-")
                        }
                        BenchmarkIterationsRow(
                            "0 / $timeLabel",
                            refreshIconBitmap,
                            settingsIconBitmap,
                            !isBenchmarkRunning && isPluginEnabled,
                            { runBenchmark() },
                            { showBenchmarkSettings() },
                            failedIndicatorAlpha,
                        )
                    } else if (initial != null) {
                        StatRow("Parse time (ms):", String.format("%.3f", initial.parseTimeMs), 14)
                        StatRow(
                            "Input size:",
                            String.format(
                                "%d char, %d lines",
                                initial.inputChars,
                                initial.inputLines,
                            ),
                        )
                        StatRow("Number of tokens:", initial.numTokens.toString())
                        StatRow(
                            "Prediction time (ms):",
                            String.format(
                                "%.3f = %.2f%%",
                                initial.predictionTimeMs,
                                initial.predictionPercent,
                            ),
                        )
                        StatRow("Lookahead burden:", String.format("%.2f", initial.lookaheadBurden))
                        StatRow(
                            "DFA cache miss rate:",
                            String.format("%.2f%%", initial.cacheMissRate),
                        )
                        BenchmarkIterationsRow(
                            "- / $timeLabel",
                            refreshIconBitmap,
                            settingsIconBitmap,
                            !isBenchmarkRunning && isPluginEnabled,
                            { runBenchmark() },
                            { showBenchmarkSettings() },
                            failedIndicatorAlpha,
                        )
                    } else {
                        StatRow("Parse time (ms):", "-", 14)
                        StatRow("Input size:", "-")
                        StatRow("Number of tokens:", "-")
                        StatRow("Prediction time (ms):", "-")
                        StatRow("Lookahead burden:", "-")
                        StatRow("DFA cache miss rate:", "-")
                        BenchmarkIterationsRow(
                            "- / $timeLabel",
                            refreshIconBitmap,
                            settingsIconBitmap,
                            !isBenchmarkRunning && isPluginEnabled,
                            { runBenchmark() },
                            { showBenchmarkSettings() },
                            failedIndicatorAlpha,
                        )
                    }
                }

                if (isBenchmarkRunning) {
                    Box(
                        modifier = Modifier.matchParentSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        SimpleCircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
    }

    /** Publishes metrics from the exact text passed to the parser, retaining it for subsequent benchmarks. */
    fun setProfilerData(previewState: PreviewState, parseTimeNs: Long, inputText: String) {
        cancelBenchmark()
        benchmarkInputText = inputText
        benchmarkResult = null
        benchmarkFailed = false
        benchmarkFailedTime = 0L

        val parseTimeMs = parseTimeNs / 1_000_000.0
        val parsingResult = previewState.parsingResult

        val parseInfo = parsingResult?.parser?.parseInfo
        val kotlinStats = parsingResult?.getKotlinProfilingStats()
        val numTokens = parsingResult?.tokenCount ?: 0

        val totalTimeInPrediction = parseInfo?.totalTimeInPrediction ?: kotlinStats?.totalTimeInPrediction ?: 0L
        val predictionTimeMs = totalTimeInPrediction / 1_000_000.0
        val predictionPercent = if (parseTimeMs > 0) (predictionTimeMs / parseTimeMs) * 100 else 0.0

        val totalSLL = parseInfo?.totalSLLLookaheadOps ?: kotlinStats?.totalSLLLookaheadOps ?: 0L
        val totalLL = parseInfo?.totalLLLookaheadOps ?: kotlinStats?.totalLLLookaheadOps ?: 0L
        val totalATN = parseInfo?.totalATNLookaheadOps ?: kotlinStats?.totalATNLookaheadOps ?: 0L
        val totalLookahead = totalSLL + totalLL
        val lookaheadBurden = if (numTokens > 0) totalLookahead.toDouble() / numTokens else 0.0
        val cacheMissRate = if (totalLookahead > 0) totalATN * 100.0 / totalLookahead else 0.0

        val inputChars = inputText.length
        val inputLines = inputText.count { it == '\n' } + 1

        // Publish statistics before previewState so the table sees matching data.
        initialStats =
            InitialStats(
                parseTimeMs = parseTimeMs,
                inputChars = inputChars,
                inputLines = inputLines,
                numTokens = numTokens,
                predictionTimeMs = predictionTimeMs,
                predictionPercent = predictionPercent,
                lookaheadBurden = lookaheadBurden,
                cacheMissRate = cacheMissRate,
            )

        this.previewState = previewState

        runBenchmark()
    }

    /** Publishes metrics from the exact text passed to the lexer without starting a parser benchmark. */
    fun setLexerOnlyData(previewState: PreviewState, lexTimeNs: Long, tokenCount: Int, inputText: String) {
        cancelBenchmark()
        benchmarkInputText = null
        benchmarkResult = null
        benchmarkFailed = false
        benchmarkFailedTime = 0L

        val lexTimeMs = lexTimeNs / 1_000_000.0

        val inputChars = inputText.length
        val inputLines = inputText.count { it == '\n' } + 1

        // Publish statistics before previewState so the table sees matching data.
        initialStats =
            InitialStats(
                parseTimeMs = lexTimeMs,
                inputChars = inputChars,
                inputLines = inputLines,
                numTokens = tokenCount,
                predictionTimeMs = 0.0,
                predictionPercent = 0.0,
                lookaheadBurden = 0.0,
                cacheMissRate = 0.0,
            )

        this.previewState = previewState
    }

    private fun addDecisionEventHighlighter(
        previewState: PreviewState,
        markupModel: MarkupModel,
        info: DecisionEventInfo,
        color: java.awt.Color,
        effectType: EffectType,
    ): Token? {
        val tokens = previewState.parsingResult?.parser?.inputStream ?: throw IllegalStateException("No input stream")
        val (start, stop) = decisionEventEditorRange(tokens, info) ?: return null
        val startToken = tokens.get(info.startIndex)
        val textLength = markupModel.document.textLength

        if (start < 0 || stop < start || stop >= textLength) {
            LOG.debug(
                "Skipping invalid event highlighter range: start=$start, stop=$stop, textLength=$textLength",
            )
            return null
        }

        val dimmedColor =
            if (color == AMBIGUITY_COLOR) {
                java.awt.Color(
                    (color.red * 0.7).toInt(),
                    (color.green * 0.7).toInt(),
                    (color.blue * 0.7).toInt(),
                )
            } else {
                java.awt.Color(color.red, color.green, color.blue, 179)
            }

        val attr =
            TextAttributes().apply {
                backgroundColor = JBColor.LIGHT_GRAY
                this.effectColor = JBColor(dimmedColor, dimmedColor)
                this.effectType = effectType
                errorStripeColor = color
            }

        val highlighter =
            markupModel.addRangeHighlighter(
                start,
                stop + 1,
                HighlighterLayer.ADDITIONAL_SYNTAX,
                attr,
                HighlighterTargetArea.EXACT_RANGE,
            )
        highlighter.putUserData(HIGHLIGHT_G4P_INFO_KEY, info)
        highlighter.setErrorStripeMarkColor(color)

        return startToken
    }

    private fun highlightG4p(previewState: PreviewState, decision: Int) {
        val file = previewState.g4pInputFile ?: return
        val editor = EditorUtils.getOrOpenEditorWithoutFocus(project, file) ?: return
        val markupModel = editor.markupModel
        Highlighter.removeDecisionEventHighlighters(editor)

        val parser = previewState.parsingResult?.parser ?: return
        val parseInfo = parser.parseInfo ?: return

        // The decision index can be stale after a new parse.
        if (decision < 0 || decision >= parseInfo.decisionInfo.size) {
            return
        }
        val decisionInfo = parseInfo.decisionInfo[decision]

        val decisionState = parser.atn.getDecisionState(decision)
        val ruleIndex = decisionState.ruleIndex

        val parseTree = previewState.parsingResult?.tree as? ParserRuleContext ?: return
        val document = editor.document
        val textLength = document.textLength

        val matchedContexts = collectRuleContexts(parseTree, ruleIndex)

        var overallStart = Int.MAX_VALUE
        var overallStop = Int.MIN_VALUE

        val effectColor =
            when {
                decisionInfo.predicateEvals.isNotEmpty() -> PREDEVAL_COLOR
                decisionInfo.contextSensitivities.isNotEmpty() -> FULLCTX_COLOR
                decisionInfo.ambiguities.isNotEmpty() -> AMBIGUITY_COLOR
                else -> JBColor.darkGray
            }

        for (ctx in matchedContexts) {
            val (start, stop) = ruleCharacterRange(ctx) ?: continue

            if (start < 0 || stop < start || stop >= textLength) {
                LOG.debug(
                    "Skipping invalid range for context: start=$start, stop=$stop, textLength=$textLength",
                )
                continue
            }

            val attr =
                TextAttributes().apply {
                    backgroundColor = JBColor.WHITE
                    this.effectColor = JBColor(effectColor, effectColor)
                    this.effectType = EffectType.ROUNDED_BOX
                }

            val highlighter =
                markupModel.addRangeHighlighter(
                    start,
                    stop + 1,
                    HighlighterLayer.SELECTION,
                    attr,
                    HighlighterTargetArea.EXACT_RANGE,
                )
            highlighter.putUserData(HIGHLIGHT_G4P_RULE_INFO_KEY, decisionInfo)

            overallStart = minOf(overallStart, start)
            overallStop = maxOf(overallStop, stop)
        }

        fun updateOverallRange(info: DecisionEventInfo) {
            val (start, stop) =
                decisionEventEditorRange(previewState.parsingResult?.parser?.inputStream, info) ?: return
            if (start >= 0 && stop >= start && stop < textLength) {
                overallStart = minOf(overallStart, start)
                overallStop = maxOf(overallStop, stop)
            } else {
                LOG.debug(
                    "Skipping invalid event range: start=$start, stop=$stop, textLength=$textLength",
                )
            }
        }

        val maxLook = max(decisionInfo.LL_MaxLook, decisionInfo.SLL_MaxLook)
        if (maxLook > 1) {
            val maxLookEvent =
                if (decisionInfo.LL_MaxLook > decisionInfo.SLL_MaxLook) decisionInfo.LL_MaxLookEvent
                else decisionInfo.SLL_MaxLookEvent
            addDecisionEventHighlighter(
                previewState,
                markupModel,
                maxLookEvent,
                DEEPESTLOOK_COLOR,
                EffectType.BOLD_DOTTED_LINE,
            )
            updateOverallRange(maxLookEvent)
        }

        decisionInfo.predicateEvals.forEach {
            addDecisionEventHighlighter(
                previewState,
                markupModel,
                it,
                PREDEVAL_COLOR,
                EffectType.ROUNDED_BOX,
            )
            updateOverallRange(it)
        }
        decisionInfo.contextSensitivities.forEach {
            addDecisionEventHighlighter(
                previewState,
                markupModel,
                it,
                FULLCTX_COLOR,
                EffectType.ROUNDED_BOX,
            )
            updateOverallRange(it)
        }
        decisionInfo.ambiguities.forEach {
            addDecisionEventHighlighter(
                previewState,
                markupModel,
                it,
                AMBIGUITY_COLOR,
                EffectType.ROUNDED_BOX,
            )
            updateOverallRange(it)
        }

        if (overallStart <= overallStop && overallStart >= 0 && overallStop < textLength) {
            val midOffset = (overallStart + overallStop) / 2
            ApplicationManager.getApplication().invokeLater {
                ApplicationManager.getApplication().runReadAction {
                    editor.caretModel.moveToOffset(midOffset)
                    editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
                }
            }
        } else {
            LOG.debug(
                "Invalid overall range: overallStart=$overallStart, overallStop=$overallStop, textLength=$textLength",
            )
        }
    }

    fun collectRuleContexts(tree: ParseTree, ruleIndex: Int): List<ParserRuleContext> =
        collectMatchingRuleContexts(tree, ruleIndex)

    private fun highlightGrammar(previewState: PreviewState, decision: Int) {
        val grammarFile = previewState.grammarFile ?: return
        val grammar = previewState.grammar ?: return
        val editor = EditorUtils.getOrOpenEditorWithoutFocus(project, grammarFile) ?: return

        // The decision index can be stale after a grammar change.
        if (decision < 0 || decision >= grammar.atn.decisionToState.size) return
        val decisionState = grammar.atn.getDecisionState(decision)
        val region = grammar.getStateToGrammarRegion(decisionState.stateNumber) ?: return
        Highlighter.removeHighlighters(editor, HIGHLIGHT_GRAMMAR_INFO_KEY)
        val tokens = grammar.tokenStream
        if (region.a < 0 || region.b < 0 || region.a >= tokens.size() || region.b >= tokens.size()) return
        val startToken = tokens.get(region.a) as org.antlr.runtime.CommonToken
        val stopToken = tokens.get(region.b) as org.antlr.runtime.CommonToken

        val colorInfo = getDecisionColorInfo(previewState.parsingResult, decision)
        val effectColor =
            when {
                colorInfo?.hasPredicateEvals == true -> PREDEVAL_COLOR
                colorInfo?.hasContextSensitivities == true -> FULLCTX_COLOR
                colorInfo?.hasAmbiguities == true -> AMBIGUITY_COLOR
                else -> JBColor.darkGray
            }

        val attr =
            TextAttributes().apply {
                backgroundColor = JBColor.WHITE
                this.effectColor = effectColor
                this.effectType = EffectType.ROUNDED_BOX
            }
        val highlighter =
            editor.markupModel.addRangeHighlighter(
                startToken.startIndex,
                stopToken.stopIndex + 1,
                HighlighterLayer.SELECTION,
                attr,
                HighlighterTargetArea.EXACT_RANGE,
            )
        highlighter.putUserData(HIGHLIGHT_GRAMMAR_INFO_KEY, decision)

        ApplicationManager.getApplication().invokeLater {
            EditorUtils.centerRangeInEditor(editor, startToken.startIndex, stopToken.stopIndex)
        }
    }
}
