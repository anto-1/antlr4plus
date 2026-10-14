package com.antlr4plus.codeGen

import com.antlr4plus.compat.compatIsTrusted
import com.antlr4plus.notifications.NotificationsUtils
import com.antlr4plus.parsing.ANTLRToolListenerImpl
import com.antlr4plus.parsing.GrammarWarningsService
import com.antlr4plus.parsing.ParsingUtils
import com.antlr4plus.parsing.TrustedFilesManager
import com.antlr4plus.previewService.PreviewService
import com.antlr4plus.util.rethrowFatalOrCancellation
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.nio.charset.Charset
import org.antlr.v4.Tool

object GenAntlrCode {

    data class GenerationResult(
        val successful: Boolean,
        val outputDirectory: String,
        val errors: List<String> = emptyList(),
        val failure: Throwable? = null,
    )

    val LOG: Logger = Logger.getInstance("GenAntlrCode")
    const val GROUP_DISPLAY_ID: String = "ANTLR 4 Parser Generation"
    val SUPPORTED_TARGET_LANGUAGES: List<String> =
        listOf(
            "Cpp",
            "CSharp",
            "Dart",
            "Go",
            "Java",
            "JavaScript",
            "Kotlin",
            "PHP",
            "Python3",
            "Rust",
            "Swift",
            "TypeScript",
        )

    private val PACKAGE_NAME_REGEX = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")

    fun antlr(project: Project, vfile: VirtualFile): GenerationResult =
        try {
            antlr(
                project,
                vfile,
                getANTLRArgsAsList(project, vfile),
                project.getService(PreviewService::class.java).state.liveSettings?.language?.takeIf { it.isNotBlank() }
                    ?: "Java",
            )
        } catch (failure: Throwable) {
            generationFailure(project, vfile, failure)
        }

    /** Generates with arguments captured by the initiating UI action. */
    fun antlr(
        project: Project,
        vfile: VirtualFile,
        capturedArgs: List<String>,
        targetLanguage: String,
    ): GenerationResult =
        try {
            executeAntlr(project, vfile, capturedArgs, targetLanguage)
        } catch (failure: Throwable) {
            generationFailure(project, vfile, failure)
        }

    private fun generationFailure(
        project: Project,
        vfile: VirtualFile,
        failure: Throwable,
    ): GenerationResult {
        rethrowFatalOrCancellation(failure)
        LOG.warn("Can't configure parser generation for ${vfile.path}", failure)
        val summary = failure.message ?: failure::class.java.simpleName
        Notifications.Bus.notify(
            Notification(
                GROUP_DISPLAY_ID,
                "Can't generate parser for ${vfile.name}: $summary",
                NotificationType.ERROR,
            ),
            project,
        )
        return GenerationResult(
            successful = false,
            outputDirectory = "",
            errors = listOf(summary),
            failure = failure,
        )
    }

    private fun executeAntlr(
        project: Project,
        vfile: VirtualFile,
        capturedArgs: List<String>,
        targetLanguage: String,
    ): GenerationResult {
        check(project.compatIsTrusted()) { "Trust the project before generating parser code." }
        val args = capturedArgs.toMutableList()
        val sourcePath = getParentDir(vfile)
        val fullyQualifiedInputFileName = "$sourcePath${File.separator}${vfile.name}"
        args.add(fullyQualifiedInputFileName)
        val grammarCharset = args.valueAfter("-encoding")?.let(Charset::forName) ?: Charsets.UTF_8
        val lexerGrammarFileName = ParsingUtils.getLexerFileForParser(fullyQualifiedInputFileName, grammarCharset)
        if (lexerGrammarFileName != null) {
            args.add(lexerGrammarFileName)
        }

        val grammarFile = File(fullyQualifiedInputFileName)
        val lexerFile = lexerGrammarFileName?.let { File(it) }
        val expectedFileNames = computeExpectedAntlrOutputFiles(grammarFile, lexerFile, grammarCharset)

        // Output directories may contain handwritten sources; clean only this grammar's expected
        // Java files.
        if (args.any { it == "-Dlanguage=Java" }) {
            val outputIndex = args.indexOf("-o")
            if (outputIndex >= 0 && outputIndex + 1 < args.size) {
                cleanExpectedGeneratedFiles(File(args[outputIndex + 1]), expectedFileNames)
            }
        }

        val warningsService = GrammarWarningsService.getInstance(project)
        warningsService.clearWarnings(fullyQualifiedInputFileName)
        if (lexerGrammarFileName != null) {
            warningsService.clearWarnings(lexerGrammarFileName)
        }

        LOG.info("Expected ANTLR output files: $expectedFileNames")

        LOG.info("  Creating ANTLR Tool with args: ${args.toTypedArray().toList()}")
        val antlr = Tool(args.toTypedArray())
        val listener =
            ANTLRToolListenerImpl(
                antlr,
                project.getService(PreviewService::class.java).antlrConsolePanel,
                project,
            )
        antlr.addListener(listener)
        return try {
            LOG.info("  Running antlr.processGrammarsOnCommandLine()...")
            antlr.processGrammarsOnCommandLine()
            LOG.info("  antlr.processGrammarsOnCommandLine() completed")

            if (listener.errors.isNotEmpty() || antlr.numErrors > 0) {
                val errors =
                    listener.errors.ifEmpty {
                        listOf("ANTLR reported ${antlr.numErrors} generation error(s)")
                    }
                val summary =
                    errors.first().lineSequence().firstOrNull() ?: "ANTLR reported an unknown generation error"
                LOG.warn("ANTLR generation failed for ${vfile.path}: $summary")
                warningsService.triggerAllReAnnotations()
                Notifications.Bus.notify(
                    Notification(
                        GROUP_DISPLAY_ID,
                        "Can't generate parser for ${vfile.name}: $summary",
                        NotificationType.ERROR,
                    ),
                    project,
                )
                return GenerationResult(
                    successful = false,
                    outputDirectory = antlr.outputDirectory,
                    errors = errors,
                )
            }

            val localFs = LocalFileSystem.getInstance()
            val outputDirPath = antlr.outputDirectory // Use ANTLR's resolved output path.
            LOG.info("  ANTLR output directory: $outputDirPath")
            val outputVf = localFs.refreshAndFindFileByPath(outputDirPath)
            if (outputVf != null && outputVf.isDirectory) {
                outputVf.refresh(false, true)

                // Register only files whose names match the expected Java outputs.
                val outputDir = File(outputDirPath)
                val allJavaFiles =
                    outputDir.listFiles { f -> f.isFile && f.extension == "java" }?.toList() ?: emptyList()
                val generatedFiles = allJavaFiles.filter { it.name in expectedFileNames }
                val unexpectedFiles = allJavaFiles.filter { it.name !in expectedFileNames }

                if (unexpectedFiles.isNotEmpty()) {
                    LOG.warn(
                        "SECURITY: Found ${unexpectedFiles.size} unexpected .java file(s) in output directory: ${unexpectedFiles.map { it.name }}",
                    )
                    LOG.warn(
                        "SECURITY: These files will NOT be trusted. Possible injection attempt.",
                    )
                }

                if (generatedFiles.isNotEmpty()) {
                    TrustedFilesManager.getInstance(project).markAsGenerated(generatedFiles)
                    LOG.info(
                        "Marked ${generatedFiles.size} expected generated .java files: ${generatedFiles.map { it.name }}",
                    )
                } else {
                    LOG.warn("  No generated files found in output directory")
                }
            } else {
                LOG.warn("  Output directory not found or not a directory: $outputDirPath")
            }

            warningsService.triggerAllReAnnotations()

            NotificationsUtils.showToast(
                project,
                "Generated $targetLanguage parser for ${vfile.name}",
            )
            GenerationResult(
                successful = true,
                outputDirectory = outputDirPath,
                errors = emptyList(),
            )
        } catch (failure: Throwable) {
            rethrowFatalOrCancellation(failure)
            LOG.warn("Can't generate parser for ${vfile.path}", failure)
            val message =
                "Can't generate parser for ${vfile.name}: ${failure.message ?: failure::class.java.simpleName}"
            val notification = Notification(GROUP_DISPLAY_ID, message, NotificationType.ERROR)
            Notifications.Bus.notify(notification, project)

            // Refresh partial output after generation errors.
            val localFs = LocalFileSystem.getInstance()
            val outputDirPath = antlr.outputDirectory
            val outputVf = localFs.refreshAndFindFileByPath(outputDirPath)
            if (outputVf != null && outputVf.isDirectory) {
                outputVf.refresh(false, true)
            }
            GenerationResult(
                successful = false,
                outputDirectory = outputDirPath,
                errors = listener.errors,
                failure = failure,
            )
        }
    }

    fun getANTLRArgsAsList(project: Project, vfile: VirtualFile): MutableList<String> =
        buildAntlrArguments(project, vfile)

    internal fun isValidPackageName(packageName: String): Boolean = PACKAGE_NAME_REGEX.matches(packageName)

    fun getParentDir(vfile: VirtualFile): String = vfile.parent.path

    fun getContentRoot(project: Project, vfile: VirtualFile): VirtualFile =
        ReadAction.compute<VirtualFile, RuntimeException?> {
            val root = ProjectRootManager.getInstance(project).fileIndex.getContentRootForFile(vfile)
            root ?: vfile.parent
        }

    internal fun cleanExpectedGeneratedFiles(outputDir: File, expectedFileNames: Set<String>) {
        if (!outputDir.isDirectory || expectedFileNames.isEmpty()) return

        outputDir
            .listFiles { file -> file.isFile && file.name in expectedFileNames }
            ?.forEach { generatedFile ->
                try {
                    if (!generatedFile.delete()) {
                        LOG.warn(
                            "Failed to delete stale generated file: ${generatedFile.absolutePath}",
                        )
                    }
                } catch (failure: Exception) {
                    LOG.warn(
                        "Error deleting stale generated file: ${generatedFile.absolutePath}",
                        failure,
                    )
                }
            }
    }

    private fun List<String>.valueAfter(flag: String): String? {
        val index = indexOf(flag)
        return takeIf { index >= 0 && index + 1 < size }?.get(index + 1)
    }

    /**
     * Candidate Java output names for cleanup and session registration. Includes listener and visitor names regardless
     * of the generation flags.
     */
    internal fun computeExpectedAntlrOutputFiles(
        parserGrammarFile: File,
        lexerGrammarFile: File?,
        charset: Charset = Charsets.UTF_8,
    ): Set<String> {
        val expectedFiles = mutableSetOf<String>()

        val parserGrammarContent = if (parserGrammarFile.exists()) parserGrammarFile.readText(charset) else ""
        val parserGrammarName = parserGrammarFile.nameWithoutExtension

        val isCombinedGrammar =
            !parserGrammarContent.contains(
                Regex("""^\s*(lexer|parser)\s+grammar""", RegexOption.MULTILINE),
            )
        val isLexerGrammar = parserGrammarContent.contains(Regex("""^\s*lexer\s+grammar""", RegexOption.MULTILINE))
        val isParserGrammar = parserGrammarContent.contains(Regex("""^\s*parser\s+grammar""", RegexOption.MULTILINE))

        if (isCombinedGrammar) {
            expectedFiles.add("${parserGrammarName}Lexer.java")
            expectedFiles.add("${parserGrammarName}Parser.java")
            expectedFiles.add("${parserGrammarName}BaseListener.java")
            expectedFiles.add("${parserGrammarName}Listener.java")
            expectedFiles.add("${parserGrammarName}BaseVisitor.java")
            expectedFiles.add("${parserGrammarName}Visitor.java")
        } else if (isParserGrammar) {
            expectedFiles.add("${parserGrammarName}.java")
            expectedFiles.add("${parserGrammarName}BaseListener.java")
            expectedFiles.add("${parserGrammarName}Listener.java")
            expectedFiles.add("${parserGrammarName}BaseVisitor.java")
            expectedFiles.add("${parserGrammarName}Visitor.java")
        } else if (isLexerGrammar) {
            expectedFiles.add("${parserGrammarName}.java")
        }

        if (lexerGrammarFile != null && lexerGrammarFile.exists()) {
            val lexerGrammarName = lexerGrammarFile.nameWithoutExtension
            expectedFiles.add("${lexerGrammarName}.java")
        }

        return expectedFiles
    }
}
