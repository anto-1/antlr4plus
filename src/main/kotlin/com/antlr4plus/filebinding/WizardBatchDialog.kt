package com.antlr4plus.filebinding

import com.antlr4plus.filebinding.BindingWizardHandler.FolderBatch
import com.antlr4plus.util.escapeHtml
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.Action
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities

internal class WizardBatchDialog(
    project: Project,
    private val batch: FolderBatch,
    private val bindingInfo: List<Triple<VirtualFile, VirtualFile?, Boolean>>,
    private val items: List<GrammarPickerItem>,
    currentBatchIndex: Int,
    totalBatches: Int,
    processedFiles: Int,
    totalFiles: Int,
    private val bestGrammarIndex: Int,
) : DialogWrapper(project) {

    var selectedItem: GrammarPickerItem? = null
        private set

    var isSkipped: Boolean = false
        private set

    private val listModel = DefaultListModel<GrammarPickerItem>()
    private val itemList = JBList(listModel)
    private lateinit var grammarScrollPane: JBScrollPane

    init {
        title =
            "Binding Wizard - Folder ${currentBatchIndex + 1}/$totalBatches (${processedFiles + batch.files.size}/$totalFiles files)"
        setOKButtonText("Bind All")
        setCancelButtonText("Abort Wizard")
        init()

        if (bestGrammarIndex >= 0) {
            itemList.selectedIndex = bestGrammarIndex
            // Wait for dialog layout before calculating the centered scroll position.
            SwingUtilities.invokeLater {
                scrollToCenter(bestGrammarIndex)
            }
        }
    }

    private fun scrollToCenter(index: Int) {
        if (index < 0 || index >= listModel.size) return

        val cellBounds = itemList.getCellBounds(index, index) ?: return
        val viewportHeight = grammarScrollPane.viewport.height
        val cellHeight = cellBounds.height

        val targetY = cellBounds.y - (viewportHeight / 2) + (cellHeight / 2)
        val maxY = itemList.height - viewportHeight

        val scrollY = targetY.coerceIn(0, maxY.coerceAtLeast(0))

        grammarScrollPane.viewport.viewPosition = java.awt.Point(0, scrollY)
    }

    override fun createCenterPanel(): JComponent {
        // Fixed dimensions keep controls in the same position between batches.
        val panel = JPanel(BorderLayout(0, 10))
        panel.preferredSize = Dimension(900, 600)

        val headerContainer = JPanel(BorderLayout())
        headerContainer.preferredSize = Dimension(900, 200)
        headerContainer.minimumSize = Dimension(900, 200)
        headerContainer.maximumSize = Dimension(Int.MAX_VALUE, 200)

        val headerPanel = JPanel()
        headerPanel.layout = BoxLayout(headerPanel, BoxLayout.Y_AXIS)

        val folderLabel = JLabel("<html><b>Folder:</b> ${escapeHtml(batch.folder.name)}/</html>")
        val pathLabel = JLabel("<html><small>${escapeHtml(batch.folder.path)}</small></html>")
        headerPanel.add(folderLabel)
        headerPanel.add(pathLabel)
        headerPanel.add(Box.createVerticalStrut(8))

        val filesLabel = JLabel("<html><b>Files (${batch.files.size}):</b></html>")
        headerPanel.add(filesLabel)

        for ((file, binding, isBroken) in bindingInfo) {
            val statusIcon =
                when {
                    isBroken -> "<font color='red'>⚠</font>"
                    binding != null -> "<font color='green'>✓</font>"
                    else -> "<font color='gray'>○</font>"
                }
            val bindingText =
                when {
                    isBroken -> "<font color='red'>(broken: ${binding?.name?.let(::escapeHtml)})</font>"
                    binding != null -> "<font color='gray'>→ ${escapeHtml(binding.name)}</font>"
                    else -> "<font color='gray'>(unbound)</font>"
                }
            val fileEntry = JLabel("<html>  $statusIcon ${escapeHtml(file.name)} $bindingText</html>")
            headerPanel.add(fileEntry)
        }

        val headerScrollPane = JBScrollPane(headerPanel)
        headerScrollPane.border = BorderFactory.createEmptyBorder()
        headerScrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        headerScrollPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER

        headerContainer.add(headerScrollPane, BorderLayout.CENTER)
        panel.add(headerContainer, BorderLayout.NORTH)

        items.forEach { listModel.addElement(it) }

        itemList.cellRenderer =
            object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean,
                ): java.awt.Component {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    when (value) {
                        is GrammarPickerItem.LevelHeader -> {
                            text = value.displayName
                            isEnabled = false
                            background = if (isSelected) list?.selectionBackground else list?.background
                        }
                        is GrammarPickerItem.GrammarFile -> {
                            text = "    ${value.displayName}"
                        }
                        is GrammarPickerItem.BrowseOption -> {
                            text = "Browse for Grammar..."
                        }
                    }
                    return this
                }
            }

        itemList.selectionMode = ListSelectionModel.SINGLE_SELECTION

        itemList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val selected = itemList.selectedValue
                if (selected is GrammarPickerItem.LevelHeader) {
                    val currentIdx = itemList.selectedIndex
                    for (i in (currentIdx + 1) until listModel.size) {
                        val item = listModel.getElementAt(i)
                        if (item !is GrammarPickerItem.LevelHeader) {
                            itemList.selectedIndex = i
                            break
                        }
                    }
                }
            }
        }

        grammarScrollPane = JBScrollPane(itemList)
        grammarScrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS
        panel.add(grammarScrollPane, BorderLayout.CENTER)

        val instructionPanel = JPanel(BorderLayout())
        instructionPanel.preferredSize = Dimension(900, 30)
        instructionPanel.minimumSize = Dimension(900, 30)
        val instructionLabel =
            JLabel(
                "<html><small>Select a grammar to bind all ${batch.files.size} file(s), or click Skip to leave unchanged.</small></html>",
            )
        instructionPanel.add(instructionLabel, BorderLayout.CENTER)
        panel.add(instructionPanel, BorderLayout.SOUTH)

        return panel
    }

    override fun createLeftSideActions(): Array<Action> {
        val skipAction =
            object : DialogWrapperAction("Skip Folder") {
                override fun doAction(e: java.awt.event.ActionEvent?) {
                    isSkipped = true
                    close(OK_EXIT_CODE)
                }
            }
        return arrayOf(skipAction)
    }

    override fun doOKAction() {
        selectedItem = itemList.selectedValue
        super.doOKAction()
    }
}
