package com.antlr4plus.toolWindow

import com.antlr4plus.about.AboutDialog
import com.antlr4plus.compose.ComposeCompat
import com.antlr4plus.compose.ComposeFallbackLoader
import com.antlr4plus.context.ContextAggregator
import com.antlr4plus.dependencies.ui.DependencyGraphPane
import com.antlr4plus.error.ComposeAntlrErrorConsolePanel
import com.antlr4plus.error.ComposeCompilerConsolePanel
import com.antlr4plus.error.ComposeParsingErrorConsolePanel
import com.antlr4plus.feedback.FeedbackDialog
import com.antlr4plus.icons.Icons
import com.antlr4plus.preview.hierarchy.HierarchyViewer
import com.antlr4plus.preview.tree.UberTreeViewer
import com.antlr4plus.previewService.PreviewService
import com.antlr4plus.state.PreviewState
import com.antlr4plus.state.ProcessingStatus
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.content.ContentFactory
import com.intellij.util.IconUtil
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.*
import org.antlr.v4.tool.Grammar

class TopRightToolWindow : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        project.getService(PreviewService::class.java).toolWindow = toolWindow

        val contentFactory = ContentFactory.getInstance()
        val previewService = project.getService(PreviewService::class.java)

        val titleManager = ToolWindowTitleManager(toolWindow)

        val loadingPanel = LoadingOverlayPanel()
        loadingPanel.layout = BorderLayout()
        loadingPanel.setOnCancelCallback {
            previewService.cancelCurrentJob()
        }

        val cardPanel = JPanel(CardLayout())

        val toolbarContainer = JPanel()
        toolbarContainer.layout = BoxLayout(toolbarContainer, BoxLayout.Y_AXIS)

        val toolbarPanel = FadingToolbarPanel(fadeZoneWidth = 24)
        toolbarPanel.alignmentX = Component.LEFT_ALIGNMENT

        val statusRow = JPanel()
        statusRow.layout = BoxLayout(statusRow, BoxLayout.X_AXIS)
        statusRow.alignmentX = Component.LEFT_ALIGNMENT

        // Load bundled Compose support before creating panels when the IDE needs the fallback.
        ComposeFallbackLoader.ensureComposeAvailable()

        val profilerPanelComponent: JComponent
        val tokenTimelinePanelComponent: JComponent
        val settingsPanel: JPanel

        if (ComposeCompat.isComposeAvailable) {
            val composePanels = ComposeCompat.createComposePanels(project, toolWindow.disposable)

            @Suppress("UNCHECKED_CAST")
            previewService.composeProfilerPanel =
                composePanels.profilerPanel as? com.antlr4plus.statistics.ComposeProfilerPanel
            @Suppress("UNCHECKED_CAST")
            previewService.tokenTimelinePanel =
                composePanels.timelinePanel as? com.antlr4plus.timeline.ComposeTokenTimelinePanel

            profilerPanelComponent = composePanels.profilerComponent
            tokenTimelinePanelComponent = composePanels.timelineComponent
            settingsPanel = composePanels.settingsPanel
        } else {
            profilerPanelComponent = createFallbackPanel("Profiler requires Compose support")
            tokenTimelinePanelComponent = createFallbackPanel("Lexer Timeline requires Compose support")
            settingsPanel = createFallbackPanel("Settings requires Compose support")
        }

        val (uber, uberPane) = UberTreeViewer.createUberPanel(project, toolWindow.disposable)
        previewService.uber = uber

        val hierarchyPanel = HierarchyViewer(project, null, toolWindow.disposable)
        previewService.hierarchy = hierarchyPanel

        val bindingEditorPanel = BindingEditorPane.createPane(project, toolWindow.disposable)

        val dependencyGraphPane = DependencyGraphPane.create(project, toolWindow.disposable)

        val errorConsolePanel = ComposeParsingErrorConsolePanel(project, toolWindow.disposable)

        val antlrConsolePanel = ComposeAntlrErrorConsolePanel(project, toolWindow.disposable)
        previewService.antlrConsolePanel = antlrConsolePanel

        val compilerConsolePanel = ComposeCompilerConsolePanel(project, toolWindow.disposable)
        previewService.compilerConsolePanel = compilerConsolePanel

        val runtimeConsolePanel = com.antlr4plus.error.ComposeRuntimeConsolePanel(project, toolWindow.disposable)
        previewService.runtimeConsolePanel = runtimeConsolePanel

        cardPanel.add(profilerPanelComponent, "Profiler")
        cardPanel.add(tokenTimelinePanelComponent, "Lexer")
        cardPanel.add(uberPane, "Tree")
        cardPanel.add(hierarchyPanel, "Hierarchy")
        cardPanel.add(dependencyGraphPane, "Dependencies")
        cardPanel.add(settingsPanel, "Settings")
        cardPanel.add(bindingEditorPanel, "Import/Export")
        cardPanel.add(errorConsolePanel.getComponent(), "ParsingConsole")
        cardPanel.add(antlrConsolePanel.getComponent(), "AntlrConsole")
        cardPanel.add(compilerConsolePanel.getComponent(), "CompilerConsole")
        cardPanel.add(runtimeConsolePanel.getComponent(), "RuntimeConsole")

        val globalToggle = createGlobalToggle(previewService, loadingPanel)
        toolbarPanel.add(globalToggle)

        loadingPanel.addExcludedComponent(globalToggle)

        val buttonGroup = ButtonGroup()
        val buttons = mutableListOf<JToggleButton>()

        val defaultPaneName = "Profiler"

        val parserOnlyPanes = setOf("Profiler", "Tree", "Hierarchy")

        val fadingToast = FadingToast()

        val iconScale = 1.15f

        buttons.add(
            createIconButton(
                "Profiler",
                IconUtil.scale(AllIcons.Toolwindows.ToolWindowProfiler, null, iconScale),
                cardPanel,
                previewService,
                buttonGroup,
                titleManager,
                fadingToast,
            ),
        )
        buttons.add(
            createIconButton(
                "Lexer",
                ItalicLetterIcon("L", 16),
                cardPanel,
                previewService,
                buttonGroup,
                titleManager,
            ),
        )
        buttons.add(
            createIconButton(
                "Tree",
                IconUtil.scale(AllIcons.Hierarchy.Subtypes, null, iconScale),
                cardPanel,
                previewService,
                buttonGroup,
                titleManager,
                fadingToast,
            ),
        )
        buttons.add(
            createIconButton(
                "Hierarchy",
                IconUtil.scale(AllIcons.Actions.ShowAsTree, null, iconScale),
                cardPanel,
                previewService,
                buttonGroup,
                titleManager,
                fadingToast,
            ),
        )
        buttons.add(
            createIconButton(
                "Dependencies",
                IconUtil.scale(AllIcons.Toolwindows.ToolWindowModuleDependencies, null, iconScale),
                cardPanel,
                previewService,
                buttonGroup,
                titleManager,
            ),
        )
        buttons.add(
            createIconButton(
                "Settings",
                IconUtil.scale(AllIcons.General.Settings, null, iconScale),
                cardPanel,
                previewService,
                buttonGroup,
                titleManager,
                exemptFromPluginDisable = true,
            ),
        )
        buttons.add(
            createIconButton(
                "Import/Export",
                Icons.DATAFLOW,
                cardPanel,
                previewService,
                buttonGroup,
                titleManager,
                exemptFromPluginDisable = true,
            ),
        )

        val parsingConsoleButton =
            createBadgedIconButton(
                "ParsingConsole",
                ConsoleLetterIcon("P", 16),
                cardPanel,
                previewService,
                buttonGroup,
                errorConsolePanel,
                titleManager,
            )
        buttons.add(parsingConsoleButton)

        val antlrConsoleButton =
            createBadgedIconButton(
                "AntlrConsole",
                ConsoleLetterIcon("A", 16),
                cardPanel,
                previewService,
                buttonGroup,
                antlrConsolePanel,
                titleManager,
            )
        buttons.add(antlrConsoleButton)

        val compilerConsoleButton =
            createBadgedIconButton(
                "CompilerConsole",
                ConsoleLetterIcon("C", 16),
                cardPanel,
                previewService,
                buttonGroup,
                compilerConsolePanel,
                titleManager,
            )
        buttons.add(compilerConsoleButton)

        val runtimeConsoleButton =
            createBadgedIconButton(
                "RuntimeConsole",
                ConsoleLetterIcon("R", 16),
                cardPanel,
                previewService,
                buttonGroup,
                runtimeConsolePanel,
                titleManager,
            )
        buttons.add(runtimeConsoleButton)

        buttons.forEach { toolbarPanel.add(it) }

        val contextAggregator = ContextAggregator(project)
        val copyContextButton =
            createActionButton(
                "Copy Context to Clipboard",
                AllIcons.Actions.Copy,
            ) {
                contextAggregator.showDialogAndCopy()
            }
        toolbarPanel.add(copyContextButton)

        toolbarPanel.add(Box.createHorizontalStrut(8))
        fadingToast.preferredSize = Dimension(150, ToolWindowTheme.BUTTON_SIZE)
        toolbarPanel.add(fadingToast)

        titleManager.setTitle(ToolWindowTitle.build("Profiler", previewService.state))
        buttons.firstOrNull()?.let { firstButton ->
            firstButton.isSelected = true
            firstButton.putClientProperty("activePane", true)
            firstButton.repaint()
        }

        val statusLabelRenderer = StatusLabelRenderer()

        val statusLabel = JLabel("Ready")
        statusLabel.preferredSize = Dimension(150, ToolWindowTheme.BUTTON_SIZE)
        statusLabel.maximumSize = Dimension(Short.MAX_VALUE.toInt(), ToolWindowTheme.BUTTON_SIZE)
        statusLabel.minimumSize = Dimension(0, ToolWindowTheme.BUTTON_SIZE)
        statusLabel.horizontalAlignment = SwingConstants.LEFT
        statusLabel.border = ToolWindowTheme.createRoundedBorder(padding = 8, margin = 0)
        statusLabel.isFocusable = false

        loadingPanel.addExcludedComponent(statusLabel)

        var currentRegions = listOf<Region>()
        val statusUpdateGeneration = AtomicInteger()

        fun scheduleStatusLabelUpdate(state: PreviewState) {
            val generation = statusUpdateGeneration.incrementAndGet()
            currentRegions = statusLabelRenderer.update(state, statusLabel)
            val parserGrammar = state.grammar
            val lexerGrammar = state.lexerGrammar
            ApplicationManager.getApplication().executeOnPooledThread {
                if (project.isDisposed) return@executeOnPooledThread
                val importedFiles = mutableSetOf<VirtualFile>()
                collectImportedGrammarFiles(parserGrammar, importedFiles)
                collectImportedGrammarFiles(lexerGrammar, importedFiles)
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || statusUpdateGeneration.get() != generation) return@invokeLater
                    currentRegions = statusLabelRenderer.update(state, statusLabel, importedFiles)
                }
            }
        }

        scheduleStatusLabelUpdate(previewService.state)

        fun updateTooltipAndCursor(x: Int) {
            when (currentRegions.firstOrNull { x in it.startX..it.endX }) {
                is FileRegion -> {
                    statusLabel.toolTipText = "Click the file to locate it in the Project Tree."
                    statusLabel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                }
                else -> {
                    statusLabel.toolTipText = null
                    statusLabel.cursor = Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)
                }
            }
        }

        statusLabel.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val clickX = e.x
                    val clickedRegion = currentRegions.firstOrNull { clickX in it.startX..it.endX }

                    if (clickedRegion is FileRegion) {
                        ProjectView.getInstance(project).select(null, clickedRegion.file, true)
                        FileEditorManager.getInstance(project).openFile(clickedRegion.file, true)
                    }
                }

                override fun mouseEntered(e: MouseEvent) {
                    updateTooltipAndCursor(e.x)
                }
            },
        )

        statusLabel.addMouseMotionListener(
            object : MouseMotionAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    updateTooltipAndCursor(e.x)
                }
            },
        )

        val connection = project.messageBus.connect(toolWindow.disposable)
        var lastSwitchedFilePath: String? = null
        connection.subscribe(
            PreviewService.FILE_SWITCHED_TOPIC,
            object : PreviewService.FileSwitchedListener {
                override fun onFileSwitched(previewState: PreviewState) {
                    val newPath = previewState.g4pInputFile?.path
                    if (newPath != lastSwitchedFilePath) {
                        previewService.tokenTimelinePanel?.clearHighlightingState()
                        lastSwitchedFilePath = newPath
                    }

                    scheduleStatusLabelUpdate(previewState)
                    parsingConsoleButton.repaint()
                    antlrConsoleButton.repaint()
                    compilerConsoleButton.repaint()
                    runtimeConsoleButton.repaint()

                    val activeButton = buttons.find { it.getClientProperty("activePane") == true }
                    val paneName = activeButton?.toolTipText?.split(" ")?.firstOrNull() ?: defaultPaneName
                    titleManager.setTitle(ToolWindowTitle.build(paneName, previewState))
                }
            },
        )
        // Keep controls available while dependency resolution awaits user input.
        var dependencyResolutionActive = false
        var dependencyResolutionProcessingId: Long? = null

        connection.subscribe(
            PreviewService.PARSING_COMPLETION_TOPIC,
            object : PreviewService.ParsingCompletionListener {
                override fun onParsingCompleted(previewState: PreviewState) {
                    scheduleStatusLabelUpdate(previewState)
                    parsingConsoleButton.repaint()
                    antlrConsoleButton.repaint()
                    compilerConsoleButton.repaint()
                    runtimeConsoleButton.repaint()

                    // Logical disabling preserves clicks so buttons can explain why a pane is
                    // unavailable.
                    val isLexerOnly = previewState.isLexerOnlyMode
                    buttons.forEach { button ->
                        val buttonPaneName = button.toolTipText?.split(" ")?.firstOrNull()
                        if (buttonPaneName in parserOnlyPanes) {
                            button.putClientProperty("isLogicallyDisabled", isLexerOnly)
                            button.repaint()
                        }
                    }

                    var activeButton = buttons.find { it.getClientProperty("activePane") == true }
                    val activePaneName = activeButton?.toolTipText?.split(" ")?.firstOrNull() ?: defaultPaneName
                    if (isLexerOnly && activePaneName in parserOnlyPanes) {
                        val lexerButton = buttons.find { it.toolTipText == "Lexer" }
                        lexerButton?.doClick()
                        activeButton = lexerButton
                    }

                    val paneName = activeButton?.toolTipText?.split(" ")?.firstOrNull() ?: defaultPaneName
                    titleManager.setTitle(ToolWindowTitle.build(paneName, previewState))

                    if (previewState.status is ProcessingStatus.Success) {
                        loadingPanel.triggerSuccessAnimation()
                    }

                    if (!dependencyResolutionActive) {
                        buttons.forEach {
                            it.putClientProperty("isOverlayLoading", false)
                            it.repaint()
                        }
                    }
                }
            },
        )
        connection.subscribe(
            PreviewService.STATUS_UPDATE_TOPIC,
            object : PreviewService.StatusUpdateListener {
                override fun onStatusUpdate(message: String, isLoading: Boolean) {
                    uber.treeViewer.isLoading = isLoading && message.isNotEmpty()
                    if (isLoading && message.isNotEmpty()) {
                        statusUpdateGeneration.incrementAndGet()
                        loadingPanel.showLoadingStatus(message)
                        statusLabel.text = "<html><nobr><span style='color:#808080;'>Processing...</span></nobr></html>"
                        buttons.forEach {
                            it.putClientProperty("isOverlayLoading", true)
                            it.repaint()
                        }
                    } else if (!dependencyResolutionActive) {
                        loadingPanel.hideLoadingStatus()
                        scheduleStatusLabelUpdate(previewService.state)
                        buttons.forEach {
                            it.putClientProperty("isOverlayLoading", false)
                            it.repaint()
                        }
                    }
                }
            },
        )
        connection.subscribe(
            PreviewService.SWITCH_PANE_TOPIC,
            object : PreviewService.SwitchPaneListener {
                override fun onSwitchPane(paneName: String) {
                    val targetButton = buttons.find { it.toolTipText?.startsWith(paneName) == true }
                    targetButton?.doClick()
                }
            },
        )
        connection.subscribe(
            PreviewService.PLUGIN_TOGGLE_TOPIC,
            object : PreviewService.PluginToggleListener {
                override fun onPluginToggled(enabled: Boolean) {
                    if (!enabled) {
                        statusUpdateGeneration.incrementAndGet()
                        statusLabel.text =
                            "<html><nobr><span style='color:#808080;'>Preview Disabled</span></nobr></html>"
                        currentRegions = emptyList()

                        previewService.tokenTimelinePanel?.clearHighlightingState()

                        buttons.forEach { button ->
                            button.putClientProperty("activePane", false)
                            button.putClientProperty("isPluginDisabled", true)
                            button.putClientProperty("isOverlayLoading", false)
                            button.repaint()
                        }

                        titleManager.setTitle("")
                    } else {
                        buttons.forEach { button ->
                            button.putClientProperty("isPluginDisabled", false)
                            button.repaint()
                        }

                        buttons
                            .find { it.isSelected }
                            ?.let { selectedButton ->
                                selectedButton.putClientProperty("activePane", true)
                                selectedButton.repaint()
                            }
                    }
                }
            },
        )
        connection.subscribe(
            PreviewService.DEPENDENCY_RESOLUTION_TOPIC,
            object : PreviewService.DependencyResolutionListener {
                override fun onResolutionNeeded(
                    processingId: Long,
                    message: String,
                    onResolveClicked: () -> Unit,
                ) {
                    statusUpdateGeneration.incrementAndGet()
                    dependencyResolutionActive = true
                    dependencyResolutionProcessingId = processingId
                    loadingPanel.showLoadingStatus(message)
                    statusLabel.text =
                        "<html><nobr><span style='color:#808080;'>Resolving dependencies...</span></nobr></html>"
                    loadingPanel.setLoadingCancellable(false)
                    loadingPanel.showActionButton("Resolve", onResolveClicked)
                }

                override fun onResolutionCompleted(processingId: Long) {
                    if (!dependencyResolutionActive || dependencyResolutionProcessingId != processingId) return
                    dependencyResolutionActive = false
                    dependencyResolutionProcessingId = null
                    statusUpdateGeneration.incrementAndGet()
                    loadingPanel.hideActionButton()
                    buttons.forEach {
                        it.putClientProperty("isOverlayLoading", false)
                        it.repaint()
                    }
                    if (previewService.state.status == ProcessingStatus.Loading) {
                        statusLabel.text = "<html><nobr><span style='color:#808080;'>Processing...</span></nobr></html>"
                        // Keep the processing message visible between resolution and parsing to
                        // avoid flicker.
                        loadingPanel.showLoadingStatus("Processing...")
                    } else {
                        loadingPanel.hideLoadingStatus()
                        scheduleStatusLabelUpdate(previewService.state)
                    }
                }
            },
        )
        connection.subscribe(
            PreviewService.JOB_CANCELLED_TOPIC,
            object : PreviewService.JobCancelledListener {
                override fun onJobCancelled() {
                    loadingPanel.hideLoadingStatus()
                }
            },
        )

        connection.subscribe(
            com.intellij.ide.ui.LafManagerListener.TOPIC,
            com.intellij.ide.ui.LafManagerListener {
                val activeButton = buttons.find { it.getClientProperty("activePane") == true }
                val paneName = activeButton?.toolTipText?.split(" ")?.firstOrNull() ?: defaultPaneName
                titleManager.setTitle(ToolWindowTitle.build(paneName, previewService.state))
                scheduleStatusLabelUpdate(previewService.state)
                toolbarPanel.repaint()
                statusRow.repaint()
            },
        )

        toolbarPanel.add(Box.createHorizontalGlue())

        val githubButton =
            createActionButton(
                "View ANTLR4+ on GitHub",
                AllIcons.Vcs.Vendors.Github,
            ) {
                BrowserUtil.browse("https://github.com/anto-1/antlr4plus")
            }
        toolbarPanel.add(githubButton)

        val feedbackButton =
            createActionButton(
                "Leave Feedback",
                AllIcons.General.Balloon,
            ) {
                FeedbackDialog(project).show()
            }
        toolbarPanel.add(feedbackButton)

        val helpButton =
            createActionButton(
                "Help & Getting Started",
                AllIcons.Actions.Help,
            ) {
                com.antlr4plus.onboarding.OnboardingDialog.showAlways(project)
            }
        toolbarPanel.add(helpButton)

        val aboutButton =
            createActionButton(
                "About ANTLR4+",
                AllIcons.Actions.More,
            ) {
                AboutDialog.show(project)
            }
        toolbarPanel.add(aboutButton)

        // Avoid feeding programmatic toggle updates back into the service.
        var isUpdatingToggleProgrammatically = false
        val autoRefreshToggle = createAutoRefreshToggle(previewService) { isUpdatingToggleProgrammatically }
        statusRow.add(autoRefreshToggle)

        val runtimeIndicator = PreviewRuntimeIndicator(previewService, project)
        statusRow.add(runtimeIndicator)
        var runtimeChangeProcessingId: Long? = null

        connection.subscribe(
            PreviewService.RUNTIME_CHANGE_PENDING_TOPIC,
            object : PreviewService.RuntimeChangePendingListener {
                override fun onRuntimeChangePending(processingId: Long, newRuntime: String) {
                    runtimeChangeProcessingId = processingId
                    runtimeIndicator.showPendingRuntime(newRuntime)
                }

                override fun onRuntimeChangeCompleted(processingId: Long) {
                    if (runtimeChangeProcessingId != processingId) return
                    runtimeChangeProcessingId = null
                    runtimeIndicator.finishPendingRuntime()
                }
            },
        )

        connection.subscribe(
            PreviewService.PARSING_COMPLETION_TOPIC,
            object : PreviewService.ParsingCompletionListener {
                override fun onParsingCompleted(previewState: PreviewState) {
                    runtimeChangeProcessingId = null
                    runtimeIndicator.confirmRuntime(previewState)
                }
            },
        )

        connection.subscribe(
            PreviewService.FILE_SWITCHED_TOPIC,
            object : PreviewService.FileSwitchedListener {
                override fun onFileSwitched(previewState: PreviewState) {
                    if (runtimeChangeProcessingId == null) {
                        runtimeIndicator.confirmRuntime(previewState)
                    }
                }
            },
        )

        connection.subscribe(
            PreviewService.AUTO_REFRESH_TOGGLE_TOPIC,
            object : PreviewService.AutoRefreshToggleListener {
                override fun onAutoRefreshToggled(enabled: Boolean) {
                    isUpdatingToggleProgrammatically = true
                    try {
                        autoRefreshToggle.isSelected = !enabled
                        autoRefreshToggle.toolTipText =
                            if (!enabled) "Resume automatic refresh" else "Pause automatic refresh"
                    } finally {
                        isUpdatingToggleProgrammatically = false
                    }
                }
            },
        )

        connection.subscribe(
            PreviewService.PLUGIN_TOGGLE_TOPIC,
            object : PreviewService.PluginToggleListener {
                override fun onPluginToggled(enabled: Boolean) {
                    isUpdatingToggleProgrammatically = true
                    try {
                        autoRefreshToggle.isEnabled = enabled
                        if (!enabled) {
                            autoRefreshToggle.isSelected = false
                            autoRefreshToggle.toolTipText = "Automatic refresh (plugin disabled)"
                        } else {
                            autoRefreshToggle.isSelected = previewService.isAutoRefreshPaused
                            autoRefreshToggle.toolTipText =
                                if (previewService.isAutoRefreshPaused) "Resume automatic refresh"
                                else "Pause automatic refresh"
                        }
                    } finally {
                        isUpdatingToggleProgrammatically = false
                    }
                }
            },
        )

        statusRow.add(statusLabel)

        toolbarContainer.add(toolbarPanel)
        toolbarContainer.add(statusRow)

        loadingPanel.add(toolbarContainer, BorderLayout.NORTH)
        loadingPanel.add(cardPanel, BorderLayout.CENTER)

        val masterContent = contentFactory.createContent(loadingPanel, "", false)
        toolWindow.contentManager.addContent(masterContent)

        previewService.antlrConsoleContent = masterContent

        // Clear editor highlights when the tool window is disposed.
        com.intellij.openapi.util.Disposer.register(toolWindow.disposable) {
            previewService.tokenTimelinePanel?.clearHighlightingState()
        }

        buttons.firstOrNull()?.doClick()
    }

    private fun collectImportedGrammarFiles(
        grammar: Grammar?,
        importedFiles: MutableSet<VirtualFile>,
    ) {
        grammar ?: return
        grammar.getAllImportedGrammars()?.forEach { imp ->
            imp.fileName?.let { path ->
                LocalFileSystem.getInstance().findFileByPath(path)?.let { vf ->
                    importedFiles.add(vf)
                }
            }
        }
    }

    private fun createFallbackPanel(message: String): JPanel {
        val panel = JPanel(BorderLayout())
        val label = JLabel(message, SwingConstants.CENTER)
        label.foreground = JBColor.GRAY
        panel.add(label, BorderLayout.CENTER)
        return panel
    }
}
