package com.antlr4plus.dotG4Support.annotator

import com.antlr4plus.actions.AnnotationIntentActionsFactory
import com.antlr4plus.dotG4Support.validation.GrammarIssue
import com.antlr4plus.dotG4Support.validation.GrammarIssuesCollector
import com.antlr4plus.parsing.GrammarWarningsService
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import org.antlr.runtime.CommonToken
import org.antlr.v4.tool.ErrorSeverity

class ExternalAnnotatorImpl : ExternalAnnotator<PsiFile?, MutableList<GrammarIssue?>?>() {
    companion object {
        private val LOG = Logger.getInstance(ExternalAnnotatorImpl::class.java)
    }

    override fun collectInformation(file: PsiFile): PsiFile {
        LOG.info("ExternalAnnotator.collectInformation called for ${file.name}")
        return file
    }

    override fun doAnnotate(collectedInfo: PsiFile?): MutableList<GrammarIssue?>? {
        LOG.info("ExternalAnnotator.doAnnotate called for ${collectedInfo?.name}")
        if (collectedInfo == null) return mutableListOf()
        return try {
            @Suppress("UNCHECKED_CAST")
            val issues = GrammarIssuesCollector.collectGrammarIssues(collectedInfo) as MutableList<GrammarIssue?>
            LOG.info("doAnnotate returning ${issues.size} issues")
            issues
        } catch (e: Exception) {
            LOG.warn("Failed to collect grammar issues", e)
            mutableListOf()
        }
    }

    override fun apply(
        file: PsiFile,
        annotationResult: MutableList<GrammarIssue?>?,
        holder: AnnotationHolder,
    ) {
        LOG.info(
            "ExternalAnnotator.apply called for ${file.name} with ${annotationResult?.size ?: 0} issues",
        )
        annotationResult?.filterNotNull()?.forEach {
            LOG.info(
                "Processing issue: annotation=${it.annotation}, tokens=${it.offendingTokens.size}",
            )
            if (it.offendingTokens.isEmpty()) {
                annotateFileIssue(file, holder, it)
            } else {
                annotateIssue(file, holder, it)
            }
        }

        annotateServiceWarnings(file, holder)
    }

    private fun annotateServiceWarnings(file: PsiFile, holder: AnnotationHolder) {
        val project = file.project
        val virtualFile = file.virtualFile ?: return
        val warningsService = GrammarWarningsService.getInstance(project)

        val warnings =
            warningsService.getWarnings(virtualFile.path).ifEmpty {
                warningsService.getWarningsByFileName(virtualFile.name)
            }

        if (warnings.isEmpty()) return

        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return

        for ((_, line, column, message, isError) in warnings) {
            val range = getTextRangeFromLineColumn(document, line, column, file)
            if (range != null) {
                val severity = if (isError) HighlightSeverity.ERROR else HighlightSeverity.WARNING
                holder.newAnnotation(severity, message).range(range).create()
            }
        }
    }

    private fun annotateFileIssue(file: PsiFile, holder: AnnotationHolder, issue: GrammarIssue) {
        if (issue.annotation == null || issue.msg == null) {
            return
        }
        var builder =
            holder.newAnnotation(HighlightSeverity.WARNING, issue.annotation!!).range(file.textRange).fileLevel()
        val intentionAction =
            AnnotationIntentActionsFactory.getFix(
                TextRange(file.textOffset, file.textOffset + file.textLength),
                issue.msg.errorType,
                file,
            )
        if (intentionAction != null) {
            builder = builder.withFix(intentionAction)
        }
        builder.create()
    }

    private fun annotateIssue(file: PsiFile, holder: AnnotationHolder, issue: GrammarIssue) {
        for (t in issue.offendingTokens) {
            if (t is CommonToken && tokenBelongsToFile(t, file)) {
                val range = getTokenRange(t, file)
                val severity = getIssueSeverity(issue)
                annotate(holder, issue, range, severity, file)
            }
        }
    }

    private fun getIssueSeverity(issue: GrammarIssue): ErrorSeverity {
        if (issue.msg != null && issue.msg.errorType != null) {
            return issue.msg.errorType.severity
        }
        return ErrorSeverity.INFO
    }

    private fun annotate(
        holder: AnnotationHolder,
        issue: GrammarIssue,
        range: TextRange,
        severity: ErrorSeverity,
        file: PsiFile,
    ) {
        val highlightSeverity =
            when (severity) {
                ErrorSeverity.ERROR,
                ErrorSeverity.ERROR_ONE_OFF,
                ErrorSeverity.FATAL -> HighlightSeverity.ERROR

                ErrorSeverity.WARNING -> HighlightSeverity.WARNING
                ErrorSeverity.WARNING_ONE_OFF,
                ErrorSeverity.INFO -> HighlightSeverity.WEAK_WARNING
            }

        if (issue.annotation == null || issue.msg == null) {
            return
        }

        var builder = holder.newAnnotation(highlightSeverity, issue.annotation!!).range(range)

        val intentionAction =
            AnnotationIntentActionsFactory.getFix(
                range,
                issue.msg.errorType,
                file,
            )
        if (intentionAction != null) {
            builder = builder.withFix(intentionAction)
        }

        builder.create()
    }
}
