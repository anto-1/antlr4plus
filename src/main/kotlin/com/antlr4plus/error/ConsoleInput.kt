package com.antlr4plus.error

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import com.antlr4plus.compat.compatCombinedClickable
import com.antlr4plus.util.rethrowIfControlFlow

internal inline fun Modifier.consoleSelectionKeys(
    crossinline selectAll: () -> Unit,
    crossinline clearSelection: () -> Unit,
): Modifier = onKeyEvent { keyEvent ->
    try {
        if (keyEvent.type == KeyEventType.KeyDown) {
            val awtEvent = java.awt.EventQueue.getCurrentEvent()
            val modifiers = (awtEvent as? java.awt.event.InputEvent)?.modifiersEx ?: 0
            val isCtrlOrCmd =
                (modifiers and java.awt.event.InputEvent.CTRL_DOWN_MASK) != 0 ||
                    (modifiers and java.awt.event.InputEvent.META_DOWN_MASK) != 0

            when {
                isCtrlOrCmd && keyEvent.key == Key.A -> {
                    selectAll()
                    true
                }
                keyEvent.key == Key.Escape -> {
                    clearSelection()
                    true
                }
                else -> false
            }
        } else {
            false
        }
    } catch (e: IllegalStateException) {
        rethrowIfControlFlow(e)
        // A tooltip can leave the panel without an active focus target.
        false
    }
}

internal inline fun Modifier.consoleRowClicks(
    index: Int,
    noinline onDoubleClick: () -> Unit,
    crossinline onClick: (Int, Boolean, Boolean) -> Unit,
): Modifier =
    compatCombinedClickable(
        onDoubleClick = onDoubleClick,
        onClick = {
            val awtEvent = java.awt.EventQueue.getCurrentEvent()
            val modifiers = (awtEvent as? java.awt.event.InputEvent)?.modifiersEx ?: 0
            val isShift = (modifiers and java.awt.event.InputEvent.SHIFT_DOWN_MASK) != 0
            val isCtrl =
                (modifiers and java.awt.event.InputEvent.CTRL_DOWN_MASK) != 0 ||
                    (modifiers and java.awt.event.InputEvent.META_DOWN_MASK) != 0
            onClick(index, isShift, isCtrl)
        },
    )
