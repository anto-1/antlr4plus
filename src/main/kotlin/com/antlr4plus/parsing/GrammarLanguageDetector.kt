package com.antlr4plus.parsing

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.nio.charset.Charset

/** Infers a target from options, action syntax and nearby base-class files; defaults to Java. */
object GrammarLanguageDetector {
    private val LOG = Logger.getInstance(GrammarLanguageDetector::class.java)

    enum class TargetLanguage {
        JAVA,
        PYTHON,
        CSHARP,
        JAVASCRIPT,
        GO,
        CPP,
        DART,
        PHP,
        SWIFT,
        TYPESCRIPT,
        UNKNOWN;

        companion object {
            fun fromSettingsValue(value: String?): TargetLanguage =
                when (value?.lowercase()) {
                    "java" -> JAVA
                    "python3",
                    "python2",
                    "python" -> PYTHON
                    "csharp",
                    "c#" -> CSHARP
                    "javascript",
                    "js" -> JAVASCRIPT
                    "typescript",
                    "ts" -> TYPESCRIPT
                    "go",
                    "golang" -> GO
                    "cpp",
                    "c++" -> CPP
                    "dart" -> DART
                    "php" -> PHP
                    "swift" -> SWIFT
                    else -> UNKNOWN
                }

            fun getDisplayName(language: TargetLanguage): String =
                when (language) {
                    JAVA -> "Java"
                    PYTHON -> "Python"
                    CSHARP -> "C#"
                    JAVASCRIPT -> "JavaScript"
                    TYPESCRIPT -> "TypeScript"
                    GO -> "Go"
                    CPP -> "C++"
                    DART -> "Dart"
                    PHP -> "PHP"
                    SWIFT -> "Swift"
                    UNKNOWN -> "Unknown"
                }
        }
    }

    data class DetectionResult(
        val detectedLanguage: TargetLanguage,
    ) {
        val isJavaCompatible: Boolean
            get() = detectedLanguage == TargetLanguage.JAVA || detectedLanguage == TargetLanguage.UNKNOWN

        fun getWarningMessage(): String? {
            if (isJavaCompatible) return null
            val languageName = TargetLanguage.getDisplayName(detectedLanguage)
            return "This grammar targets $languageName. Preview generation requires Java-compatible grammars."
        }
    }

    data class CompatibilityResult(
        val isCompatible: Boolean,
        val selectedLanguage: TargetLanguage,
        val embeddedCodeLanguage: TargetLanguage?,
        val hasEmbeddedCode: Boolean,
        val hasLanguageOption: Boolean,
        val optionLanguage: TargetLanguage?,
        val warningMessage: String?,
    ) {
        companion object {
            fun compatible(selectedLanguage: TargetLanguage): CompatibilityResult =
                CompatibilityResult(
                    isCompatible = true,
                    selectedLanguage = selectedLanguage,
                    embeddedCodeLanguage = null,
                    hasEmbeddedCode = false,
                    hasLanguageOption = false,
                    optionLanguage = null,
                    warningMessage = null,
                )

            fun compatibleWithEmbeddedCode(
                selectedLanguage: TargetLanguage,
                embeddedLanguage: TargetLanguage,
            ): CompatibilityResult =
                CompatibilityResult(
                    isCompatible = true,
                    selectedLanguage = selectedLanguage,
                    embeddedCodeLanguage = embeddedLanguage,
                    hasEmbeddedCode = true,
                    hasLanguageOption = false,
                    optionLanguage = null,
                    warningMessage = null,
                )

            fun incompatibleEmbeddedCode(
                selectedLanguage: TargetLanguage,
                embeddedLanguage: TargetLanguage,
            ): CompatibilityResult {
                val selectedName = TargetLanguage.getDisplayName(selectedLanguage)
                val embeddedName = TargetLanguage.getDisplayName(embeddedLanguage)
                return CompatibilityResult(
                    isCompatible = false,
                    selectedLanguage = selectedLanguage,
                    embeddedCodeLanguage = embeddedLanguage,
                    hasEmbeddedCode = true,
                    hasLanguageOption = false,
                    optionLanguage = null,
                    warningMessage =
                        "Grammar contains $embeddedName embedded action code, but target language is set to $selectedName. The generated code may not compile correctly.",
                )
            }

            fun incompatibleLanguageOption(
                selectedLanguage: TargetLanguage,
                optionLanguage: TargetLanguage,
            ): CompatibilityResult {
                val selectedName = TargetLanguage.getDisplayName(selectedLanguage)
                val optionName = TargetLanguage.getDisplayName(optionLanguage)
                return CompatibilityResult(
                    isCompatible = false,
                    selectedLanguage = selectedLanguage,
                    embeddedCodeLanguage = null,
                    hasEmbeddedCode = false,
                    hasLanguageOption = true,
                    optionLanguage = optionLanguage,
                    warningMessage =
                        "Grammar specifies 'language=$optionName' in options block, but target language is set to $selectedName. The grammar's language option will override the setting.",
                )
            }
        }
    }

    private val LANGUAGE_OPTION_MAP =
        mapOf(
            "java" to TargetLanguage.JAVA,
            "python" to TargetLanguage.PYTHON,
            "python2" to TargetLanguage.PYTHON,
            "python3" to TargetLanguage.PYTHON,
            "csharp" to TargetLanguage.CSHARP,
            "javascript" to TargetLanguage.JAVASCRIPT,
            "typescript" to TargetLanguage.JAVASCRIPT,
            "go" to TargetLanguage.GO,
            "golang" to TargetLanguage.GO,
            "cpp" to TargetLanguage.CPP,
        )

    // grammars-v4 places language-specific base classes in children of the grammar directory.
    private val LANGUAGE_DIR_MAP = LANGUAGE_OPTION_MAP - "java"

    private val BASE_CLASS_EXTENSIONS =
        mapOf(
            ".py" to TargetLanguage.PYTHON,
            ".cs" to TargetLanguage.CSHARP,
            ".js" to TargetLanguage.JAVASCRIPT,
            ".ts" to TargetLanguage.JAVASCRIPT,
            ".go" to TargetLanguage.GO,
            ".cpp" to TargetLanguage.CPP,
            ".cc" to TargetLanguage.CPP,
        )

    fun detectTargetLanguage(
        grammarFile: VirtualFile,
        charset: Charset = Charsets.UTF_8,
    ): DetectionResult {
        val content =
            try {
                grammarFile.contentsToByteArray().toString(charset)
            } catch (e: Exception) {
                LOG.warn("Could not read grammar file: ${grammarFile.path}", e)
                return DetectionResult(TargetLanguage.JAVA)
            }

        // Parent directory names may describe the parsed language, not the generation target.
        return detectFromOptionsBlock(content)
            ?: detectFromActionBlocks(content)
            ?: detectFromBaseClassFiles(grammarFile, content)
            ?: DetectionResult(TargetLanguage.JAVA)
    }

    fun detectTargetLanguage(
        grammarFile: File,
        charset: Charset = Charsets.UTF_8,
    ): DetectionResult {
        val content =
            try {
                grammarFile.readText(charset)
            } catch (e: Exception) {
                LOG.warn("Could not read grammar file: ${grammarFile.path}", e)
                return DetectionResult(TargetLanguage.JAVA)
            }
        return detectTargetLanguage(content)
    }

    fun detectTargetLanguage(content: String): DetectionResult =
        detectFromOptionsBlock(content) ?: detectFromActionBlocks(content) ?: DetectionResult(TargetLanguage.JAVA)

    /** Reads the first options block with simple text searches, without lexical validation. */
    private fun detectFromOptionsBlock(content: String): DetectionResult? {
        val optionsStart = content.indexOf("options")
        if (optionsStart == -1) return null

        val braceStart = content.indexOf('{', optionsStart)
        if (braceStart == -1 || braceStart - optionsStart > 20) return null

        val braceEnd = content.indexOf('}', braceStart)
        if (braceEnd == -1) return null

        val optionsContent = content.substring(braceStart + 1, braceEnd)

        val langIndex = optionsContent.indexOf("language")
        if (langIndex == -1) return null

        val equalsIndex = optionsContent.indexOf('=', langIndex)
        if (equalsIndex == -1 || equalsIndex - langIndex > 15) return null

        val semicolonIndex = optionsContent.indexOf(';', equalsIndex)
        if (semicolonIndex == -1) return null

        val value = optionsContent.substring(equalsIndex + 1, semicolonIndex).trim().trim('"', '\'', ' ')

        return LANGUAGE_OPTION_MAP[value.lowercase()]?.let(::DetectionResult)
    }

    private val PYTHON_PATTERNS =
        listOf(
            Regex("""self\.[a-zA-Z_]"""),
            Regex("""def\s+[a-zA-Z_]+\s*\(self"""),
            Regex("""from\s+\w+\s+import"""),
            Regex("""class\s+\w+.*:\s*$""", RegexOption.MULTILINE),
            Regex("""\bNone\b"""),
            Regex("""\bTrue\b"""),
            Regex("""\bFalse\b"""),
            Regex("""len\s*\("""),
            Regex("""if\s+.+:\s*$""", RegexOption.MULTILINE),
            Regex("""elif\s+.+:"""),
            Regex("""except\s*.*:"""),
        )

    private val CSHARP_PATTERNS =
        listOf(
            Regex("""using\s+System"""),
            Regex("""namespace\s+\w+"""),
            Regex("""\bvar\s+\w+\s*="""),
            Regex("""public\s+(override|virtual|static)"""),
        )

    private val JS_PATTERNS =
        listOf(
            Regex("""const\s+\w+\s*="""),
            Regex("""let\s+\w+\s*="""),
            Regex("""===|!=="""),
            Regex("""function\s*\("""),
            Regex("""=>\s*\{"""),
        )

    private val GO_PATTERNS =
        listOf(
            Regex("""func\s+\("""),
            Regex("""package\s+\w+"""),
            Regex(""":=\s*"""),
        )

    private val CPP_PATTERNS =
        listOf(
            Regex("""#include\s*<"""),
            Regex("""std::\w+"""),
            Regex("""nullptr"""),
            Regex("""->\w+"""),
        )

    private fun detectFromActionBlocks(content: String): DetectionResult? {
        val actionContent = extractActionBlocks(content)
        if (actionContent.isEmpty()) return null

        return detectNonJavaCodeLanguage(actionContent)?.let(::DetectionResult)
    }

    private fun detectFromBaseClassFiles(
        grammarFile: VirtualFile,
        content: String,
    ): DetectionResult? {
        val superClassName = extractSuperClassName(content) ?: return null
        val grammarDir = grammarFile.parent ?: return null

        for ((dirName, language) in LANGUAGE_DIR_MAP) {
            val langDir = grammarDir.findChild(dirName) ?: grammarDir.parent?.findChild(dirName)
            if (langDir != null && langDir.isDirectory) {
                for ((ext, extLang) in BASE_CLASS_EXTENSIONS) {
                    if (extLang == language) {
                        val baseFile = langDir.findChild("$superClassName$ext")
                        if (baseFile != null) {
                            return DetectionResult(language)
                        }
                    }
                }
            }
        }

        for ((ext, language) in BASE_CLASS_EXTENSIONS) {
            val baseFile = grammarDir.findChild("$superClassName$ext")
            if (baseFile != null) {
                return DetectionResult(language)
            }
        }

        return null
    }

    /** Reports inferred mismatches between the grammar and selected target language. */
    fun checkTargetLanguageCompatibility(
        grammarFile: VirtualFile,
        selectedLanguage: String?,
        charset: Charset = Charsets.UTF_8,
    ): CompatibilityResult {
        val targetLang = TargetLanguage.fromSettingsValue(selectedLanguage)

        if (targetLang == TargetLanguage.JAVA || targetLang == TargetLanguage.UNKNOWN) {
            return CompatibilityResult.compatible(targetLang)
        }

        val content =
            try {
                grammarFile.contentsToByteArray().toString(charset)
            } catch (e: Exception) {
                LOG.warn("Could not read grammar file: ${grammarFile.path}", e)
                return CompatibilityResult.compatible(targetLang)
            }

        return checkTargetLanguageCompatibility(content, targetLang)
    }

    fun checkTargetLanguageCompatibility(
        content: String,
        selectedLanguage: TargetLanguage,
    ): CompatibilityResult {
        val optionsLanguage = detectLanguageFromOptionsBlock(content)
        if (optionsLanguage != null && optionsLanguage != TargetLanguage.UNKNOWN) {
            if (optionsLanguage != selectedLanguage) {
                return CompatibilityResult.incompatibleLanguageOption(
                    selectedLanguage,
                    optionsLanguage,
                )
            }
            return CompatibilityResult.compatibleWithEmbeddedCode(selectedLanguage, optionsLanguage)
        }

        val actionContent = extractActionBlocks(content)
        if (actionContent.isEmpty()) {
            return CompatibilityResult.compatible(selectedLanguage)
        }

        val embeddedLanguage = detectEmbeddedCodeLanguage(actionContent)
        if (embeddedLanguage == null || embeddedLanguage == TargetLanguage.UNKNOWN) {
            return CompatibilityResult.compatible(selectedLanguage)
        }

        if (embeddedLanguage != selectedLanguage) {
            return CompatibilityResult.incompatibleEmbeddedCode(selectedLanguage, embeddedLanguage)
        }

        return CompatibilityResult.compatibleWithEmbeddedCode(selectedLanguage, embeddedLanguage)
    }

    private fun detectLanguageFromOptionsBlock(content: String): TargetLanguage? =
        detectFromOptionsBlock(content)?.detectedLanguage

    /** Returns the first language meeting its pattern threshold, or null when none does. */
    private fun detectEmbeddedCodeLanguage(actionContent: String): TargetLanguage? =
        detectNonJavaCodeLanguage(actionContent)
            ?: TargetLanguage.JAVA.takeIf {
                JAVA_PATTERNS.count { it.containsMatchIn(actionContent) } >= 2
            }

    private fun detectNonJavaCodeLanguage(actionContent: String): TargetLanguage? =
        when {
            PYTHON_PATTERNS.count { it.containsMatchIn(actionContent) } >= 2 -> TargetLanguage.PYTHON
            CSHARP_PATTERNS.count { it.containsMatchIn(actionContent) } >= 2 -> TargetLanguage.CSHARP
            JS_PATTERNS.count { it.containsMatchIn(actionContent) } >= 2 -> TargetLanguage.JAVASCRIPT
            GO_PATTERNS.count { it.containsMatchIn(actionContent) } >= 2 -> TargetLanguage.GO
            CPP_PATTERNS.count { it.containsMatchIn(actionContent) } >= 2 -> TargetLanguage.CPP
            else -> null
        }

    private val JAVA_PATTERNS =
        listOf(
            Regex("""import\s+[a-z][a-z0-9_]*(\.[a-z0-9_]+)*\.[A-Z]"""),
            Regex("""(public|private|protected)\s+(class|interface|enum)"""),
            Regex("""System\.(out|err)\."""),
            Regex("""new\s+[A-Z][a-zA-Z0-9_]*\s*\("""),
            Regex("""@Override"""),
            Regex("""\.equals\s*\("""),
            Regex("""instanceof\s+"""),
            Regex("""(ArrayList|HashMap|List|Map|Set)<"""),
        )
}
