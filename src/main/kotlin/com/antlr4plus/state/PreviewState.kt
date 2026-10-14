package com.antlr4plus.state

import com.antlr4plus.parsing.ParsingResult
import com.antlr4plus.settings.GrammarProperties
import com.antlr4plus.settings.LiveSettings
import com.antlr4plus.settings.SettingsPersistenceService
import com.antlr4plus.timeline.TokenTimeline
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.antlr.v4.tool.Grammar
import org.antlr.v4.tool.LexerGrammar

enum class GrammarType(val displayName: String) {
    COMBINED("Combined Grammar"),
    PARSER("Parser Grammar"),
    LEXER("Lexer Grammar"),
    NONE(""),
}

class PreviewState(private val project: Project) {
    var status: ProcessingStatus = ProcessingStatus.Idle

    fun getProject(): Project = project

    var g4pInputFile: VirtualFile? = null
        set(value) {
            // Preserve the LiveSettings instance held by SettingsPanel during same-file refreshes.
            val pathChanged = field?.path != value?.path
            field = value
            if (pathChanged) {
                liveSettings?.dispose()
                liveSettings = value?.let {
                    val persistenceService = project.service<SettingsPersistenceService>()
                    val settings = LiveSettings(persistenceService, it.path)
                    settings
                }
            }
        }

    var contentRoot: String? = null
    var grammarFile: VirtualFile? = null
    var lexerGrammarFile: VirtualFile? = null
    var grammar: Grammar? = null
    var lexerGrammar: LexerGrammar? = null
    var startRuleName: String? = null

    /** Whether Test Rule explicitly selected the start rule, for the tool-window title. */
    var isStartRuleExplicit: Boolean = false

    var parsingResult: ParsingResult? = null
    var tokenTimeline: TokenTimeline? = null

    /** Support-source paths used to decorate the active preview dependencies. */
    var activeExternalSourceFiles: Set<String> = emptySet()

    val isLexerOnlyMode: Boolean
        get() = lexerGrammar != null && grammar == null

    val grammarType: GrammarType
        get() =
            when {
                grammar == null && lexerGrammar == null -> GrammarType.NONE
                grammar == null && lexerGrammar != null -> GrammarType.LEXER
                grammar != null && grammar!!.isCombined -> GrammarType.COMBINED
                else -> GrammarType.PARSER
            }

    var liveSettings: LiveSettings? = null
        private set

    /** Configured output base resolved relative to the active input folder; null when no input is active. */
    val activeOutputDir: String?
        get() {
            val g4pFile = g4pInputFile ?: return null
            val g4pDir = g4pFile.parent?.path ?: return null
            val configuredOutputDir = liveSettings?.outputDir
            val effectiveOutputDir = configuredOutputDir ?: "gen"
            return if (java.io.File(effectiveOutputDir).isAbsolute) {
                effectiveOutputDir
            } else {
                "$g4pDir/$effectiveOutputDir"
            }
        }

    /** Replaceable preview source directory for the active runtime, or null if unavailable. */
    val activePreviewSourceDir: String?
        get() {
            val g4pFile = g4pInputFile ?: return null
            val contentRoot = g4pFile.parent ?: return null
            if (grammarFile == null && lexerGrammarFile == null) return null
            val runtime = liveSettings?.previewRuntime ?: "Java"
            return GrammarProperties.resolvePreviewOutputDir(this, project, contentRoot, runtime)
        }

    /** Output base plus the optional target-language subdirectory. */
    val activeUserGeneratedSourceDir: String?
        get() {
            val baseDir = activeOutputDir ?: return null
            val createLangSubdir = liveSettings?.createLanguageSubdir ?: true
            val language = liveSettings?.language ?: "Java"
            return if (createLangSubdir) {
                "$baseDir/$language"
            } else {
                baseDir
            }
        }

    fun reset() {
        status = ProcessingStatus.Idle
        startRuleName = null
        isStartRuleExplicit = false
        parsingResult = null
        tokenTimeline = null
        grammar = null
        lexerGrammar = null
        grammarFile = null
        lexerGrammarFile = null
        activeExternalSourceFiles = emptySet()
        // Input and live settings persist across parses.
    }

    fun dispose() {
        liveSettings?.dispose()
    }
}
