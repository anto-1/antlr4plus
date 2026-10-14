package com.antlr4plus.onboarding

import com.antlr4plus.notifications.NotificationsUtils
import com.antlr4plus.onboarding.OnboardingDialog.HighlightAction
import com.antlr4plus.onboarding.OnboardingDialog.OnboardingPage
import com.intellij.openapi.project.Project
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

internal fun createOnboardingPages(project: Project?): List<OnboardingPage> =
    listOf(
        OnboardingPage(
            title = "Input Files: The .g4p Extension",
            content =
                listOf(
                    "ANTLR4+ uses .g4p files to hold sample input for your grammar.",
                    "",
                    "A .g4p file is just a text file containing content you want to parse.",
                    "The .g4p extension tells ANTLR4+ \"this is parser input, not a grammar.\"",
                    "",
                    "To create one:",
                    "\u2022 Right-click any text file \u2192 'Rename to .g4p'",
                    "\u2022 Or use 'Rename and Bind' to create and connect it in one step",
                ),
        ),
        OnboardingPage(
            title = "Binding: Connecting Input to Grammar",
            content =
                listOf(
                    "Each .g4p file must be bound to a grammar.",
                    "",
                    "To bind:",
                    "\u2022 Right-click the .g4p file in the Project view",
                    "\u2022 Select 'Bind .g4 Grammar'",
                    "\u2022 Choose your .g4 grammar file",
                    "",
                    "Once bound, click the .g4p file in the Project view to trigger preview.",
                ),
            highlight =
                "NOTE: Bindings persist across IDE sessions. To change a binding, " +
                    "right-click and select 'Unbind .g4 Grammar' or rebind.",
        ),
        OnboardingPage(
            title = "Live Preview: How It Works",
            content =
                listOf(
                    "When you click a bound .g4p file, ANTLR4+ will:",
                    "",
                    "1. Generate Java/Kotlin code from your grammar (and any imported grammars)",
                    "2. Compile the lexer and parser using the bundled compiler",
                    "3. Execute the parser on your input text",
                    "4. Display the results across all panels (Tree, Lexer, Profiler, etc.)",
                    "",
                    "This means code from your grammar actually runs \u2014 including any",
                    "embedded actions, semantic predicates, and custom base classes.",
                ),
        ),
        OnboardingPage(
            title = "Security",
            content =
                listOf(
                    "Grammar preview compiles and executes code from your",
                    "grammar \u2014 including embedded actions and base classes.",
                    "",
                    "ANTLR4+ uses IntelliJ's built-in project trust system:",
                    "",
                    "\u2022 Trusted projects: preview runs normally",
                    "\u2022 Untrusted projects: preview is blocked",
                    "",
                    "Only open grammars from sources you trust.",
                    "Use File \u2192 Trust Project to enable preview.",
                ),
            highlight =
                "TIP: When you trust a project, preview generation starts automatically. " +
                    "No extra prompts or buttons needed.",
        ),
        OnboardingPage(
            title = "You're Ready!",
            content =
                listOf(
                    "Quick start:",
                    "",
                    "1. Open or create a .g4 grammar file",
                    "2. Right-click a sample input file \u2192 'Rename and Bind'",
                    "3. Select your grammar",
                    "4. Click the .g4p file in the Project view",
                    "",
                    "The tree updates live as you edit grammar or input.",
                ),
            highlight =
                "TIP: Clone github.com/antlr/grammars-v4 and checkout commit 7bb150f, then click 'Copy Bindings' below. " +
                    "Paste into the Import/Export pane editor and click 'Apply' to auto-rename files to .g4p and bind to grammars.",
            highlightActions =
                listOf(
                    HighlightAction("Copy Clone Command") {
                        val command =
                            "git clone https://github.com/antlr/grammars-v4.git && cd grammars-v4 && git checkout 7bb150f"
                        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                        clipboard.setContents(StringSelection(command), null)
                        val proj = project ?: return@HighlightAction
                        NotificationsUtils.showToast(proj, "Clone command copied to clipboard")
                    },
                    HighlightAction("Copy Bindings") {
                        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                        clipboard.setContents(StringSelection(GrammarsV4Bindings.BINDINGS), null)
                        val bindingCount = GrammarsV4Bindings.BINDINGS.trim().lines().size
                        val proj = project ?: return@HighlightAction
                        NotificationsUtils.showToast(
                            proj,
                            "$bindingCount bindings copied to clipboard",
                        )
                    },
                ),
        ),
        OnboardingPage(
            title = "Help improve ANTLR4+",
            content =
                listOf(
                    "This plugin doesn't collect any usage data.",
                    "",
                    "If something doesn't work or feels off,",
                    "a quick marketplace review helps more",
                    "than you'd think \u2014 I read every one and",
                    "bugs are typically fixed within days.",
                ),
            highlight = "Something broke? Leave a review or send an email. " + "I respond to every message personally.",
            highlightActions =
                listOf(
                    HighlightAction("Leave a Review") {
                        com.intellij.ide.BrowserUtil.browse(
                            "https://plugins.jetbrains.com/plugin/29781-antlr4-plus/reviews",
                        )
                    },
                    HighlightAction("Copy Email") {
                        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                        clipboard.setContents(StringSelection("i00@tutanota.com"), null)
                        val proj = project ?: return@HighlightAction
                        NotificationsUtils.showToast(proj, "Email copied to clipboard")
                    },
                ),
        ),
    )
