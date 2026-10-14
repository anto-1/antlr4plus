package com.antlr4plus.parsing

import com.antlr4plus.compat.compatIsTrusted
import com.antlr4plus.dotG4Support.errorManager.PluginIgnoreMissingTokensFileErrorManager
import com.antlr4plus.previewService.PreviewService
import com.antlr4plus.state.PreviewState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import org.antlr.intellij.adaptor.parser.SyntaxErrorListener
import org.antlr.intellij.plugin.parser.ANTLRv4Lexer
import org.antlr.intellij.plugin.parser.ANTLRv4Parser
import org.antlr.runtime.ANTLRStringStream
import org.antlr.runtime.RecognitionException
import org.antlr.v4.Tool
import org.antlr.v4.parse.ANTLRParser
import org.antlr.v4.runtime.*
import org.antlr.v4.runtime.tree.ParseTree
import org.antlr.v4.tool.ErrorType
import org.antlr.v4.tool.Grammar
import org.antlr.v4.tool.LexerGrammar
import org.antlr.v4.tool.ast.GrammarRootAST

object ParsingUtils {
    private val LOG = Logger.getInstance(ParsingUtils::class.java)
    var BAD_PARSER_GRAMMAR: Grammar? = null
    var BAD_LEXER_GRAMMAR: LexerGrammar? = null

    init {
        try {
            BAD_PARSER_GRAMMAR = Grammar("grammar BAD; a : 'bad' ;").apply { name = "BAD_PARSER_GRAMMAR" }
            BAD_LEXER_GRAMMAR =
                LexerGrammar("lexer grammar BADLEXER; A : 'bad' ;").apply {
                    name = "BAD_LEXER_GRAMMAR"
                }
        } catch (e: RecognitionException) {
            // TODO: Log failure to initialize the fallback grammars.
        }
    }

    fun nextRealToken(tokens: CommonTokenStream, i: Int): Token? {
        var index = i
        val n = tokens.size()
        index++
        if (index !in 0 until n) return null
        var t = tokens.get(index)
        while (t.channel != Token.DEFAULT_CHANNEL) {
            if (t.type == Token.EOF) {
                val tokenSource = tokens.getTokenSource() ?: return CommonToken(Token.EOF, "EOF")
                val tokenFactory = tokenSource.tokenFactory ?: return CommonToken(Token.EOF, "EOF")
                return tokenFactory.create(Token.EOF, "EOF")
            }
            index++
            if (index >= n) return null

            t = tokens.get(index)
        }
        return t
    }

    fun previousRealToken(tokens: CommonTokenStream, i: Int): Token? {
        var index = i
        val size = tokens.size()
        index--
        if (index !in 0 until size) return null
        var t = tokens.get(index)
        while (t.channel != Token.DEFAULT_CHANNEL) {
            index--
            if (index < 0) return null
            t = tokens.get(index)
        }
        return t
    }

    @JvmStatic
    fun getTokenUnderCursor(previewState: PreviewState?, offset: Int): Token? =
        previewState?.parsingResult?.let { result ->
            val parser = result.parser as PreviewParser
            val tokenStream = parser.inputStream as CommonTokenStream
            getTokenUnderCursor(tokenStream, offset)
        }

    fun getTokenUnderCursor(tokens: CommonTokenStream, offset: Int): Token? {
        val cmp = Comparator { a: Token, b: Token ->
            if (a.stopIndex < b.startIndex) return@Comparator -1
            if (a.startIndex > b.stopIndex) return@Comparator 1
            0
        }
        val input = tokens.tokenSource.inputStream
        val nativeOffset = coordinatesForInput(input)?.toNative(offset) ?: return null
        if (nativeOffset >= input.size()) return null
        val key = CommonToken(Token.INVALID_TYPE, "")
        key.startIndex = nativeOffset
        key.stopIndex = nativeOffset
        val tokenList = tokens.getTokens()
        val i = tokenList.binarySearch(key, cmp)
        return if (i >= 0) tokenList[i] else null
    }

    fun tokenizeANTLRGrammar(text: String): CommonTokenStream {
        val input = CharStreams.fromString(text)
        val lexer = ANTLRv4Lexer(input)
        lexer.removeErrorListeners()
        val tokens = CommonTokenStream(lexer)
        tokens.fill()
        return tokens
    }

    @JvmStatic
    fun parseANTLRGrammar(text: String): ParsingResult {
        val input = CharStreams.fromString(text)
        val lexer = ANTLRv4Lexer(input)
        val tokens = CommonTokenStream(lexer)
        val parser = ANTLRv4Parser(tokens)

        val listener = SyntaxErrorListener()
        parser.removeErrorListeners()
        parser.addErrorListener(listener)
        lexer.removeErrorListeners()
        lexer.addErrorListener(listener)

        val t: ParseTree = parser.grammarSpec()
        return ParsingResult.java(parser, t, listener)
    }

    suspend fun parseText(
        g: Grammar?,
        lg: LexerGrammar?,
        startRuleName: String?,
        grammarFile: VirtualFile,
        inputText: String,
        project: Project,
    ): ParsingResult? =
        parseText(
            g,
            lg,
            startRuleName,
            grammarFile,
            inputText,
            project,
            null,
            null,
            null,
            null,
            null,
        )

    /** Parses with compiled classes; returns null when compilation or parsing cannot proceed. */
    suspend fun parseText(
        g: Grammar?,
        lg: LexerGrammar?,
        startRuleName: String?,
        grammarFile: VirtualFile,
        inputText: String,
        project: Project,
        generatedCodeDir: File?,
        indicator: ProgressIndicator?,
        caseChangingStrategy: CaseChangingStrategy? = null,
        previewRuntime: String? = null,
        previewContext: CompiledParsingService.PreviewCompilationContext? = null,
    ): ParsingResult? {
        val previewService = project.getService(PreviewService::class.java)

        if (g == null || lg == null) {
            LOG.warn("Grammar or lexer grammar is null: g=$g, lg=$lg")
            previewService.appendAntlrLine(
                "Failed to load grammar. Check for grammar errors above.",
            )
            return null
        }

        // Compiled-preview failures must not fall back to interpreted execution.
        if (generatedCodeDir != null && generatedCodeDir.exists()) {
            when (
                val compiledResult =
                    CompiledParsingService.parseWithCompiledClasses(
                        g,
                        lg,
                        startRuleName,
                        grammarFile,
                        inputText,
                        generatedCodeDir,
                        project,
                        indicator,
                        caseChangingStrategy,
                        previewRuntime,
                        previewContext,
                    )
            ) {
                is CompiledParsingService.CompiledParseResult.Success -> {
                    return compiledResult.result
                }
                is CompiledParsingService.CompiledParseResult.GrammarCodeUntrusted -> {
                    LOG.info("Preview generation blocked: project is not trusted")
                    return null
                }
                is CompiledParsingService.CompiledParseResult.MissingSuperClassFile -> {
                    val missing = compiledResult.missingClasses
                    LOG.info(
                        "Preview generation blocked: missing class-valued option source files: $missing",
                    )
                    previewService.appendAntlrLine(
                        "Cannot generate preview: the grammar references class(es) the plugin could not find:",
                    )
                    missing.forEach { label ->
                        // Missing-source labels can include an option name and a qualified class
                        // name.
                        val rawValue = label.substringAfterLast('=').trim().ifBlank { label }
                        val simpleName = rawValue.substringAfterLast('.')
                        previewService.appendAntlrLine(
                            "  - $label  (looking for $simpleName.java or $simpleName.kt)",
                        )
                    }
                    previewService.appendAntlrLine(
                        "Add the missing source file(s) anywhere in your project tree, or fix the option value in the grammar.",
                    )
                    return null
                }
                is CompiledParsingService.CompiledParseResult.UserCancelled -> {
                    LOG.info(
                        "Preview generation cancelled: user skipped class file selection for '${compiledResult.className}'",
                    )
                    return null
                }
                is CompiledParsingService.CompiledParseResult.Timeout -> {
                    LOG.warn("Preview generation timed out after ${compiledResult.timeoutMs}ms")
                    previewService.appendAntlrLine(
                        "Preview generation timed out after ${compiledResult.timeoutMs / 1000} seconds.",
                    )
                    previewService.appendAntlrLine(
                        "The grammar may have infinite loops or the input is too complex.",
                    )
                    return null
                }
                is CompiledParsingService.CompiledParseResult.Failed -> {
                    LOG.info("Compiled parsing failed, preview generation blocked")
                    return null
                }
            }
        }

        LOG.warn("No generated code directory available for preview")
        return null
    }

    internal fun autoSetStartRule(previewState: PreviewState) {
        previewState.grammar?.let { g ->
            if (g.rules.isEmpty()) {
                previewState.startRuleName = null
            } else if (previewState.startRuleName == null) {
                previewState.startRuleName = findBestStartRule(g)
            }
        }
    }

    fun createANTLRToolForLoadingGrammars(project: Project): Tool {
        val antlr = Tool()
        antlr.errMgr = PluginIgnoreMissingTokensFileErrorManager(antlr)
        antlr.errMgr.setFormat("antlr")
        val listener = LoadGrammarsToolListener(antlr)
        antlr.removeListeners()
        antlr.addListener(listener)
        antlr.libDirectory = project.getService(PreviewService::class.java).state.liveSettings?.libDir ?: ""
        return antlr
    }

    fun loadGrammars(project: Project, virtualFile: VirtualFile): Pair<LexerGrammar?, Grammar?>? {
        if (!project.compatIsTrusted()) return null
        val antlr = createANTLRToolForLoadingGrammars(project)
        val listener = antlr.getListeners().first() as LoadGrammarsToolListener

        val grammar =
            loadGrammar(virtualFile, antlr)
                ?: run {
                    reportBadGrammar(project, virtualFile)
                    return null
                }

        val lexerGrammar: LexerGrammar? =
            when (grammar.getType()) {
                ANTLRParser.PARSER -> {
                    val lg = loadLexerGrammarFor(grammar, project)
                    if (lg == null) {
                        val expectedLexer = getLexerNameFromParserFileName(grammar.fileName)
                        printToAntlrConsole(
                            project,
                            "Could not load lexer grammar. Expected: $expectedLexer",
                        )
                        BAD_LEXER_GRAMMAR
                    } else {
                        grammar.importVocab(lg)
                        lg
                    }
                }
                ANTLRParser.LEXER -> grammar as LexerGrammar
                ANTLRParser.COMBINED -> null
                else -> return null
            }

        // Processing creates the implicit lexer for combined grammars.
        antlr.process(grammar, false)
        if (listener.grammarErrorMessages.isNotEmpty()) {
            val msg = listener.grammarErrorMessages.joinToString("\n")
            printToAntlrConsole(project, msg, true)
            return null
        }

        val finalLexerGrammar =
            if (grammar.getType() == ANTLRParser.COMBINED) {
                val implicitLexer = grammar.getImplicitLexer()
                if (implicitLexer == null) {
                    printToAntlrConsole(
                        project,
                        "Could not extract implicit lexer from combined grammar.",
                    )
                    BAD_LEXER_GRAMMAR
                } else {
                    implicitLexer
                }
            } else {
                lexerGrammar
            }

        return when (grammar.getType()) {
            ANTLRParser.PARSER -> finalLexerGrammar to grammar
            ANTLRParser.LEXER -> finalLexerGrammar to null
            ANTLRParser.COMBINED -> finalLexerGrammar to grammar
            else -> null
        }
    }

    private fun printToAntlrConsole(project: Project, msg: String, isError: Boolean = true) {
        project.service<PreviewService>().let { service ->
            service.antlrConsolePanel?.appendLine(msg, isError)
        }
    }

    private fun reportBadGrammar(project: Project, grammarFile: VirtualFile) {
        printToAntlrConsole(project, "Empty or bad grammar in file ${grammarFile.name}", true)
    }

    private fun loadGrammar(grammarFile: VirtualFile, antlr: Tool): Grammar? {
        // Handle a missing AST before constructing the grammar.
        val grammarRootAST: GrammarRootAST = parseGrammar(antlr, grammarFile) ?: return null

        val g: Grammar? = antlr.createGrammar(grammarRootAST)
        g?.fileName = grammarFile.path

        return g
    }

    fun parseGrammar(antlr: Tool, grammarFile: VirtualFile): GrammarRootAST? {
        try {
            val document =
                ReadAction.compute<Document?, Throwable> {
                    FileDocumentManager.getInstance().getDocument(grammarFile)
                } ?: throw IllegalStateException("Document not found for file: ${grammarFile.path}")

            val grammarText = document.text
            val `in` = ANTLRStringStream(grammarText)
            `in`.name = grammarFile.path
            return antlr.parse(grammarFile.path, `in`)
        } catch (ioe: IOException) {
            antlr.errMgr.toolError(ErrorType.CANNOT_OPEN_FILE, ioe, grammarFile)
        }
        return null
    }

    /** Loads the lexer named by tokenVocab, or derives a lexer filename from the parser filename. */
    fun loadLexerGrammarFor(grammar: Grammar, project: Project): LexerGrammar? {
        if (!project.compatIsTrusted()) return null
        val antlr = createANTLRToolForLoadingGrammars(project)
        val listener = antlr.getListeners()[0] as LoadGrammarsToolListener
        var lg: LexerGrammar? = null
        val lexerGrammarFile: VirtualFile?

        val vocabName = grammar.getOptionString("tokenVocab")
        if (vocabName != null) {
            val grammarFile = LocalFileSystem.getInstance().findFileByIoFile(File(grammar.fileName))
            lexerGrammarFile = VfsUtil.findRelativeFile(grammarFile?.parent, "$vocabName.g4")
            if (lexerGrammarFile == null) {
                printToAntlrConsole(
                    project,
                    "Lexer grammar file not found: $vocabName.g4 (specified in tokenVocab)",
                )
            }
        } else {
            val expectedLexerPath = getLexerNameFromParserFileName(grammar.fileName)
            lexerGrammarFile = LocalFileSystem.getInstance().findFileByIoFile(File(expectedLexerPath))
            if (lexerGrammarFile == null) {
                printToAntlrConsole(
                    project,
                    "Lexer grammar file not found: ${File(expectedLexerPath).name}",
                )
            }
        }

        if (lexerGrammarFile != null && lexerGrammarFile.exists()) {
            try {
                lg = loadGrammar(lexerGrammarFile, antlr) as LexerGrammar?
                if (lg != null) {
                    antlr.process(lg, false)
                } else {
                    reportBadGrammar(project, lexerGrammarFile)
                }
            } catch (cce: ClassCastException) {
                LOG.error("File $lexerGrammarFile isn't a lexer grammar", cce)
                printToAntlrConsole(
                    project,
                    "File ${lexerGrammarFile.name} is not a lexer grammar (expected 'lexer grammar')",
                )
            } catch (e: Exception) {
                var msg = "Error loading lexer grammar: ${e.message}"
                if (listener.grammarErrorMessages.isNotEmpty()) {
                    msg += "\n" + listener.grammarErrorMessages.joinToString("\n")
                }
                LOG.error("File $lexerGrammarFile couldn't be parsed as a lexer grammar", e)
                printToAntlrConsole(project, msg)
            }
            if (listener.grammarErrorMessages.isNotEmpty()) {
                val msg = listener.grammarErrorMessages.joinToString("\n")
                printToAntlrConsole(project, msg, true)
                lg = null
            }
        }
        return lg
    }

    fun getLexerNameFromParserFileName(parserFileName: String): String {
        val lowerName = parserFileName.lowercase()
        val i = lowerName.indexOf("parser.g4")
        if (i >= 0) {
            return parserFileName.substring(0, i) + "Lexer.g4"
        } else {
            val f = File(parserFileName)
            val fname = f.name
            val lowerFname = fname.lowercase()
            val dot = lowerFname.lastIndexOf(".g4")
            if (dot < 0) {
                throw IllegalArgumentException(
                    "Invalid parser file name: $parserFileName (expected .g4 extension)",
                )
            }
            val parserName = fname.substring(0, dot)
            val parentDir = f.parentFile
            return File(parentDir, parserName + "Lexer.g4").absolutePath
        }
    }

    /** Resolves a sibling lexer from the first tokenVocab-shaped text match, if the file exists. */
    fun getLexerFileFromTokenVocab(grammarFile: File, charset: Charset = Charsets.UTF_8): File? {
        if (!grammarFile.exists()) return null

        val content = grammarFile.readText(charset)
        val regex = Regex("""tokenVocab\s*=\s*(\w+)\s*;""")
        val match = regex.find(content) ?: return null

        val vocabName = match.groupValues[1]
        val lexerFile = File(grammarFile.parentFile, "$vocabName.g4")
        return if (lexerFile.exists()) lexerFile else null
    }

    /** Tries tokenVocab first, then the parser-to-lexer filename convention. */
    fun getLexerFileForParser(parserFilePath: String, charset: Charset = Charsets.UTF_8): String? {
        val parserFile = File(parserFilePath)

        val tokenVocabLexer = getLexerFileFromTokenVocab(parserFile, charset)
        if (tokenVocabLexer != null) {
            return tokenVocabLexer.absolutePath
        }

        val conventionLexer = getLexerNameFromParserFileName(parserFilePath)
        return if (File(conventionLexer).exists()) conventionLexer else null
    }
}
