package com.antlr4plus.compat

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.ide.impl.isTrusted
import com.intellij.ide.impl.setTrusted
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.psi.PsiFile
import java.awt.Color

fun TextFieldWithBrowseButton.compatAddBrowseListener(
    title: String,
    description: String,
    project: Project?,
    descriptor: FileChooserDescriptor,
) {
    addBrowseFolderListener(TextBrowseFolderListener(descriptor, project))
}

/** Call the Java setter explicitly for compatibility with this IDE range. */
fun RangeHighlighter.compatSetErrorStripeMarkColor(color: Color?) {
    @Suppress("DEPRECATION") this.setErrorStripeMarkColor(color)
}

/** These IDEs restart analysis without a reason parameter. */
@Suppress("UNUSED_PARAMETER")
fun DaemonCodeAnalyzer.compatRestart(reason: String) {
    this.restart()
}

/** These IDEs restart file analysis without a reason parameter. */
@Suppress("UNUSED_PARAMETER")
fun DaemonCodeAnalyzer.compatRestart(psiFile: PsiFile, reason: String) {
    this.restart(psiFile)
}

/** These IDEs expose project-based trust checks through the legacy extensions. */
@Suppress("DEPRECATION", "UnstableApiUsage") fun Project.compatIsTrusted(): Boolean = isTrusted()

@Suppress("DEPRECATION", "UnstableApiUsage")
fun Project.compatSetTrusted(trusted: Boolean) {
    setTrusted(trusted)
}
