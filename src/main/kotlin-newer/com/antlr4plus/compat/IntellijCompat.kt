package com.antlr4plus.compat

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.ide.trustedProjects.TrustedProjects
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
    addBrowseFolderListener(
        TextBrowseFolderListener(descriptor.withTitle(title).withDescription(description), project),
    )
}

/** Call the Java setter explicitly for compatibility with this IDE range. */
fun RangeHighlighter.compatSetErrorStripeMarkColor(color: Color?) {
    @Suppress("DEPRECATION") this.setErrorStripeMarkColor(color)
}

fun DaemonCodeAnalyzer.compatRestart(reason: String) {
    this.restart(reason)
}

fun DaemonCodeAnalyzer.compatRestart(psiFile: PsiFile, reason: String) {
    this.restart(psiFile, reason)
}

fun Project.compatIsTrusted(): Boolean = TrustedProjects.isProjectTrusted(this)

fun Project.compatSetTrusted(trusted: Boolean) {
    TrustedProjects.setProjectTrusted(this, trusted)
}
