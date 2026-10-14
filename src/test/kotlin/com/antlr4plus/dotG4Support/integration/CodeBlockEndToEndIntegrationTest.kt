package com.antlr4plus.dotG4Support.integration

import com.antlr4plus.actions.SyncGeneratedActionToGrammarAction
import com.antlr4plus.dotG4Support.actionInlay.EmbeddedCodeRegionHighlighter
import com.antlr4plus.dotG4Support.actionInlay.GeneratedActionRegionHighlighter
import com.antlr4plus.dotG4Support.lineMarker.GeneratedActionGotoDeclarationHandler
import com.antlr4plus.dotG4Support.lineMarker.GeneratedActionNavigationSupport
import com.antlr4plus.filebinding.FileBindingService
import com.antlr4plus.parsing.actionMap.ActionLocationMap
import com.antlr4plus.parsing.actionMap.ActionLocationMapService
import com.antlr4plus.parsing.actionMap.GeneratedSyncPreviewRefreshSupport
import com.antlr4plus.previewService.PreviewService
import com.intellij.codeInsight.navigation.actions.GotoDeclarationOrUsageHandler2
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.event.FocusEvent
import java.io.File

class CodeBlockEndToEndIntegrationTest : BasePlatformTestCase() {
    private val temporaryRoots = mutableListOf<File>()

    override fun setUp() {
        super.setUp()
        project.getService(PreviewService::class.java).isEnabled = true
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    override fun tearDown() {
        try {
            project.getService(PreviewService::class.java).isEnabled = false
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            temporaryRoots.forEach(File::deleteRecursively)
            temporaryRoots.clear()
        } finally {
            super.tearDown()
        }
    }

    fun `test executable code blocks work end to end across grammar and generated editors`() {
        val fixture =
            createFixture(
                grammarName = "MixedDemo",
                grammarText =
                    """
                    grammar MixedDemo;

                    @members {
                        boolean helper() {
                            return true;
                        }
                    }

                    rule
                        : { helper() }? ID { afterExpr(); } EOF
                        ;
                        catch [RecognitionException re] {
                            recoverHere(re, _localctx);
                        }
                        finally {
                            cleanup();
                        }

                    ID : [a-zA-Z]+ ;
                    WS : [ \t\r\n]+ -> skip ;
                    """
                        .trimIndent(),
                generatedText =
                    """
                    public class MixedDemoParser extends Parser {
                        boolean helper() {
                            return true;
                        }

                        public final RuleContext rule() throws RecognitionException {
                            try {
                                if (!( helper() )) throw new FailedPredicateException(this, "helper()");
                                match(ID);
                                 afterExpr();
                                match(EOF);
                            }
                            catch (RecognitionException re) {
                                recoverHere(re, _localctx);
                            }
                            finally {
                                cleanup();
                                exitRule();
                            }
                            return _localctx;
                        }
                    }
                    """
                        .trimIndent(),
            )

        assertEquals(5, fixture.map.entries.size)

        val grammarEditor = openEditor(fixture.grammarVFile)
        val grammarHighlighters =
            waitForHighlighterCount(
                grammarEditor.editor,
                EmbeddedCodeRegionHighlighter.OUR_HIGHLIGHTER_KEY,
                5,
            )
        assertTrue(
            grammarHighlighters.all {
                (it.errorStripeTooltip as? String)?.startsWith("Embedded code:") == true
            },
        )
        assertTrue(
            grammarHighlighters.all {
                it.getTextAttributes(grammarEditor.editor.colorsScheme)?.backgroundColor != null
            },
        )
        assertTrue(
            grammarHighlighters.all {
                it.getTextAttributes(grammarEditor.editor.colorsScheme)?.effectType == null
            },
        )

        val catchEntry = fixture.map.entries.first { it.body == "recoverHere(re, _localctx);" }
        assertGotoFromEditor(
            psiFile = grammarEditor.psiFile,
            editor = grammarEditor.editor,
            lookupText = "recoverHere",
            expectedTargetOffset =
                GeneratedActionNavigationSupport.computeNavigationOffset(
                    fixture.generatedVFile,
                    fixture.generatedFile,
                    catchEntry,
                ),
        )

        val generatedEditor = openEditor(fixture.generatedVFile)
        val generatedHighlighters =
            waitForHighlighterCount(
                generatedEditor.editor,
                GeneratedActionRegionHighlighter.OUR_HIGHLIGHTER_KEY,
                fixture.map.entries.size,
            )
        assertEquals(
            fixture.map.entries.sortedBy { it.generatedBodyRange.first }.map { it.generatedBodyRange.first },
            generatedHighlighters.sortedBy { it.startOffset }.map { it.startOffset },
        )
        assertTrue(
            generatedHighlighters.all {
                (it.errorStripeTooltip as? String)?.startsWith("Generated counterpart:") == true &&
                    it.gutterIconRenderer == null
            },
        )
        assertTrue(
            generatedHighlighters.all {
                it.getTextAttributes(generatedEditor.editor.colorsScheme)?.backgroundColor != null
            },
        )
        assertTrue(
            generatedHighlighters.all {
                it.getTextAttributes(generatedEditor.editor.colorsScheme)?.effectType == null
            },
        )

        assertEquals(fixture.generatedText, fixture.generatedFile.readText())

        val finallyEntry = fixture.map.entries.first { it.body == "cleanup();" }
        assertGotoFromEditor(
            psiFile = generatedEditor.psiFile,
            editor = generatedEditor.editor,
            lookupText = "cleanup",
            expectedTargetOffset =
                GeneratedActionNavigationSupport.computeGrammarNavigationOffset(
                    fixture.grammarVFile,
                    fixture.grammarFile,
                    finallyEntry.grammarBodyRange.first,
                    rangeEndExclusive(finallyEntry.grammarBodyRange),
                ),
        )

        val parserActionEntry = fixture.map.entries.first { it.body == "afterExpr();" }
        WriteCommandAction.runWriteCommandAction(project) {
            generatedEditor.editor.document.replaceString(
                parserActionEntry.generatedBodyRange.first,
                parserActionEntry.generatedBodyRange.last + 1,
                "afterExprUpdated();",
            )
        }

        val grammarDocument = requireNotNull(FileDocumentManager.getInstance().getDocument(fixture.grammarVFile))
        assertFalse(grammarDocument.text.contains("{ afterExprUpdated(); }"))
        assertNull(
            project.getService(ActionLocationMapService::class.java).peekPendingGeneratedSync(fixture.grammarFile),
        )

        syncHighlightedBlock(generatedEditor.editor, parserActionEntry.generatedBodyRange.first)
        waitUntil("manual generated sync updates the grammar action body") {
            grammarDocument.text.contains("{ afterExprUpdated(); }")
        }

        val pendingSync =
            project.getService(ActionLocationMapService::class.java).peekPendingGeneratedSync(fixture.grammarFile)
        assertNotNull(pendingSync)
        assertEquals(
            fixture.g4pVFile.path,
            GeneratedSyncPreviewRefreshSupport.resolvePendingRefreshTarget(
                    project,
                    fixture.grammarVFile,
                )
                ?.path,
        )
    }

    fun `test argument blocks work end to end across grammar and generated editors`() {
        val fixture =
            createFixture(
                grammarName = "ArgumentDemo",
                grammarText =
                    """
                    grammar ArgumentDemo;

                    start[int level] returns [int value]
                    locals [int n = 0]
                        : atom[1, 2] EOF
                        ;

                    atom[int a, int b]
                        : ID
                        ;

                    ID : [a-zA-Z]+ ;
                    WS : [ \t\r\n]+ -> skip ;
                    """
                        .trimIndent(),
                generatedText =
                    """
                    public class ArgumentDemoParser extends Parser {
                        public static class StartContext extends ParserRuleContext {
                            public int level;
                            public int value;
                            public int n = 0;
                            public AtomContext atom() { return getRuleContext(AtomContext.class, 0); }
                            public TerminalNode EOF() { return getToken(ArgumentDemoParser.EOF, 0); }
                            public StartContext(ParserRuleContext parent, int invokingState) { super(parent, invokingState); }
                            public StartContext(ParserRuleContext parent, int invokingState, int level) {
                                super(parent, invokingState);
                                this.level = level;
                            }
                        }

                        public final StartContext start(int level) throws RecognitionException {
                            StartContext _localctx = new StartContext(_ctx, getState(), level);
                            enterRule(_localctx, 0, RULE_start);
                            try {
                                enterOuterAlt(_localctx, 1);
                                {
                                atom(1, 2);
                                match(EOF);
                                }
                            }
                            finally {
                                exitRule();
                            }
                            return _localctx;
                        }

                        public static class AtomContext extends ParserRuleContext {
                            public int a;
                            public int b;
                            public TerminalNode ID() { return getToken(ArgumentDemoParser.ID, 0); }
                            public AtomContext(ParserRuleContext parent, int invokingState) { super(parent, invokingState); }
                            public AtomContext(ParserRuleContext parent, int invokingState, int a, int b) {
                                super(parent, invokingState);
                                this.a = a;
                                this.b = b;
                            }
                        }

                        public final AtomContext atom(int a, int b) throws RecognitionException {
                            AtomContext _localctx = new AtomContext(_ctx, getState(), a, b);
                            enterRule(_localctx, 2, RULE_atom);
                            try {
                                enterOuterAlt(_localctx, 1);
                                {
                                match(ID);
                                }
                            }
                            finally {
                                exitRule();
                            }
                            return _localctx;
                        }
                    }
                    """
                        .trimIndent(),
            )

        val expectedBodies = setOf("int level", "int value", "int n = 0", "1, 2", "int a, int b")
        assertEquals(expectedBodies, fixture.map.entries.map { it.body }.toSet())

        val grammarEditor = openEditor(fixture.grammarVFile)
        val grammarHighlighters =
            waitForHighlighterCount(
                grammarEditor.editor,
                EmbeddedCodeRegionHighlighter.OUR_HIGHLIGHTER_KEY,
                expectedBodies.size,
            )
        assertEquals(expectedBodies.size, grammarHighlighters.size)
        assertTrue(
            grammarHighlighters.all {
                it.getTextAttributes(grammarEditor.editor.colorsScheme)?.backgroundColor != null
            },
        )
        assertTrue(
            grammarHighlighters.all {
                it.getTextAttributes(grammarEditor.editor.colorsScheme)?.effectType == null
            },
        )

        val returnsEntry = fixture.map.entries.first { it.body == "int value" }
        assertGotoFromEditor(
            psiFile = grammarEditor.psiFile,
            editor = grammarEditor.editor,
            lookupText = "int value",
            expectedTargetOffset =
                GeneratedActionNavigationSupport.computeNavigationOffset(
                    fixture.generatedVFile,
                    fixture.generatedFile,
                    returnsEntry,
                ),
        )

        val generatedEditor = openEditor(fixture.generatedVFile)
        val generatedHighlighters =
            waitForHighlighterCount(
                generatedEditor.editor,
                GeneratedActionRegionHighlighter.OUR_HIGHLIGHTER_KEY,
                expectedBodies.size,
            )
        assertEquals(expectedBodies.size, generatedHighlighters.size)
        assertTrue(
            generatedHighlighters.all {
                it.getTextAttributes(generatedEditor.editor.colorsScheme)?.backgroundColor != null
            },
        )
        assertTrue(
            generatedHighlighters.all {
                it.getTextAttributes(generatedEditor.editor.colorsScheme)?.effectType == null
            },
        )

        assertEquals(fixture.generatedText, fixture.generatedFile.readText())

        val callArgsEntry = fixture.map.entries.first { it.body == "1, 2" }
        assertGotoFromEditor(
            psiFile = generatedEditor.psiFile,
            editor = generatedEditor.editor,
            lookupText = "1, 2",
            expectedTargetOffset =
                GeneratedActionNavigationSupport.computeGrammarNavigationOffset(
                    fixture.grammarVFile,
                    fixture.grammarFile,
                    callArgsEntry.grammarBodyRange.first,
                    rangeEndExclusive(callArgsEntry.grammarBodyRange),
                ),
        )
    }

    fun `test generated sync postpones grammar auto refresh until grammar editor refocus`() {
        val fixture =
            createFixture(
                grammarName = "DeferredRefreshDemo",
                grammarText =
                    """
                    grammar DeferredRefreshDemo;

                    rule
                        : ID { first(); } EOF
                        ;

                    ID : [a-zA-Z]+ ;
                    WS : [ \t\r\n]+ -> skip ;
                    """
                        .trimIndent(),
                generatedText =
                    """
                    public class DeferredRefreshDemoParser extends Parser {
                        public final RuleContext rule() throws RecognitionException {
                            match(ID);
                             first();
                            match(EOF);
                            return _localctx;
                        }
                    }
                    """
                        .trimIndent(),
            )

        activatePreview(fixture)

        val generatedEditor = openEditor(fixture.generatedVFile)
        waitForHighlighterCount(
            generatedEditor.editor,
            GeneratedActionRegionHighlighter.OUR_HIGHLIGHTER_KEY,
            fixture.map.entries.size,
        )

        val grammarEditor = openEditor(fixture.grammarVFile)
        waitForHighlighterCount(
            grammarEditor.editor,
            EmbeddedCodeRegionHighlighter.OUR_HIGHLIGHTER_KEY,
            fixture.map.entries.size,
        )

        val entry = fixture.map.entries.single()
        WriteCommandAction.runWriteCommandAction(project) {
            generatedEditor.editor.document.replaceString(
                entry.generatedBodyRange.first,
                entry.generatedBodyRange.last + 1,
                "second();",
            )
        }

        val mapService = project.getService(ActionLocationMapService::class.java)
        val grammarDocument = FileDocumentManager.getInstance().getDocument(fixture.grammarVFile)
        assertNotNull(grammarDocument)
        val resolvedGrammarDocument = grammarDocument!!

        assertFalse(resolvedGrammarDocument.text.contains("{ second(); }"))
        assertNull(mapService.peekPendingGeneratedSync(fixture.grammarFile))

        syncHighlightedBlock(generatedEditor.editor, entry.generatedBodyRange.first)
        waitUntil("manual generated sync updates grammar before deferred refresh") {
            resolvedGrammarDocument.text.contains("{ second(); }")
        }
        waitUntil("pending generated sync is recorded") {
            mapService.peekPendingGeneratedSync(fixture.grammarFile) != null
        }
        assertEquals(
            fixture.g4pVFile.path,
            GeneratedSyncPreviewRefreshSupport.resolvePendingRefreshTarget(
                    project,
                    fixture.grammarVFile,
                )
                ?.path,
        )

        WriteCommandAction.runWriteCommandAction(project) {
            grammarEditor.editor.document.insertString(0, "\n")
        }
        settle(waitMs = 900)

        assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(resolvedGrammarDocument))
        assertNotNull(mapService.peekPendingGeneratedSync(fixture.grammarFile))

        fireFocusGained(grammarEditor.editor)

        waitUntil("grammar focus flush clears the pending generated sync") {
            mapService.peekPendingGeneratedSync(fixture.grammarFile) == null
        }
        waitUntil("grammar focus flush saves the grammar document") {
            !FileDocumentManager.getInstance().isDocumentUnsaved(resolvedGrammarDocument)
        }
    }

    fun `test grammar split editors keep identical highlight offsets`() {
        val fixture =
            createFixture(
                grammarName = "SplitGrammarDemo",
                grammarText =
                    """
                    grammar SplitGrammarDemo;

                    @members {
                        boolean helper() {
                            return true;
                        }
                    }

                    rule
                        : { helper() }? ID { afterExpr(); } EOF
                        ;

                    ID : [a-zA-Z]+ ;
                    WS : [ \t\r\n]+ -> skip ;
                    """
                        .trimIndent(),
                generatedText =
                    """
                    public class SplitGrammarDemoParser extends Parser {
                        boolean helper() {
                            return true;
                        }

                        public final RuleContext rule() throws RecognitionException {
                            if (!( helper() )) throw new FailedPredicateException(this, "helper()");
                            match(ID);
                             afterExpr();
                            match(EOF);
                            return _localctx;
                        }
                    }
                    """
                        .trimIndent(),
            )

        val primaryEditor = openEditor(fixture.grammarVFile).editor
        val primaryHighlighters =
            waitForHighlighterCount(
                primaryEditor,
                EmbeddedCodeRegionHighlighter.OUR_HIGHLIGHTER_KEY,
                fixture.map.entries.size,
            )

        val secondaryEditor =
            EditorFactory.getInstance().createEditor(primaryEditor.document, project, fixture.grammarVFile, false)
        try {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

            val secondaryHighlighters =
                waitForHighlighterCount(
                    secondaryEditor,
                    EmbeddedCodeRegionHighlighter.OUR_HIGHLIGHTER_KEY,
                    fixture.map.entries.size,
                )

            assertEquals(
                primaryHighlighters.map { it.startOffset to it.endOffset },
                secondaryHighlighters.map { it.startOffset to it.endOffset },
            )
        } finally {
            EditorFactory.getInstance().releaseEditor(secondaryEditor)
        }
    }

    private fun createFixture(
        grammarName: String,
        grammarText: String,
        generatedText: String,
    ): FixtureFiles {
        val root = File(project.basePath!!, "code-block-e2e-$grammarName-${System.nanoTime()}")
        assertTrue(root.mkdirs())
        temporaryRoots += root

        val grammarFile = File(root, "$grammarName.g4")
        val g4pFile = File(root, "$grammarName.g4p")
        val generatedFile = File(root, "gen/preview/java/${grammarName}Parser.java")

        grammarFile.writeText(grammarText)
        g4pFile.writeText("input")
        generatedFile.parentFile.mkdirs()
        generatedFile.writeText(generatedText)

        val grammarVFile = refreshFile(grammarFile)
        val g4pVFile = refreshFile(g4pFile)
        assertNotNull(grammarVFile)
        assertNotNull(g4pVFile)

        val bindingService = project.getService(FileBindingService::class.java)
        assertTrue(bindingService.bind(g4pVFile!!, grammarVFile!!))

        val generatedVFile = refreshFile(generatedFile)
        assertNotNull(generatedVFile)

        val mapService = project.getService(ActionLocationMapService::class.java)
        mapService.refreshFor(grammarFile, generatedFile)
        val map = mapService.get(grammarFile)
        assertNotNull(map)
        assertFalse(File(generatedFile.parentFile, ".antlr4plus").exists())

        return FixtureFiles(
            grammarFile = grammarFile,
            grammarVFile = grammarVFile,
            g4pVFile = g4pVFile,
            generatedFile = generatedFile,
            generatedVFile = generatedVFile!!,
            generatedText = generatedText,
            map = map!!,
        )
    }

    private fun refreshFile(file: File): VirtualFile? = runWriteAction {
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
    }

    private fun activatePreview(fixture: FixtureFiles) {
        val previewService = project.getService(PreviewService::class.java)
        previewService.state.g4pInputFile = fixture.g4pVFile
        previewService.state.grammarFile = fixture.grammarVFile
        previewService.state.lexerGrammarFile = null
    }

    private fun openEditor(virtualFile: VirtualFile): OpenEditor {
        myFixture.configureFromExistingVirtualFile(virtualFile)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val editor = myFixture.editor
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
        assertNotNull(psiFile)
        return OpenEditor(
            psiFile = psiFile!!,
            editor = editor,
        )
    }

    private fun assertGotoFromEditor(
        psiFile: PsiFile,
        editor: Editor,
        lookupText: String,
        expectedTargetOffset: Int,
    ) {
        val offset = psiFile.text.indexOf(lookupText)
        assertTrue("Expected to find [$lookupText] in ${psiFile.name}", offset >= 0)

        val sourceElement: PsiElement? = psiFile.findElementAt(offset)
        val targets = GeneratedActionGotoDeclarationHandler().getGotoDeclarationTargets(sourceElement, offset, editor)
        assertNotNull(targets)
        assertEquals(1, targets!!.size)
        assertEquals(expectedTargetOffset, targets.single().textOffset)

        val outcome = computeGtduOutcome(psiFile, editor, offset)
        assertEquals(GotoDeclarationOrUsageHandler2.GTDUOutcome.GTD, outcome)
    }

    private fun computeGtduOutcome(
        psiFile: PsiFile,
        editor: Editor,
        offset: Int,
    ): GotoDeclarationOrUsageHandler2.GTDUOutcome? {
        val future =
            ApplicationManager.getApplication().executeOnPooledThread<
                GotoDeclarationOrUsageHandler2.GTDUOutcome?,
            > {
                ReadAction.compute<GotoDeclarationOrUsageHandler2.GTDUOutcome?, RuntimeException> {
                    GotoDeclarationOrUsageHandler2.testGTDUOutcome(editor, psiFile, offset)
                }
            }
        return future.get()
    }

    private fun waitForHighlighterCount(
        editor: Editor,
        key: Key<Boolean>,
        expectedCount: Int,
    ): List<RangeHighlighter> {
        var result = emptyList<RangeHighlighter>()
        waitUntil("editor highlighter count reaches $expectedCount") {
            val markupModel = DocumentMarkupModel.forDocument(editor.document, project, false)
            result =
                (markupModel?.allHighlighters ?: emptyArray())
                    .filter { it.getUserData(key) == true }
                    .sortedBy { it.startOffset }
            result.size == expectedCount
        }
        return result
    }

    private fun waitUntil(
        description: String,
        timeoutMs: Long = 4_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            if (condition()) {
                return
            }
            Thread.sleep(50)
        }

        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertTrue("Timed out waiting for: $description", condition())
    }

    private fun settle(waitMs: Long = 300) {
        Thread.sleep(waitMs)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private fun syncHighlightedBlock(
        editor: Editor,
        caretOffset: Int,
        selectionEndExclusive: Int? = null,
    ) {
        positionEditor(editor, caretOffset, selectionEndExclusive)

        val action = SyncGeneratedActionToGrammarAction()
        var event = createEditorActionEvent(action, editor)
        waitUntil("manual generated-action sync to become available") {
            event = createEditorActionEvent(action, editor)
            action.update(event)
            event.presentation.isEnabledAndVisible
        }
        action.actionPerformed(event)
        settle()
    }

    private fun positionEditor(
        editor: Editor,
        caretOffset: Int,
        selectionEndExclusive: Int?,
    ) {
        val targetOffset = caretOffset.coerceIn(0, editor.document.textLength)
        runWriteAction {
            editor.caretModel.moveToOffset(targetOffset)
            if (selectionEndExclusive != null && selectionEndExclusive > targetOffset) {
                editor.selectionModel.setSelection(
                    targetOffset,
                    selectionEndExclusive.coerceIn(targetOffset, editor.document.textLength),
                )
            } else {
                editor.selectionModel.removeSelection()
            }
        }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private fun createEditorActionEvent(action: AnAction, editor: Editor): AnActionEvent {
        val file = FileDocumentManager.getInstance().getFile(editor.document)
        val dataContext = DataContext { dataId ->
            when {
                CommonDataKeys.PROJECT.`is`(dataId) -> project
                CommonDataKeys.EDITOR.`is`(dataId) -> editor
                CommonDataKeys.VIRTUAL_FILE.`is`(dataId) -> file
                else -> null
            }
        }

        return TestActionEvent.createTestEvent(action, dataContext)
    }

    private fun fireFocusGained(editor: Editor) {
        val event = FocusEvent(editor.contentComponent, FocusEvent.FOCUS_GAINED)
        editor.contentComponent.focusListeners.forEach { it.focusGained(event) }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private fun rangeEndExclusive(range: IntRange): Int =
        if (range.isEmpty()) {
            range.first.coerceAtLeast(0)
        } else {
            range.last + 1
        }

    private data class FixtureFiles(
        val grammarFile: File,
        val grammarVFile: VirtualFile,
        val g4pVFile: VirtualFile,
        val generatedFile: File,
        val generatedVFile: VirtualFile,
        val generatedText: String,
        val map: ActionLocationMap,
    )

    private data class OpenEditor(
        val psiFile: PsiFile,
        val editor: Editor,
    )
}
