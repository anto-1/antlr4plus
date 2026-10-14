package com.antlr4plus.codeGen

import com.antlr4plus.codeGen.GenAntlrCode.SUPPORTED_TARGET_LANGUAGES
import com.antlr4plus.codeGen.GenAntlrCode.getContentRoot
import com.antlr4plus.codeGen.GenAntlrCode.getParentDir
import com.antlr4plus.codeGen.GenAntlrCode.isValidPackageName
import com.antlr4plus.parsing.ParsingUtils
import com.antlr4plus.previewService.PreviewService
import com.antlr4plus.settings.GrammarProperties
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.nio.charset.Charset

internal fun buildAntlrArguments(project: Project, vfile: VirtualFile): MutableList<String> =
    getANTLRArgs(project, vfile)
        .flatMap { (key, value) ->
            if (value.isNotEmpty()) listOf(key, value) else listOf(key)
        }
        .toMutableList()

/** Appends grammar inputs to [args] and returns the same mutable list. */
internal fun preparePreviewAntlrArguments(
    args: MutableList<String>,
    fullyQualifiedInputFileName: String,
): MutableList<String> {
    args.add(fullyQualifiedInputFileName)
    val encodingIndex = args.indexOf("-encoding")
    val grammarCharset =
        if (encodingIndex >= 0 && encodingIndex + 1 < args.size) {
            Charset.forName(args[encodingIndex + 1])
        } else {
            Charsets.UTF_8
        }
    val lexerGrammarFileName = ParsingUtils.getLexerFileForParser(fullyQualifiedInputFileName, grammarCharset)
    if (lexerGrammarFileName != null) {
        args.add(lexerGrammarFileName)
    }
    return args
}

private fun getANTLRArgs(project: Project, vfile: VirtualFile): LinkedHashMap<String, String> {
    val service = project.getService(PreviewService::class.java)
    val sourcePath = getParentDir(vfile)
    val liveSettings = service.state.liveSettings

    // Resolve relative output paths from the .g4p directory, or the grammar content root if absent.
    val g4pDir = service.state.g4pInputFile?.parent
    val contentRoot = g4pDir ?: getContentRoot(project, vfile)

    val packageName = liveSettings?.packageName?.trim().orEmpty()
    require(packageName.isEmpty() || isValidPackageName(packageName)) {
        "Invalid package name '$packageName'. Use dot-separated identifiers."
    }

    val targetLanguage = liveSettings?.language?.takeIf { it.isNotBlank() } ?: "Java"
    require(targetLanguage in SUPPORTED_TARGET_LANGUAGES) {
        "Unsupported target language '$targetLanguage'"
    }

    val encoding = liveSettings?.encoding?.takeIf { it.isNotBlank() }
    if (encoding != null) {
        require(runCatching { Charset.isSupported(encoding) }.getOrDefault(false)) {
            "Unsupported grammar encoding '$encoding'"
        }
    }

    return linkedMapOf<String, String>().apply {
        if (packageName.isNotBlank()) {
            this["-package"] = packageName
        }

        this["-Dlanguage=$targetLanguage"] = ""

        val baseOutputDir = GrammarProperties.resolveOutputDirName(service.state, project, contentRoot, "")
        val createLangSubdir = liveSettings?.createLanguageSubdir ?: true
        val effectiveOutputDir =
            if (createLangSubdir) {
                "$baseOutputDir${File.separator}$targetLanguage"
            } else {
                baseOutputDir
            }
        val outputDirName =
            if (packageName.isNotBlank()) {
                "$effectiveOutputDir${File.separator}${packageName.replace('.', File.separatorChar)}"
            } else {
                effectiveOutputDir
            }
        this["-o"] = outputDirName

        // Resolve token-library paths from the grammar source directory.
        val libDir =
            requireNotNull(GrammarProperties.resolveLibDir(service.state, project, sourcePath)) {
                "Missing libDir"
            }
        val absoluteLibDir = if (File(libDir).isAbsolute) libDir else "${contentRoot.path}${File.separator}$libDir"
        this["-lib"] = absoluteLibDir

        encoding?.let {
            this["-encoding"] = it
        }

        // ANTLR defaults to listeners enabled and visitors disabled.
        if (liveSettings?.generateListener == false) {
            this["-no-listener"] = ""
        }
        if (liveSettings?.generateVisitor == true) {
            this["-visitor"] = ""
        }
    }
}
