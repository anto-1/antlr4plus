package com.antlr4plus.parsing

import com.antlr4plus.compat.compatRestart
import com.antlr4plus.previewService.PreviewService
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.antlr.v4.tool.ANTLRMessage

/** Stores ANTLR diagnostics for editor inspections, keyed by reported file path. */
@Service(Service.Level.PROJECT)
class GrammarWarningsService(private val project: Project) : Disposable {

    private val cs = CoroutineScope(Dispatchers.Default + SupervisorJob())

    data class GrammarWarning(
        val filePath: String,
        val line: Int,
        val column: Int,
        val message: String,
        val isError: Boolean,
        val errorCode: Int,
    )

    private val warningsByFile = ConcurrentHashMap<String, ConcurrentLinkedQueue<GrammarWarning>>()

    /** Clears entries matching either the full path or its filename. */
    fun clearWarnings(filePath: String) {
        warningsByFile.remove(filePath)

        val fileName = filePath.substringAfterLast('/').substringAfterLast('\\')
        warningsByFile.keys.removeIf { key ->
            key == fileName || key.endsWith("/$fileName") || key.endsWith("\\$fileName")
        }
    }

    fun addWarning(warning: GrammarWarning) {
        warningsByFile.computeIfAbsent(warning.filePath) { ConcurrentLinkedQueue() }.add(warning)
    }

    fun addFromAntlrMessage(msg: ANTLRMessage, renderedMessage: String, isError: Boolean) {
        val fileName = msg.fileName ?: return
        val line = msg.line
        val column = msg.charPosition
        val errorCode = msg.errorType?.code ?: 0

        val cleanMessage = extractMessagePart(renderedMessage)

        addWarning(
            GrammarWarning(
                filePath = fileName,
                line = line,
                column = column,
                message = cleanMessage,
                isError = isError,
                errorCode = errorCode,
            ),
        )
    }

    /** Removes a type(code): file:line:column prefix when recognized. */
    private fun extractMessagePart(fullMessage: String): String {
        val regex =
            Regex(
                """^(?:warning|error)\(\d+\):\s*[^:]+:\d+:\d+:\s*(.+)$""",
                RegexOption.DOT_MATCHES_ALL,
            )
        val match = regex.find(fullMessage)
        return match?.groupValues?.get(1)?.trim() ?: fullMessage
    }

    fun getWarnings(filePath: String): List<GrammarWarning> = warningsByFile[filePath]?.toList() ?: emptyList()

    fun getWarningsByFileName(fileName: String): List<GrammarWarning> =
        warningsByFile.entries
            .filter {
                it.key.endsWith("/$fileName") || it.key.endsWith("\\$fileName") || it.key == fileName
            }
            .flatMap { it.value }

    fun clearAll() {
        warningsByFile.clear()
    }

    /** Refreshes inspections and project-tree decorations after resolving the reported path or filename. */
    fun triggerReAnnotation(filePath: String) {
        cs.launch {
            val virtualFile = findGrammarFile(filePath) ?: return@launch

            val psiFile =
                readAction {
                    PsiManager.getInstance(project).findFile(virtualFile)
                } ?: return@launch

            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) {
                    DaemonCodeAnalyzer.getInstance(project).compatRestart(psiFile, "ANTLR4+ grammar warnings updated")
                    ProjectView.getInstance(project).refresh()
                }
            }
        }
    }

    private fun findGrammarFile(filePathOrName: String): VirtualFile? {
        LocalFileSystem.getInstance().findFileByPath(filePathOrName)?.let {
            return it
        }

        val previewService = project.getService(PreviewService::class.java)
        val grammarFile = previewService?.state?.grammarFile

        if (grammarFile != null) {
            val parentDir = grammarFile.parent
            parentDir?.findChild(filePathOrName)?.let {
                return it
            }
        }

        val basePath = project.basePath ?: return null
        val searchPaths =
            listOf(
                "$basePath/$filePathOrName",
                "$basePath/src/main/antlr4/$filePathOrName",
                "$basePath/src/main/antlr/$filePathOrName",
                "$basePath/grammar/$filePathOrName",
                "$basePath/grammars/$filePathOrName",
            )

        for (path in searchPaths) {
            LocalFileSystem.getInstance().findFileByPath(path)?.let {
                return it
            }
        }

        val projectBaseDir = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return null
        return findFileRecursively(projectBaseDir, filePathOrName)
    }

    private fun findFileRecursively(dir: VirtualFile, fileName: String): VirtualFile? {
        for (child in dir.children) {
            if (child.isDirectory) {
                if (child.name in listOf("build", "out", ".gradle", ".idea", "node_modules")) continue
                val found = findFileRecursively(child, fileName)
                if (found != null) return found
            } else if (child.name == fileName) {
                return child
            }
        }
        return null
    }

    fun triggerAllReAnnotations() {
        for (filePath in warningsByFile.keys) {
            triggerReAnnotation(filePath)
        }
    }

    override fun dispose() {
        cs.cancel()
        warningsByFile.clear()
    }

    companion object {
        fun getInstance(project: Project): GrammarWarningsService =
            project.getService(GrammarWarningsService::class.java)
    }
}
