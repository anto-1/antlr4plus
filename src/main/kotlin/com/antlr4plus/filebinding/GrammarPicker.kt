package com.antlr4plus.filebinding

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.vfs.VirtualFile

internal sealed interface GrammarPickerItem {
    /** Non-selectable location header. */
    data class LevelHeader(val level: Int, val isSibling: Boolean = false) : GrammarPickerItem {
        val displayName: String =
            when {
                isSibling -> "── ↔ sibling folder ──"
                level < -1 -> "── ↑↑ ${-level} levels up ──"
                level == -1 -> "── ↑ 1 level up ──"
                level == 0 -> "── ● Same folder ──"
                level == 1 -> "── ↓ 1 level down ──"
                else -> "── ↓↓ $level levels down ──"
            }
    }

    data class GrammarFile(
        val file: VirtualFile,
        val displayName: String,
    ) : GrammarPickerItem

    data object BrowseOption : GrammarPickerItem
}

internal fun showGrammarPicker(
    event: AnActionEvent,
    popupTitle: String,
    items: List<GrammarPickerItem>,
    onGrammarChosen: (VirtualFile) -> Unit,
    onBrowse: () -> Unit,
) {
    val step =
        object : BaseListPopupStep<GrammarPickerItem>(popupTitle, items) {
            override fun getTextFor(value: GrammarPickerItem): String =
                when (value) {
                    is GrammarPickerItem.LevelHeader -> value.displayName
                    is GrammarPickerItem.GrammarFile -> "    ${value.displayName}"
                    is GrammarPickerItem.BrowseOption -> "Browse for Grammar..."
                }

            override fun isSelectable(value: GrammarPickerItem): Boolean = value !is GrammarPickerItem.LevelHeader

            override fun isMnemonicsNavigationEnabled(): Boolean = true

            override fun onChosen(
                selectedValue: GrammarPickerItem?,
                finalChoice: Boolean,
            ): PopupStep<*>? {
                if (!finalChoice) return FINAL_CHOICE

                when (selectedValue) {
                    is GrammarPickerItem.GrammarFile -> {
                        onGrammarChosen(selectedValue.file)
                    }
                    is GrammarPickerItem.BrowseOption -> {
                        ApplicationManager.getApplication().invokeLater {
                            onBrowse()
                        }
                    }
                    else -> {}
                }
                return FINAL_CHOICE
            }
        }

    JBPopupFactory.getInstance().createListPopup(step).showInBestPositionFor(event.dataContext)
}
