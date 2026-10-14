package com.antlr4plus.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.antlr4plus.parsing.CaseChangingStrategy
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

@OptIn(FlowPreview::class)
class LiveSettings(
    private val service: SettingsPersistenceService,
    private val filePath: String,
) {
    var outputDir: String? by mutableStateOf("gen")
    var libDir: String? by mutableStateOf("")
    var encoding: String? by mutableStateOf("UTF-8")
    var packageName: String? by mutableStateOf("")
    var language: String? by mutableStateOf("Java")
    var createLanguageSubdir by mutableStateOf(true) // Include the target-language subdirectory in generated output.
    var previewRuntime: String? by mutableStateOf("Java") // Java or Kotlin preview runtime.
    var caseChangingStrategy by mutableStateOf(CaseChangingStrategy.LEAVE_AS_IS)
    var generateListener by mutableStateOf(true)
    var generateVisitor by mutableStateOf(true)
    var simpleProfilerColumnWidths: String? by mutableStateOf(null) // Comma-separated column widths.
    var expertProfilerColumnWidths: String? by mutableStateOf(null) // Comma-separated column widths.

    var benchmarkWarmupDurationMs by mutableStateOf(500L)
    var benchmarkMeasurementDurationMs by mutableStateOf(1000L)
    var parsingTimeoutMs by mutableStateOf(2000L) // Benchmark deadline checked between parse iterations.

    var treeConnectionMode: String? by mutableStateOf(null) // SPLINE, ORTHOGONAL or DIRECT.
    var treeEdgeColor: String? by mutableStateOf(null) // Hex color string.
    var treeNodeColor: String? by mutableStateOf(null) // Hex color string.
    var treeTextColor: String? by mutableStateOf(null) // Hex color string.
    var treeBackgroundColor: String? by mutableStateOf(null) // Hex color string.

    var formattingPreset: String? by mutableStateOf("compact") // compact, expanded, hanging or custom.
    var alignColons: String? by mutableStateOf("none") // none, trailing or hanging.
    var alignSemicolons: String? by mutableStateOf("none") // none, ownLine or hanging.
    var alignTrailingComments by mutableStateOf(false)
    var alignLexerCommands by mutableStateOf(false)
    var formattingIndentWidth by mutableStateOf(4)
    var formattingUseTab by mutableStateOf(false)
    var formattingColumnLimit by mutableStateOf(100)
    var allowShortRulesOnASingleLine by mutableStateOf(true)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val disposed = AtomicBoolean(false)

    init {
        loadFromPersistence()
        snapshotFlow(::persistenceSnapshot)
            .debounce(200L.milliseconds)
            .onEach { snapshot ->
                ApplicationManager.getApplication().invokeLater {
                    if (disposed.get() || service.project.isDisposed) return@invokeLater
                    saveToPersistence(snapshot)
                    service.project.service<com.antlr4plus.previewService.PreviewService>().updateTreeViewerSettings()
                }
            }
            .launchIn(scope)
    }

    private fun loadFromPersistence() {
        val s = service.getSettings(filePath)
        outputDir = s.outputDir
        libDir = s.libDir
        encoding = s.encoding
        packageName = s.packageName
        language = s.language
        createLanguageSubdir = s.createLanguageSubdir
        previewRuntime = s.previewRuntime ?: "Java"
        caseChangingStrategy = s.caseChangingStrategy
        generateListener = s.generateListener
        generateVisitor = s.generateVisitor
        simpleProfilerColumnWidths = s.simpleProfilerColumnWidths
        expertProfilerColumnWidths = s.expertProfilerColumnWidths
        benchmarkWarmupDurationMs = s.benchmarkWarmupDurationMs
        benchmarkMeasurementDurationMs = s.benchmarkMeasurementDurationMs
        parsingTimeoutMs = s.parsingTimeoutMs
        treeConnectionMode = s.treeConnectionMode
        treeEdgeColor = s.treeEdgeColor
        treeNodeColor = s.treeNodeColor
        treeTextColor = s.treeTextColor
        treeBackgroundColor = s.treeBackgroundColor
        formattingPreset = s.formattingPreset ?: "compact"
        alignColons = s.alignColons ?: "none"
        alignSemicolons = s.alignSemicolons ?: "none"
        alignTrailingComments = s.alignTrailingComments
        alignLexerCommands = s.alignLexerCommands
        formattingIndentWidth = s.formattingIndentWidth
        formattingUseTab = s.formattingUseTab
        formattingColumnLimit = s.formattingColumnLimit
        allowShortRulesOnASingleLine = s.allowShortRulesOnASingleLine
    }

    internal fun persistenceSnapshot() =
        LiveSettingsSnapshot(
            generation =
                GenerationSettingsSnapshot(
                    outputDir = outputDir,
                    libDir = libDir,
                    encoding = encoding,
                    packageName = packageName,
                    language = language,
                    createLanguageSubdir = createLanguageSubdir,
                    previewRuntime = previewRuntime,
                    caseChangingStrategy = caseChangingStrategy,
                    generateListener = generateListener,
                    generateVisitor = generateVisitor,
                ),
            profiler =
                ProfilerSettingsSnapshot(
                    simpleProfilerColumnWidths = simpleProfilerColumnWidths,
                    expertProfilerColumnWidths = expertProfilerColumnWidths,
                    benchmarkWarmupDurationMs = benchmarkWarmupDurationMs,
                    benchmarkMeasurementDurationMs = benchmarkMeasurementDurationMs,
                    parsingTimeoutMs = parsingTimeoutMs,
                ),
            tree =
                TreeSettingsSnapshot(
                    treeConnectionMode = treeConnectionMode,
                    treeEdgeColor = treeEdgeColor,
                    treeNodeColor = treeNodeColor,
                    treeTextColor = treeTextColor,
                    treeBackgroundColor = treeBackgroundColor,
                ),
            formatting =
                FormattingSettingsSnapshot(
                    formattingPreset = formattingPreset,
                    alignColons = alignColons,
                    alignSemicolons = alignSemicolons,
                    alignTrailingComments = alignTrailingComments,
                    alignLexerCommands = alignLexerCommands,
                    formattingIndentWidth = formattingIndentWidth,
                    formattingUseTab = formattingUseTab,
                    formattingColumnLimit = formattingColumnLimit,
                    allowShortRulesOnASingleLine = allowShortRulesOnASingleLine,
                ),
        )

    fun saveToPersistence() = saveToPersistence(persistenceSnapshot())

    private fun saveToPersistence(snapshot: LiveSettingsSnapshot) {
        snapshot.applyTo(service.getSettings(filePath))
        service.markModified() // Notify persistence after mutating the nested per-file state.
    }

    fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        scope.cancel()
        val snapshot = persistenceSnapshot()
        val save = Runnable {
            if (service.project.isDisposed) return@Runnable
            saveToPersistence(snapshot)
        }
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) save.run() else application.invokeLater(save)
    }
}
