package com.antlr4plus.dotG4Support.lineMarker

import com.antlr4plus.dotG4Support.actionInlay.GeneratedActionRegionHighlighter
import com.antlr4plus.filebinding.FileBindingService
import com.antlr4plus.parsing.actionMap.ActionLocationMap
import com.antlr4plus.parsing.actionMap.ActionLocationMapBuilder
import com.antlr4plus.parsing.actionMap.ActionLocationMapService
import com.antlr4plus.parsing.actionMap.GeneratedSyncPreviewRefreshSupport
import com.antlr4plus.parsing.actionMap.actionRangeEndExclusive
import com.antlr4plus.parsing.actionMap.canonicalSourcePath
import com.antlr4plus.parsing.actionMap.containsActionOffset
import com.antlr4plus.parsing.actionMap.generatedSourceExtension
import com.antlr4plus.previewEditor.GeneratedPreviewEditorSupport
import com.antlr4plus.previewService.PreviewService
import com.antlr4plus.settings.GrammarProperties
import com.antlr4plus.settings.SettingsPersistenceService
import com.antlr4plus.util.rethrowIfControlFlow
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement
import java.io.File

internal object GeneratedActionNavigationSupport {

    data class ResolvedNavigation(
        val map: ActionLocationMap,
        val entry: ActionLocationMap.Entry,
    )

    data class ResolvedGrammarNavigation(
        val grammarFile: File,
        val entry: ActionLocationMap.Entry,
        val grammarStartOffset: Int,
        val grammarEndOffsetExclusive: Int,
    )

    fun resolveNavigation(
        project: Project,
        grammarVFile: VirtualFile,
        grammarText: CharSequence,
        grammarOffset: Int,
    ): ResolvedNavigation? {
        val grammarFile = File(grammarVFile.path)
        val service = project.getService(ActionLocationMapService::class.java)

        service?.getAll(grammarFile)?.forEach { map ->
            val entry = findEntry(map, grammarText, grammarOffset)
            if (entry != null && map.generatedFile.exists()) {
                return ResolvedNavigation(map, entry)
            }
        }

        val generatedFiles = findGeneratedFiles(project, grammarVFile)
        for (generatedFile in generatedFiles) {
            service?.getByGeneratedFile(generatedFile)?.let { map ->
                val entry = findEntry(map, grammarText, grammarOffset)
                if (entry != null && map.generatedFile.exists()) {
                    return ResolvedNavigation(map, entry)
                }
            }
        }

        for (generatedFile in generatedFiles) {
            val transientMap =
                ActionLocationMapBuilder.buildFromText(
                    grammarPath = grammarVFile.path,
                    grammarText = grammarText.toString(),
                    generatedFile = generatedFile,
                ) ?: continue

            val entry = findEntry(transientMap, grammarText, grammarOffset) ?: continue
            return ResolvedNavigation(transientMap, entry)
        }

        return null
    }

    fun resolveNavigationFromGenerated(
        project: Project,
        editor: Editor,
        generatedVFile: VirtualFile,
        generatedOffset: Int,
    ): ResolvedGrammarNavigation? {
        findLiveGrammarNavigation(editor, generatedOffset)?.let {
            return it
        }

        val backingFile = GeneratedPreviewEditorSupport.resolveBackingFile(generatedVFile) ?: generatedVFile
        val generatedFile = File(backingFile.path)
        val service = project.getService(ActionLocationMapService::class.java)

        service?.getByGeneratedFile(generatedFile)?.let { map ->
            val entry = map.findByGeneratedOffset(generatedOffset)
            if (entry != null && map.grammarFile.exists()) {
                return ResolvedGrammarNavigation(
                    grammarFile = map.grammarFile,
                    entry = entry,
                    grammarStartOffset = entry.grammarBodyRange.first.coerceAtLeast(0),
                    grammarEndOffsetExclusive = actionRangeEndExclusive(entry.grammarBodyRange),
                )
            }
        }

        val owner = GeneratedSyncPreviewRefreshSupport.resolvePreviewOwner(project, generatedFile) ?: return null
        val grammarText = loadCurrentText(owner.grammarFile) ?: return null
        val transientMap =
            ActionLocationMapBuilder.buildFromText(
                grammarPath = owner.grammarFile.path,
                grammarText = grammarText,
                generatedFile = generatedFile,
            ) ?: return null

        val entry = transientMap.findByGeneratedOffset(generatedOffset) ?: return null
        return ResolvedGrammarNavigation(
            grammarFile = File(owner.grammarFile.path),
            entry = entry,
            grammarStartOffset = entry.grammarBodyRange.first.coerceAtLeast(0),
            grammarEndOffsetExclusive = actionRangeEndExclusive(entry.grammarBodyRange),
        )
    }

    fun findEntry(
        map: ActionLocationMap,
        grammarText: CharSequence,
        grammarOffset: Int,
    ): ActionLocationMap.Entry? {
        map.findByGrammarOffset(grammarOffset)?.let {
            return it
        }

        return when (charAt(grammarText, grammarOffset)) {
            '{',
            '[' -> map.findByGrammarOffset(grammarOffset + 1)
            '}',
            ']' -> map.findByGrammarOffset(grammarOffset - 1)
            else -> null
        }
    }

    private fun findGeneratedFiles(project: Project, grammarVFile: VirtualFile): List<File> {
        val results = linkedMapOf<String, File>()

        findGeneratedFilesFromActivePreview(project, grammarVFile).forEach { file ->
            results[canonicalSourcePath(file.path)] = file
        }
        findGeneratedFilesFromBoundProfiles(project, grammarVFile).forEach { file ->
            results[canonicalSourcePath(file.path)] = file
        }

        return results.values.toList()
    }

    private fun findGeneratedFilesFromActivePreview(
        project: Project,
        grammarVFile: VirtualFile,
    ): List<File> {
        val previewService = project.getService(PreviewService::class.java) ?: return emptyList()
        val state = previewService.state
        val grammarPath = grammarVFile.path

        val previewGrammarPath = state.grammarFile?.path
        val lexerGrammarPath = state.lexerGrammarFile?.path
        val isCurrentPreviewGrammar = grammarPath == previewGrammarPath || grammarPath == lexerGrammarPath
        if (!isCurrentPreviewGrammar) return emptyList()

        val candidateDirs =
            listOfNotNull(
                state.activePreviewSourceDir?.let { dir ->
                    dir to generatedSourceExtension(state.liveSettings?.previewRuntime ?: "Java")
                },
                state.activeUserGeneratedSourceDir?.let { dir ->
                    dir to generatedSourceExtension(state.liveSettings?.language ?: "Java")
                },
            )

        val results = mutableListOf<File>()
        for ((dirPath, extension) in candidateDirs) {
            if (extension == null) continue
            results +=
                findGeneratedFilesInDirectory(
                    File(dirPath),
                    grammarVFile.nameWithoutExtension,
                    extension,
                )
        }

        return results
    }

    private fun findGeneratedFilesFromBoundProfiles(
        project: Project,
        grammarVFile: VirtualFile,
    ): List<File> {
        val bindingService = project.getService(FileBindingService::class.java) ?: return emptyList()
        val settingsService = project.getService(SettingsPersistenceService::class.java) ?: return emptyList()
        val projectRoot = bindingService.getProjectRoot() ?: return emptyList()

        val candidates = mutableListOf<Pair<VirtualFile, File>>()
        VfsUtilCore.visitChildrenRecursively(
            projectRoot,
            object : VirtualFileVisitor<Unit>() {
                override fun visitFile(file: VirtualFile): Boolean {
                    if (file.isDirectory) return true
                    if (!file.name.endsWith(".g4p", ignoreCase = true)) return false

                    val boundGrammar = bindingService.getBoundGrammar(file)
                    if (boundGrammar?.path != grammarVFile.path) return false

                    val persisted = settingsService.getSettings(file.path)
                    val runtime = persisted.previewRuntime ?: "Java"
                    val outputDir = persisted.outputDir ?: "gen"
                    val previewDir = resolvePreviewSourceDir(project, file, outputDir, runtime)
                    val extension = generatedSourceExtension(runtime) ?: return false

                    val generatedFiles =
                        findGeneratedFilesInDirectory(
                            previewDir,
                            grammarVFile.nameWithoutExtension,
                            extension,
                        )
                    if (generatedFiles.isEmpty()) return false
                    generatedFiles.forEach { generatedFile ->
                        candidates += file to generatedFile
                    }
                    return false
                }
            },
        )

        if (candidates.isEmpty()) return emptyList()

        val activeG4pPath =
            project.getService(PreviewService::class.java)?.state?.g4pInputFile?.path?.let(::canonicalSourcePath)

        val ordered = linkedMapOf<String, File>()
        candidates
            .sortedByDescending { canonicalSourcePath(it.first.path) == activeG4pPath }
            .forEach { (_, generatedFile) ->
                ordered[canonicalSourcePath(generatedFile.path)] = generatedFile
            }
        return ordered.values.toList()
    }

    private fun resolvePreviewSourceDir(
        project: Project,
        g4pFile: VirtualFile,
        outputDirSetting: String,
        runtime: String,
    ): File =
        File(
            GrammarProperties.resolvePreviewOutputDir(
                project,
                g4pFile.parent,
                outputDirSetting,
                runtime,
            ),
        )

    private fun findGeneratedFilesInDirectory(
        directory: File,
        grammarName: String,
        extension: String,
    ): List<File> {
        if (!directory.isDirectory) return emptyList()

        val filesByName =
            directory
                .listFiles { file ->
                    file.isFile && file.extension == extension
                }
                ?.associateBy { it.name } ?: return emptyList()

        return listOf(
                "${grammarName}Parser.$extension",
                "${grammarName}Lexer.$extension",
                "$grammarName.$extension",
            )
            .mapNotNull(filesByName::get)
    }

    fun computeNavigationOffset(
        generatedVFile: VirtualFile?,
        generatedFile: File,
        entry: ActionLocationMap.Entry,
    ): Int =
        try {
            val text =
                generatedVFile?.let {
                    FileDocumentManager.getInstance().getDocument(it)?.charsSequence?.toString()
                } ?: generatedFile.readText()

            GeneratedActionNavigationOffsetCalculator.calculate(text, entry.generatedBodyRange)
        } catch (failure: Throwable) {
            rethrowIfControlFlow(failure)
            entry.generatedBodyRange.first.coerceAtLeast(0)
        }

    fun resolveTargetElement(
        project: Project,
        generatedFile: File,
        entry: ActionLocationMap.Entry,
    ): PsiElement? {
        val generatedVFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(generatedFile) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(generatedVFile) ?: return null
        val offset = computeNavigationOffset(generatedVFile, generatedFile, entry)
        val exactOffset = offset.coerceIn(0, psiFile.textLength)
        val directElement = psiFile.findElementAt(exactOffset)

        if (directElement != null && directElement.textRange.startOffset == exactOffset) {
            return directElement
        }

        return ActionNavigationTarget(psiFile, exactOffset)
    }

    fun resolveGrammarTargetElement(
        project: Project,
        grammarFile: File,
        startOffset: Int,
        endOffsetExclusive: Int,
    ): PsiElement? {
        val grammarVFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(grammarFile) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(grammarVFile) ?: return null
        val offset = computeRangeNavigationOffset(grammarVFile, grammarFile, startOffset, endOffsetExclusive)
        val exactOffset = offset.coerceIn(0, psiFile.textLength)
        val directElement = psiFile.findElementAt(exactOffset)

        if (directElement != null && directElement.textRange.startOffset == exactOffset) {
            return directElement
        }

        return ActionNavigationTarget(psiFile, exactOffset)
    }

    fun computeGrammarNavigationOffset(
        grammarVFile: VirtualFile?,
        grammarFile: File,
        startOffset: Int,
        endOffsetExclusive: Int,
    ): Int = computeRangeNavigationOffset(grammarVFile, grammarFile, startOffset, endOffsetExclusive)

    private fun charAt(text: CharSequence, offset: Int): Char? {
        if (offset < 0 || offset >= text.length) return null
        return text[offset]
    }

    private fun findLiveGrammarNavigation(
        editor: Editor,
        generatedOffset: Int,
    ): ResolvedGrammarNavigation? {
        val project = editor.project ?: return null
        val markupModel = DocumentMarkupModel.forDocument(editor.document, project, false) ?: return null
        val highlighter =
            markupModel.allHighlighters.firstOrNull { highlighter ->
                highlighter.getUserData(GeneratedActionRegionHighlighter.OUR_HIGHLIGHTER_KEY) == true &&
                    containsActionOffset(
                        highlighter.startOffset,
                        highlighter.endOffset,
                        generatedOffset,
                    )
            } ?: return null

        val grammarFile = highlighter.getUserData(GeneratedActionRegionHighlighter.GRAMMAR_FILE_KEY) ?: return null
        val grammarMarker = highlighter.getUserData(GeneratedActionRegionHighlighter.GRAMMAR_MARKER_KEY)
        val entry = highlighter.getUserData(GeneratedActionRegionHighlighter.ACTION_ENTRY_KEY) ?: return null
        if (grammarMarker == null || !grammarMarker.isValid) return null

        return ResolvedGrammarNavigation(
            grammarFile = grammarFile,
            entry = entry,
            grammarStartOffset = grammarMarker.startOffset.coerceAtLeast(0),
            grammarEndOffsetExclusive = grammarMarker.endOffset.coerceAtLeast(grammarMarker.startOffset),
        )
    }

    private fun loadCurrentText(file: VirtualFile): String? =
        ApplicationManager.getApplication().runReadAction<String?> {
            FileDocumentManager.getInstance().getDocument(file)?.charsSequence?.toString()
                ?: runCatching { file.inputStream.bufferedReader().use { it.readText() } }.getOrNull()
        }

    private fun computeRangeNavigationOffset(
        vFile: VirtualFile?,
        file: File,
        startOffset: Int,
        endOffsetExclusive: Int,
    ): Int =
        try {
            val text =
                vFile?.let {
                    FileDocumentManager.getInstance().getDocument(it)?.charsSequence?.toString()
                } ?: file.readText()
            RangeNavigationOffsetCalculator.calculate(text, startOffset, endOffsetExclusive)
        } catch (failure: Throwable) {
            rethrowIfControlFlow(failure)
            startOffset.coerceAtLeast(0)
        }
}

internal object GeneratedActionNavigationOffsetCalculator {
    fun calculate(generatedText: String, generatedBodyRange: IntRange): Int =
        RangeNavigationOffsetCalculator.calculate(
            generatedText,
            generatedBodyRange.first.coerceAtLeast(0),
            if (generatedBodyRange.isEmpty()) generatedBodyRange.first.coerceAtLeast(0)
            else generatedBodyRange.last + 1,
        )
}

internal object RangeNavigationOffsetCalculator {
    fun calculate(text: String, startOffset: Int, endOffsetExclusive: Int): Int {
        val start = startOffset.coerceAtLeast(0)
        if (start >= text.length) return text.length

        val endExclusive = endOffsetExclusive.coerceIn(start, text.length)
        if (endExclusive <= start) return start

        val slice = text.substring(start, endExclusive)
        val delta = slice.indexOfFirst { !it.isWhitespace() }
        return if (delta >= 0) start + delta else start
    }
}

private class ActionNavigationTarget(
    private val psiFile: PsiFile,
    private val offset: Int,
) : FakePsiElement(), Navigatable {

    override fun getParent(): PsiElement = psiFile

    override fun getContainingFile(): PsiFile = psiFile

    override fun getNavigationElement(): PsiElement = this

    override fun getTextOffset(): Int = offset

    override fun getName(): String = psiFile.name

    override fun navigate(requestFocus: Boolean) {
        OpenFileDescriptor(psiFile.project, psiFile.virtualFile, offset).navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = true

    override fun canNavigateToSource(): Boolean = true

    override fun toString(): String = "ActionNavigationTarget(${psiFile.name}:$offset)"
}
