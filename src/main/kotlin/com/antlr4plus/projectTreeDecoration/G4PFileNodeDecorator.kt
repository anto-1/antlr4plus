package com.antlr4plus.projectTreeDecoration

import com.antlr4plus.filebinding.FileBindingService
import com.antlr4plus.icons.Icons
import com.antlr4plus.parsing.CompiledParsingService
import com.antlr4plus.parsing.GrammarWarningsService
import com.antlr4plus.parsing.TrustedFilesManager
import com.antlr4plus.previewService.PreviewService
import com.antlr4plus.settings.SettingsPersistenceService
import com.antlr4plus.state.ProcessingStatus
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.openapi.components.service
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import java.awt.Color
import java.nio.file.Path
import org.antlr.v4.tool.Grammar

internal fun normalizedProjectPath(path: String): String =
    try {
        Path.of(path).toAbsolutePath().normalize().toString().replace('\\', '/')
    } catch (_: RuntimeException) {
        path.replace('\\', '/')
    }

internal fun matchesActiveDependencyPath(filePath: String, activePaths: Set<String>): Boolean {
    val normalizedFilePath = normalizedProjectPath(filePath)
    return activePaths.any { normalizedProjectPath(it) == normalizedFilePath }
}

class G4PFileNodeDecorator : ProjectViewNodeDecorator {

    override fun decorate(node: ProjectViewNode<*>, data: PresentationData) {
        // Kotlin class nodes can expose their containing file only through PSI.
        var file = node.virtualFile
        if (file == null && node::class.simpleName == "KtClassOrObjectTreeNode") {
            try {
                val value = node.value
                if (value is com.intellij.psi.PsiElement) {
                    file = value.containingFile?.virtualFile
                }
            } catch (_: Exception) {}
        }

        if (file != null && node.project != null) {
            val isG4P = "g4p".equals(file.extension, ignoreCase = true)
            val isG4 = "g4".equals(file.extension, ignoreCase = true)
            val isJava = "java".equals(file.extension, ignoreCase = true)
            val isKotlin = "kt".equals(file.extension, ignoreCase = true)

            if (isJava || isKotlin) {
                val project = node.project!!
                val service = project.service<PreviewService>()
                if (!service.isEnabled) return

                val state = service.state

                val isInDependencyGraph = matchesActiveDependencyPath(file.path, state.activeExternalSourceFiles)

                if (isInDependencyGraph) {
                    // Reset inherited VCS and inspection styling before applying preview status.
                    data.background = null
                    data.forcedTextForeground = null

                    val status = state.status
                    val defaultFg = com.intellij.util.ui.UIUtil.getTreeForeground()

                    val bgColor =
                        when (status) {
                            is ProcessingStatus.Success -> Color(0x3C4CAF50.toInt(), true)
                            is ProcessingStatus.Loading -> Color(0x30808080.toInt(), true)
                            is ProcessingStatus.WaitingForVerification -> Color(0x30FF8C00.toInt(), true)
                            is ProcessingStatus.Cancelled -> Color(0x30808080.toInt(), true)
                            ProcessingStatus.Failed -> Color(0x30E74C3C.toInt(), true)
                            ProcessingStatus.Idle -> Color(0x30808080.toInt(), true)
                        }

                    val bulletColor =
                        if (service.isAutoRefreshPaused) {
                            Color(0x808080)
                        } else
                            when (status) {
                                is ProcessingStatus.Success -> Color(0x4CAF50)
                                is ProcessingStatus.Loading -> Color(0x808080)
                                is ProcessingStatus.WaitingForVerification -> Color(0x808080)
                                is ProcessingStatus.Cancelled -> Color(0x808080)
                                ProcessingStatus.Failed -> Color(0xE74C3C)
                                ProcessingStatus.Idle -> Color(0x808080)
                            }

                    // Use the raw node name to avoid carrying styled presentation text.
                    val displayName = node.name ?: file.nameWithoutExtension
                    data.clearText()
                    data.setAttributesKey(null)

                    data.addText(
                        "● ",
                        SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, bulletColor),
                    )

                    // STYLE_OPAQUE limits the background to the text region.
                    data.addText(
                        displayName,
                        SimpleTextAttributes(
                            bgColor,
                            defaultFg,
                            null,
                            SimpleTextAttributes.STYLE_OPAQUE,
                        ),
                    )

                    data.forcedTextForeground = defaultFg
                }
                return
            }

            // Use cached generated-file markers for decoration; this does not verify class content.
            val isClass = "class".equals(file.extension, ignoreCase = true)
            if (isClass) {
                val project = node.project!!
                val service = project.service<PreviewService>()
                if (!service.isEnabled) return

                val activeOutputDir = service.state.activeOutputDir ?: return

                val filePath = file.path
                val normalizedOutputDir = activeOutputDir.replace("\\", "/")
                val normalizedFilePath = filePath.replace("\\", "/")
                if (!normalizedFilePath.startsWith("$normalizedOutputDir/")) return

                val trustManager = project.service<TrustedFilesManager>()
                if (trustManager.isMarkedGeneratedCached(file)) {
                    data.clearText()
                    val bgColor = Color(0x3C4CAF50.toInt(), true)
                    val defaultFg = com.intellij.util.ui.UIUtil.getTreeForeground()
                    data.addText(
                        data.presentableText,
                        SimpleTextAttributes(
                            bgColor,
                            defaultFg,
                            null,
                            SimpleTextAttributes.STYLE_OPAQUE,
                        ),
                    )
                    data.forcedTextForeground = defaultFg
                    data.tooltip =
                        """
                        |Compiled class file generated by ANTLR4+
                        |
                        |These .class files are binary bytecode and cannot be edited in IntelliJ.
                        |They are automatically regenerated when grammar changes.
                        |
                        |⚠ Individual files cannot be deleted directly due to JVM file locks.
                        |✓ To delete: Remove the parent 'compiled' folder instead (rm -rf gen/preview/java/compiled).
                        """
                            .trimMargin()
                }
                return
            }

            if (isG4P || isG4) {
                val project = node.project!!
                val service = project.service<PreviewService>()

                if (!service.isEnabled) {
                    return
                }

                val state = service.state
                val status = state.status

                val activeFilePaths = mutableSetOf<String>()
                state.g4pInputFile?.let { activeFilePaths.add(normalizedProjectPath(it.path)) }
                state.grammarFile?.let { activeFilePaths.add(normalizedProjectPath(it.path)) }
                state.lexerGrammarFile?.let { activeFilePaths.add(normalizedProjectPath(it.path)) }
                collectImportedGrammarPaths(state.grammar, activeFilePaths)
                collectImportedGrammarPaths(state.lexerGrammar, activeFilePaths)

                val bindingService = project.service<FileBindingService>()
                val decorationInfo = bindingService.getDecorationInfo(file)

                // Show relative binding paths after conflict checking completes.
                val g4pFolderPath = file.parent?.path
                val showRelativePath =
                    g4pFolderPath != null && CompiledParsingService.hasConfirmedNoConflictsForFolder(g4pFolderPath)
                val suffix: String =
                    if (showRelativePath) decorationInfo.relativePathDecoration else decorationInfo.simpleDecoration
                val customAttributes =
                    SimpleTextAttributes(
                        SimpleTextAttributes.STYLE_PLAIN,
                        com.antlr4plus.color.Color.customColor,
                    )
                data.clearText()
                val parserIndex = suffix.indexOf(" ")

                val warningsService = GrammarWarningsService.getInstance(project)
                val hasErrors = warningsService.getWarningsByFileName(file.name).any { it.isError }

                if (normalizedProjectPath(file.path) in activeFilePaths) {
                    data.background = null
                    data.forcedTextForeground = null

                    val bgColor =
                        when (status) {
                            is ProcessingStatus.Success -> Color(0x3C4CAF50.toInt(), true)
                            is ProcessingStatus.Loading -> Color(0x30808080.toInt(), true)
                            is ProcessingStatus.WaitingForVerification -> Color(0x30FF8C00.toInt(), true)
                            is ProcessingStatus.Cancelled -> Color(0x30808080.toInt(), true)
                            ProcessingStatus.Failed -> Color(0x30E74C3C.toInt(), true)
                            ProcessingStatus.Idle -> Color(0x30808080.toInt(), true)
                        }
                    val defaultFg = com.intellij.util.ui.UIUtil.getTreeForeground()

                    val style =
                        if (hasErrors) {
                            SimpleTextAttributes.STYLE_OPAQUE or SimpleTextAttributes.STYLE_WAVED
                        } else {
                            SimpleTextAttributes.STYLE_OPAQUE
                        }
                    val waveColor = if (hasErrors) JBColor.RED else null

                    val bulletColor =
                        if (service.isAutoRefreshPaused) {
                            Color(0x808080)
                        } else
                            when (status) {
                                is ProcessingStatus.Success -> Color(0x4CAF50)
                                is ProcessingStatus.Loading -> Color(0x808080)
                                is ProcessingStatus.WaitingForVerification -> Color(0x808080)
                                is ProcessingStatus.Cancelled -> Color(0x808080)
                                ProcessingStatus.Failed -> Color(0xE74C3C)
                                ProcessingStatus.Idle -> Color(0x808080)
                            }
                    data.addText(
                        "● ",
                        SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, bulletColor),
                    )

                    data.addText(
                        data.presentableText,
                        SimpleTextAttributes(bgColor, defaultFg, waveColor, style),
                    )
                    data.forcedTextForeground = defaultFg
                } else {
                    if (hasErrors) {
                        data.addText(
                            data.presentableText,
                            SimpleTextAttributes(
                                null,
                                null,
                                JBColor.RED,
                                SimpleTextAttributes.STYLE_WAVED,
                            ),
                        )
                    } else {
                        data.addText(data.presentableText, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    }
                }

                if (isG4P) {
                    if (parserIndex != -1) {
                        val prefix = suffix.substring(0, parserIndex)
                        val parserLabel = " "
                        val parserValue = suffix.substring(parserIndex + parserLabel.length).trim { it <= ' ' }

                        val grammarColor = Color(199, 125, 187)
                        data.addText(prefix, customAttributes)
                        data.addText(parserLabel, customAttributes)
                        data.addText(
                            parserValue,
                            SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, grammarColor),
                        )
                    } else {
                        data.addText(suffix, customAttributes)
                    }

                    // Use the cached binding path to avoid filesystem work during decoration.
                    val boundGrammarPath = decorationInfo.boundGrammarPath
                    if (g4pFolderPath != null && boundGrammarPath != null) {
                        if (
                            CompiledParsingService.hasResolvedConflictsForBinding(
                                g4pFolderPath,
                                boundGrammarPath,
                            )
                        ) {
                            data.addText(
                                " [Resolved]",
                                SimpleTextAttributes(
                                    SimpleTextAttributes.STYLE_PLAIN,
                                    Color(0x808080),
                                ),
                            )
                        }
                    }

                    val settingsService = project.service<SettingsPersistenceService>()
                    val g4pSettings = settingsService.getSettings(file.path)
                    val previewRuntime = g4pSettings.previewRuntime ?: "Java"
                    val runtimeIcon = if (previewRuntime == "Kotlin") Icons.KOTLIN_MINI else Icons.JAVA_MINI

                    val baseIcon = data.getIcon(false) ?: com.antlr4plus.dotG4PSupport.language.FileType.INSTANCE.icon
                    val rowIcon = com.intellij.ui.RowIcon(2)
                    rowIcon.setIcon(baseIcon, 0)
                    rowIcon.setIcon(runtimeIcon, 1)
                    data.setIcon(rowIcon)
                }
            }
        }
    }

    private fun collectImportedGrammarPaths(grammar: Grammar?, activePaths: MutableSet<String>) {
        grammar ?: return
        grammar.getAllImportedGrammars()?.forEach { imp ->
            imp.fileName?.let { path ->
                activePaths.add(normalizedProjectPath(path))
            }
        }
    }
}
