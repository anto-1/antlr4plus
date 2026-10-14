package com.antlr4plus.interaction

import com.antlr4plus.actions.ExtractRuleAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.CancellationException

class InteractionCancellationTest : BasePlatformTestCase() {
    fun testEditorInteractionPropagatesCancellationFromItsDataContext() {
        for (expected in listOf(ProcessCanceledException(), CancellationException("cancelled editor action"))) {
            val context = DataContext { key ->
                if (key == CommonDataKeys.PSI_FILE.name) throw expected
                null
            }
            val event = TestActionEvent(context, ExtractRuleAction())
            val actual =
                try {
                    InteractionHandler.process(UserInteraction.GrammarEditorAction.ExtractRule(event))
                    null
                } catch (failure: Throwable) {
                    failure
                }
            assertSame("Cancellation must reach the action dispatcher", expected, actual)
        }
    }
}
