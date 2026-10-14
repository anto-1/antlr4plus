package com.antlr4plus.dependencies.ui

import com.antlr4plus.dependencies.UnifiedDependencyResolver
import com.antlr4plus.previewService.PreviewService
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JPanel
import javax.swing.SwingConstants

/** Displays the active grammar's resolved dependencies. */
class DependencyGraphPane(
    private val project: Project,
    parentDisposable: Disposable,
) : JPanel(BorderLayout()), Disposable {

    private var currentTreePanel: DependencyTreePanel? = null
    private var currentGrammarPath: String? = null
    private var currentCharset: Charset = Charsets.UTF_8
    private val updateGeneration = AtomicLong()
    @Volatile private var disposed = false
    private val emptyLabel: JBLabel

    init {
        Disposer.register(parentDisposable, this)
        border = JBUI.Borders.empty(4)

        emptyLabel =
            JBLabel(
                "<html><center>" +
                    "<p style='color: #808080;'>No grammar loaded</p>" +
                    "<p style='color: #606060; font-size: 11px;'>Open a grammar file to see its dependency graph</p>" +
                    "</center></html>",
                SwingConstants.CENTER,
            )
        emptyLabel.horizontalAlignment = SwingConstants.CENTER
        add(emptyLabel, BorderLayout.CENTER)

        val connection = project.messageBus.connect(this)
        connection.subscribe(
            PreviewService.FILE_SWITCHED_TOPIC,
            object : PreviewService.FileSwitchedListener {
                override fun onFileSwitched(previewState: com.antlr4plus.state.PreviewState) {
                    updateForGrammar(previewState.grammarFile?.path, grammarCharset(previewState))
                }
            },
        )

        val previewService = project.getService(PreviewService::class.java)
        previewService?.state?.grammarFile?.path?.let {
            updateForGrammar(it, grammarCharset(previewService.state))
        }
    }

    private fun updateForGrammar(grammarPath: String?, charset: Charset = Charsets.UTF_8) {
        if (grammarPath == currentGrammarPath && charset == currentCharset) return
        currentGrammarPath = grammarPath
        currentCharset = charset
        val generation = updateGeneration.incrementAndGet()

        currentTreePanel?.let {
            remove(it)
            currentTreePanel = null
        }

        if (grammarPath == null) {
            if (emptyLabel.parent == null) {
                add(emptyLabel, BorderLayout.CENTER)
            }
            revalidate()
            repaint()
            return
        }

        if (emptyLabel.parent != null) {
            remove(emptyLabel)
        }

        revalidate()
        repaint()

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val grammarFile = File(grammarPath)
                require(grammarFile.isFile) { "Grammar file does not exist" }
                UnifiedDependencyResolver.resolve(
                    grammarFile,
                    project,
                    grammarCharset = charset,
                )
            }

            ApplicationManager.getApplication().invokeLater {
                if (
                    disposed ||
                        project.isDisposed ||
                        updateGeneration.get() != generation ||
                        currentGrammarPath != grammarPath
                )
                    return@invokeLater

                if (emptyLabel.parent != null) remove(emptyLabel)
                result.fold(
                    onSuccess = { graph ->
                        val treePanel = DependencyTreePanel.create(project, graph)
                        currentTreePanel = treePanel
                        add(treePanel, BorderLayout.CENTER)
                    },
                    onFailure = { error ->
                        val errorLabel =
                            JBLabel(
                                "<html><center>" +
                                    "<p style='color: #F44336;'>Failed to resolve dependencies</p>" +
                                    "<p style='color: #808080; font-size: 11px;'>${error.message ?: "Unknown error"}</p>" +
                                    "</center></html>",
                                SwingConstants.CENTER,
                            )
                        errorLabel.horizontalAlignment = SwingConstants.CENTER
                        add(errorLabel, BorderLayout.CENTER)
                    },
                )
                revalidate()
                repaint()
            }
        }
    }

    /** Recomputes even when the grammar path and charset are unchanged. */
    fun refresh() {
        val path = currentGrammarPath
        currentGrammarPath = null
        updateForGrammar(path, currentCharset)
    }

    override fun dispose() {
        disposed = true
        updateGeneration.incrementAndGet()
        currentTreePanel = null
    }

    private fun grammarCharset(state: com.antlr4plus.state.PreviewState): Charset =
        state.liveSettings?.encoding?.takeIf { it.isNotBlank() }?.let(Charset::forName) ?: Charsets.UTF_8

    companion object {
        fun create(project: Project, parentDisposable: Disposable): DependencyGraphPane =
            DependencyGraphPane(project, parentDisposable)
    }
}
