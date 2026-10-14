package com.antlr4plus.dotG4Support.validation

import com.antlr4plus.codeGen.GenAntlrCode
import com.antlr4plus.compat.compatIsTrusted
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.PsiFile
import java.io.File
import java.io.StringReader
import org.antlr.runtime.ANTLRReaderStream
import org.antlr.v4.Tool
import org.antlr.v4.parse.ANTLRParser
import org.antlr.v4.runtime.misc.IntervalSet
import org.antlr.v4.tool.Grammar
import org.antlr.v4.tool.ast.GrammarAST
import org.antlr.v4.tool.ast.RuleRefAST

object GrammarIssuesCollector {
    val LOG: Logger = Logger.getInstance(GrammarIssuesCollector::class.java.getName())

    @JvmStatic
    fun collectGrammarIssues(file: PsiFile): MutableList<GrammarIssue> {
        val grammarFileName = file.virtualFile.path
        LOG.info("doAnnotate $grammarFileName")
        val fileContents = ReadAction.compute<String, RuntimeException> { file.text }
        val args = GenAntlrCode.getANTLRArgsAsList(file.project, file.virtualFile)
        val listener = GrammarIssuesCollectorToolListener()

        val antlr = Tool(args.toTypedArray<String?>())
        if (!args.contains("-lib")) {
            // PSI directory lookup requires a read action.
            ApplicationManager.getApplication().runReadAction {
                antlr.libDirectory = file.containingDirectory.toString()
            }
        }

        antlr.removeListeners()
        antlr.addListener(listener)
        try {
            val sr = StringReader(fileContents)
            val `in` = ANTLRReaderStream(sr)
            `in`.name = file.name
            val ast = antlr.parse(file.name, `in`)
            // Syntax parsing stays available in safe mode; semantic analysis follows imports and
            // tokenVocab paths.
            if (ast == null || ast.hasErrors || !file.project.compatIsTrusted()) {
                LOG.info("Syntax analysis complete, collected ${listener.issues.size} issues")
                for (issue in listener.issues) {
                    processIssue(file, issue)
                    LOG.info(
                        "Processed issue: ${issue.annotation}, offendingTokens: ${issue.offendingTokens.size}",
                    )
                }
                return listener.issues
            }
            val g = antlr.createGrammar(ast)
            g.fileName = grammarFileName

            val vocabName = g.getOptionString("tokenVocab")
            if (vocabName != null) { // Load the vocabulary to avoid missing-token diagnostics.
                LOG.info("token vocab file $vocabName")
                val tokensFileName = "$vocabName.tokens"
                val grammarDir = File(grammarFileName).parentFile

                val libIndex = args.indexOf("-lib")
                val configuredLibDir =
                    if (libIndex >= 0 && libIndex + 1 < args.size) {
                        val libPath = args[libIndex + 1]
                        // Treat the grammar directory as the default rather than an explicit
                        // library override.
                        if (libPath != grammarDir.absolutePath && libPath.isNotEmpty()) libPath else null
                    } else null

                if (configuredLibDir != null) {
                    val tokensInConfigured = File(configuredLibDir, tokensFileName).exists()
                    if (!tokensInConfigured) {
                        val foundTokensDir = findTokensFile(file, grammarDir, tokensFileName)
                        if (foundTokensDir != null) {
                            LOG.info(
                                "Tokens file not in configured lib ($configuredLibDir), found in: $foundTokensDir",
                            )
                            val warningIssue = GrammarIssue(null)
                            warningIssue.annotation =
                                "Token file '$tokensFileName' not found in configured library directory. Found at: ${foundTokensDir.absolutePath}"
                            listener.issues.add(warningIssue)
                            antlr.libDirectory = foundTokensDir.absolutePath
                        }
                    }
                } else {
                    val foundTokensDir = findTokensFile(file, grammarDir, tokensFileName)
                    if (foundTokensDir != null) {
                        LOG.info("Found tokens file in: $foundTokensDir")
                        antlr.libDirectory = foundTokensDir.absolutePath
                    }
                }
                g.importTokensFromTokensFile()
            }

            val vfile = file.virtualFile
            if (vfile == null) {
                LOG.error("doAnnotate no virtual file for $file")
                return listener.issues
            }
            g.fileName = vfile.path
            antlr.process(g, false)

            val unusedRules = getUnusedParserRules(g)
            if (unusedRules != null) {
                for (r in unusedRules.keys) {
                    val rule = unusedRules[r] ?: throw NullPointerException("Missing rule")
                    val ruleDefToken = rule.getToken()
                    val issue = GrammarIssue(GrammarInfoMessage(g.fileName, ruleDefToken, r))
                    listener.issues.add(issue)
                }
            }

            LOG.info("Semantic analysis complete, collected ${listener.issues.size} issues")
            for (issue in listener.issues) {
                processIssue(file, issue)
                LOG.info(
                    "Processed issue: ${issue.annotation}, offendingTokens: ${issue.offendingTokens.size}",
                )
            }
        } catch (e: Exception) {
            LOG.error("antlr can't process " + file.name, e)
        }
        LOG.info("Returning ${listener.issues.size} total issues for ${file.name}")
        return listener.issues
    }

    fun processIssue(file: PsiFile, issue: GrammarIssue) = processGrammarIssue(file, issue)

    /** Searches beside the grammar, then through its content-root directories, following directory links. */
    private fun findTokensFile(psiFile: PsiFile, grammarDir: File, tokensFileName: String): File? {
        if (File(grammarDir, tokensFileName).exists()) {
            return grammarDir
        }

        val contentRoot =
            ReadAction.compute<File?, RuntimeException> {
                val vFile = psiFile.virtualFile ?: return@compute null
                val root = ProjectRootManager.getInstance(psiFile.project).fileIndex.getContentRootForFile(vFile)
                root?.let { File(it.path) } ?: grammarDir.parentFile
            } ?: grammarDir.parentFile

        LOG.info("Searching for $tokensFileName within content root: ${contentRoot.absolutePath}")

        return searchForTokensFileInTree(contentRoot, tokensFileName)
    }

    /** Breadth-first search prefers shallower matches and skips hidden/common cache directories. */
    private fun searchForTokensFileInTree(rootDir: File, tokensFileName: String): File? {
        if (!rootDir.exists() || !rootDir.isDirectory) return null

        val queue = ArrayDeque<File>()
        queue.add(rootDir)

        while (queue.isNotEmpty()) {
            val currentDir = queue.removeFirst()

            if (File(currentDir, tokensFileName).exists()) {
                return currentDir
            }

            val children = currentDir.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory && !child.name.startsWith(".")) {
                    if (child.name !in listOf("node_modules", ".git", ".idea", "__pycache__", ".gradle")) {
                        queue.add(child)
                    }
                }
            }
        }

        return null
    }

    private fun getUnusedParserRules(g: Grammar): MutableMap<String?, GrammarAST?>? {
        if (g.ast == null || g.isLexer) return null
        val ruleNodes = g.ast.getNodesWithTypePreorderDFS(IntervalSet.of(ANTLRParser.RULE_REF))
        // Inspect the AST even when ANTLR did not finish building rule definitions.
        val ruleRefs: MutableSet<String?> = HashSet()
        val ruleDefs: MutableMap<String?, GrammarAST?> = LinkedHashMap()
        for (x in ruleNodes) {
            if (x.getParent().type == ANTLRParser.RULE) {
                ruleDefs[x.getText()] = x
            } else if (x is RuleRefAST) {
                ruleRefs.add(x.getText())
            }
        }
        ruleDefs.keys.removeAll(ruleRefs)

        // Exempt the first parser rule as a conventional entry point.
        // Use original definitions: ruleDefs now contains only unreferenced rules.
        val firstParserRule =
            g.rules.values.firstOrNull {
                val nm = it.name
                nm.isNotEmpty() && nm[0].isLowerCase()
            }
        if (firstParserRule != null) {
            ruleDefs.remove(firstParserRule.name)
        }

        return ruleDefs
    }
}
