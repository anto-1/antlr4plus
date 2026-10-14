package com.antlr4plus.dotG4Support.lineMarker

import com.antlr4plus.parsing.actionMap.ActionLocationMapService
import com.antlr4plus.previewEditor.GeneratedPreviewFileEditorProvider
import com.antlr4plus.previewService.PreviewService
import com.intellij.codeInsight.TargetElementUtil
import com.intellij.codeInsight.navigation.CtrlMouseData
import com.intellij.codeInsight.navigation.actions.GotoDeclarationOrUsageHandler2
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files

class GrammarActionPsiLookupTest : BasePlatformTestCase() {
    private val temporaryRoots = mutableListOf<File>()

    override fun tearDown() {
        try {
            temporaryRoots.forEach(File::deleteRecursively)
            temporaryRoots.clear()
        } finally {
            super.tearDown()
        }
    }

    fun `test ctrl click on parser action body uses normal goto navigation`() {
        val grammarText =
            """
            grammar Demo;

            rule
                : ID { this.IsNotIdentifierAssign() } EOF
                ;

            ID : [a-zA-Z]+ ;
            WS : [ \t\r\n]+ -> skip ;
            """
                .trimIndent()
        val generatedText =
            """
            public class DemoParser extends Parser {
                public final RuleContext rule() throws RecognitionException {
                    match(ID);
                     this.IsNotIdentifierAssign();
                    match(EOF);
                    return _localctx;
                }
            }
            """
                .trimIndent()

        val (psiFile, editor, _) =
            configureFiles(
                grammarText = grammarText,
                generatedText = generatedText,
            )

        val offset = psiFile.text.indexOf("IsNotIdentifierAssign")
        assertTrue(offset >= 0)

        val sourceElement: PsiElement? = psiFile.findElementAt(offset)
        val targets = GeneratedActionGotoDeclarationHandler().getGotoDeclarationTargets(sourceElement, offset, editor)
        assertNotNull(targets)
        val resolvedTargets = targets!!
        assertEquals(1, resolvedTargets.size)
        assertEquals(
            generatedText.indexOf("this.IsNotIdentifierAssign()"),
            resolvedTargets.single().textOffset,
        )

        val outcome = computeGtduOutcome(psiFile, editor, offset)
        assertEquals(GotoDeclarationOrUsageHandler2.GTDUOutcome.GTD, outcome)
    }

    fun `test ctrl click on members block uses normal goto navigation`() {
        val grammarText =
            """
            grammar Demo;

            @members {
                boolean helper() {
                    return true;
                }
            }

            rule : ID EOF ;
            ID : [a-zA-Z]+ ;
            WS : [ \t\r\n]+ -> skip ;
            """
                .trimIndent()
        val generatedText =
            """
            public class DemoParser extends Parser {
                boolean helper() {
                    return true;
                }

                public final RuleContext rule() throws RecognitionException {
                    match(ID);
                    match(EOF);
                    return _localctx;
                }
            }
            """
                .trimIndent()

        val (psiFile, editor, _) =
            configureFiles(
                grammarText = grammarText,
                generatedText = generatedText,
            )

        val offset = psiFile.text.indexOf("helper")
        assertTrue(offset >= 0)

        val sourceElement: PsiElement? = psiFile.findElementAt(offset)
        val targets = GeneratedActionGotoDeclarationHandler().getGotoDeclarationTargets(sourceElement, offset, editor)
        assertNotNull(targets)
        val resolvedTargets = targets!!
        assertEquals(1, resolvedTargets.size)
        assertEquals(generatedText.indexOf("boolean helper()"), resolvedTargets.single().textOffset)

        val outcome = computeGtduOutcome(psiFile, editor, offset)
        assertEquals(GotoDeclarationOrUsageHandler2.GTDUOutcome.GTD, outcome)
    }

    fun `test ctrl click on scoped parser members uses normal goto navigation`() {
        val grammarText =
            """
            grammar Demo;

            @parser::members {
                boolean helper() {
                    return true;
                }
            }

            rule : ID EOF ;
            ID : [a-zA-Z]+ ;
            WS : [ \t\r\n]+ -> skip ;
            """
                .trimIndent()
        val generatedText =
            """
            public class DemoParser extends Parser {
                boolean helper() {
                    return true;
                }

                public final RuleContext rule() throws RecognitionException {
                    match(ID);
                    match(EOF);
                    return _localctx;
                }
            }
            """
                .trimIndent()

        val (psiFile, editor, _) =
            configureFiles(
                grammarText = grammarText,
                generatedText = generatedText,
            )

        val offset = psiFile.text.indexOf("helper")
        assertTrue(offset >= 0)

        val sourceElement: PsiElement? = psiFile.findElementAt(offset)
        val targets = GeneratedActionGotoDeclarationHandler().getGotoDeclarationTargets(sourceElement, offset, editor)
        assertNotNull(targets)
        val resolvedTargets = targets!!
        assertEquals(1, resolvedTargets.size)
        assertEquals(generatedText.indexOf("boolean helper()"), resolvedTargets.single().textOffset)

        val outcome = computeGtduOutcome(psiFile, editor, offset)
        assertEquals(GotoDeclarationOrUsageHandler2.GTDUOutcome.GTD, outcome)
    }

    fun `test ctrl click on semantic predicate body uses normal goto navigation`() {
        val grammarText =
            """
            grammar Demo;

            annotationFieldValue
                : { this.IsNotIdentifierAssign() }? annotationValue
                | identifier '=' annotationValue
                ;

            annotationValue : ID ;
            identifier : ID ;
            ID : [a-zA-Z]+ ;
            WS : [ \t\r\n]+ -> skip ;
            """
                .trimIndent()
        val generatedText =
            """
            public class DemoParser extends Parser {
                public final AnnotationFieldValueContext annotationFieldValue() throws RecognitionException {
                    if (!( this.IsNotIdentifierAssign() )) throw new FailedPredicateException(this, "this.IsNotIdentifierAssign()");
                    annotationValue();
                    return _localctx;
                }
            }
            """
                .trimIndent()

        val (psiFile, editor, _) =
            configureFiles(
                grammarText = grammarText,
                generatedText = generatedText,
            )

        val offset = psiFile.text.indexOf("IsNotIdentifierAssign")
        assertTrue(offset >= 0)

        val sourceElement: PsiElement? = psiFile.findElementAt(offset)
        val targets = GeneratedActionGotoDeclarationHandler().getGotoDeclarationTargets(sourceElement, offset, editor)
        assertNotNull(targets)
        val resolvedTargets = targets!!
        assertEquals(1, resolvedTargets.size)
        assertEquals(
            generatedText.indexOf("this.IsNotIdentifierAssign()"),
            resolvedTargets.single().textOffset,
        )

        val outcome = computeGtduOutcome(psiFile, editor, offset)
        assertEquals(GotoDeclarationOrUsageHandler2.GTDUOutcome.GTD, outcome)
    }

    fun `test ctrl click on exception handler body uses normal goto navigation`() {
        val grammarText =
            """
            grammar Demo;

            rule
                : ID
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
                .trimIndent()
        val generatedText =
            """
            public class DemoParser extends Parser {
                public final RuleContext rule() throws RecognitionException {
                    try {
                        match(ID);
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
                .trimIndent()

        val (psiFile, editor, _) =
            configureFiles(
                grammarText = grammarText,
                generatedText = generatedText,
            )

        val offset = psiFile.text.indexOf("recoverHere")
        assertTrue(offset >= 0)

        val sourceElement: PsiElement? = psiFile.findElementAt(offset)
        val targets = GeneratedActionGotoDeclarationHandler().getGotoDeclarationTargets(sourceElement, offset, editor)
        assertNotNull(targets)
        val resolvedTargets = targets!!
        assertEquals(1, resolvedTargets.size)
        assertEquals(
            generatedText.indexOf("recoverHere(re, _localctx);"),
            resolvedTargets.single().textOffset,
        )

        val outcome = computeGtduOutcome(psiFile, editor, offset)
        assertEquals(GotoDeclarationOrUsageHandler2.GTDUOutcome.GTD, outcome)
    }

    fun `test ctrl click on rule after body uses normal goto navigation`() {
        val grammarText =
            """
            grammar Demo;

            rule
            @after {
                cleanup();
            }
                : ID EOF
                ;

            ID : [a-zA-Z]+ ;
            WS : [ \t\r\n]+ -> skip ;
            """
                .trimIndent()
        val generatedText =
            """
            public class DemoParser extends Parser {
                public final RuleContext rule() throws RecognitionException {
                    try {
                        match(ID);
                        match(EOF);
                    }
                    finally {
                        cleanup();
                        exitRule();
                    }
                    return _localctx;
                }
            }
            """
                .trimIndent()

        val (psiFile, editor, _) =
            configureFiles(
                grammarText = grammarText,
                generatedText = generatedText,
            )

        val offset = psiFile.text.indexOf("cleanup")
        assertTrue(offset >= 0)

        val sourceElement: PsiElement? = psiFile.findElementAt(offset)
        val targets = GeneratedActionGotoDeclarationHandler().getGotoDeclarationTargets(sourceElement, offset, editor)
        assertNotNull(targets)
        val resolvedTargets = targets!!
        assertEquals(1, resolvedTargets.size)
        assertEquals(generatedText.indexOf("cleanup();"), resolvedTargets.single().textOffset)

        val outcome = computeGtduOutcome(psiFile, editor, offset)
        assertEquals(GotoDeclarationOrUsageHandler2.GTDUOutcome.GTD, outcome)
    }

    fun `test ctrl click on rule parameter block uses normal goto navigation`() {
        val grammarText =
            """
            grammar Demo;

            expr[int level] returns [int value]
            locals [int n = 0]
                : ID EOF
                ;

            ID : [a-zA-Z]+ ;
            WS : [ \t\r\n]+ -> skip ;
            """
                .trimIndent()
        val generatedText =
            """
            public class DemoParser extends Parser {
                public static class ExprContext extends ParserRuleContext {
                    public int level;
                    public int value;
                    public int n = 0;
                    public TerminalNode ID() { return getToken(DemoParser.ID, 0); }
                    public TerminalNode EOF() { return getToken(DemoParser.EOF, 0); }
                    public ExprContext(ParserRuleContext parent, int invokingState) { super(parent, invokingState); }
                    public ExprContext(ParserRuleContext parent, int invokingState, int level) {
                        super(parent, invokingState);
                        this.level = level;
                    }
                }

                public final ExprContext expr(int level) throws RecognitionException {
                    ExprContext _localctx = new ExprContext(_ctx, getState(), level);
                    enterRule(_localctx, 0, RULE_expr);
                    try {
                        enterOuterAlt(_localctx, 1);
                        {
                        match(ID);
                        match(EOF);
                        }
                    }
                    finally {
                        exitRule();
                    }
                    return _localctx;
                }
            }
            """
                .trimIndent()

        val (psiFile, editor, _) =
            configureFiles(
                grammarText = grammarText,
                generatedText = generatedText,
            )

        val offset = psiFile.text.indexOf("int value")
        assertTrue(offset >= 0)

        val sourceElement: PsiElement? = psiFile.findElementAt(offset)
        val targets = GeneratedActionGotoDeclarationHandler().getGotoDeclarationTargets(sourceElement, offset, editor)
        assertNotNull(targets)
        val resolvedTargets = targets!!
        assertEquals(1, resolvedTargets.size)
        assertEquals(generatedText.indexOf("int value;"), resolvedTargets.single().textOffset)

        val outcome = computeGtduOutcome(psiFile, editor, offset)
        assertEquals(GotoDeclarationOrUsageHandler2.GTDUOutcome.GTD, outcome)
    }

    fun `test ctrl click on generated parser action body navigates back to grammar`() {
        val grammarText =
            """
            grammar Demo;

            rule
                : ID { this.IsNotIdentifierAssign() } EOF
                ;

            ID : [a-zA-Z]+ ;
            WS : [ \t\r\n]+ -> skip ;
            """
                .trimIndent()
        val generatedText =
            """
            public class DemoParser extends Parser {
                public final RuleContext rule() throws RecognitionException {
                    match(ID);
                     this.IsNotIdentifierAssign();
                    match(EOF);
                    return _localctx;
                }
            }
            """
                .trimIndent()

        val configured =
            configureGeneratedFiles(
                grammarText = grammarText,
                generatedText = generatedText,
            )

        val offset = configured.psiFile.text.indexOf("IsNotIdentifierAssign")
        assertTrue(offset >= 0)

        val sourceElement: PsiElement? = configured.psiFile.findElementAt(offset)
        val targets =
            GeneratedActionGotoDeclarationHandler().getGotoDeclarationTargets(sourceElement, offset, configured.editor)
        assertNotNull(targets)
        val resolvedTargets = targets!!
        assertEquals(1, resolvedTargets.size)
        assertEquals(
            grammarText.indexOf("this.IsNotIdentifierAssign()"),
            resolvedTargets.single().textOffset,
        )

        val outcome = computeGtduOutcome(configured.psiFile, configured.editor, offset)
        assertEquals(GotoDeclarationOrUsageHandler2.GTDUOutcome.GTD, outcome)
    }

    fun `test ctrl click on generated semantic predicate body navigates back to grammar`() {
        val grammarText =
            """
            grammar Demo;

            annotationFieldValue
                : { this.IsNotIdentifierAssign() }? annotationValue
                | identifier '=' annotationValue
                ;

            annotationValue : ID ;
            identifier : ID ;
            ID : [a-zA-Z]+ ;
            WS : [ \t\r\n]+ -> skip ;
            """
                .trimIndent()
        val generatedText =
            """
            public class DemoParser extends Parser {
                public final AnnotationFieldValueContext annotationFieldValue() throws RecognitionException {
                    if (!( this.IsNotIdentifierAssign() )) throw new FailedPredicateException(this, "this.IsNotIdentifierAssign()");
                    annotationValue();
                    return _localctx;
                }
            }
            """
                .trimIndent()

        val configured =
            configureGeneratedFiles(
                grammarText = grammarText,
                generatedText = generatedText,
            )

        val offset = configured.psiFile.text.indexOf("IsNotIdentifierAssign")
        assertTrue(offset >= 0)

        val sourceElement: PsiElement? = configured.psiFile.findElementAt(offset)
        val targets =
            GeneratedActionGotoDeclarationHandler().getGotoDeclarationTargets(sourceElement, offset, configured.editor)
        assertNotNull(targets)
        val resolvedTargets = targets!!
        assertEquals(1, resolvedTargets.size)
        assertEquals(
            grammarText.indexOf("this.IsNotIdentifierAssign()"),
            resolvedTargets.single().textOffset,
        )

        val outcome = computeGtduOutcome(configured.psiFile, configured.editor, offset)
        assertEquals(GotoDeclarationOrUsageHandler2.GTDUOutcome.GTD, outcome)
    }

    fun `test ctrl click on generated finally clause body navigates back to grammar`() {
        val grammarText =
            """
            grammar Demo;

            rule
                : ID
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
                .trimIndent()
        val generatedText =
            """
            public class DemoParser extends Parser {
                public final RuleContext rule() throws RecognitionException {
                    try {
                        match(ID);
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
                .trimIndent()

        val configured =
            configureGeneratedFiles(
                grammarText = grammarText,
                generatedText = generatedText,
            )

        val offset = configured.psiFile.text.indexOf("cleanup")
        assertTrue(offset >= 0)

        val sourceElement: PsiElement? = configured.psiFile.findElementAt(offset)
        val targets =
            GeneratedActionGotoDeclarationHandler().getGotoDeclarationTargets(sourceElement, offset, configured.editor)
        assertNotNull(targets)
        val resolvedTargets = targets!!
        assertEquals(1, resolvedTargets.size)
        assertEquals(grammarText.indexOf("cleanup();"), resolvedTargets.single().textOffset)

        val outcome = computeGtduOutcome(configured.psiFile, configured.editor, offset)
        assertEquals(GotoDeclarationOrUsageHandler2.GTDUOutcome.GTD, outcome)
    }

    fun `test ctrl click on generated rule call arguments navigates back to grammar`() {
        val grammarText =
            """
            grammar Demo;

            start
                : atom[1, 2] EOF
                ;

            atom[int a, int b]
                : ID
                ;

            ID : [a-zA-Z]+ ;
            WS : [ \t\r\n]+ -> skip ;
            """
                .trimIndent()
        val generatedText =
            """
            public class DemoParser extends Parser {
                public static class StartContext extends ParserRuleContext {
                    public AtomContext atom() { return getRuleContext(AtomContext.class, 0); }
                    public TerminalNode EOF() { return getToken(DemoParser.EOF, 0); }
                }

                public final StartContext start() throws RecognitionException {
                    StartContext _localctx = new StartContext(_ctx, getState());
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
                    public TerminalNode ID() { return getToken(DemoParser.ID, 0); }
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
                .trimIndent()

        val configured =
            configureGeneratedFiles(
                grammarText = grammarText,
                generatedText = generatedText,
            )

        val offset = configured.psiFile.text.indexOf("1, 2")
        assertTrue(offset >= 0)

        val sourceElement: PsiElement? = configured.psiFile.findElementAt(offset)
        val targets =
            GeneratedActionGotoDeclarationHandler().getGotoDeclarationTargets(sourceElement, offset, configured.editor)
        assertNotNull(targets)
        val resolvedTargets = targets!!
        assertEquals(1, resolvedTargets.size)
        assertEquals(grammarText.indexOf("1, 2"), resolvedTargets.single().textOffset)

        val outcome = computeGtduOutcome(configured.psiFile, configured.editor, offset)
        assertEquals(GotoDeclarationOrUsageHandler2.GTDUOutcome.GTD, outcome)
    }

    fun `test ctrl click on generated members block navigates back to grammar`() {
        val grammarText =
            """
            grammar Demo;

            @members {
                boolean helper() {
                    return true;
                }
            }

            rule : ID EOF ;
            ID : [a-zA-Z]+ ;
            WS : [ \t\r\n]+ -> skip ;
            """
                .trimIndent()
        val generatedText =
            """
            public class DemoParser extends Parser {
                boolean helper() {
                    return true;
                }

                public final RuleContext rule() throws RecognitionException {
                    match(ID);
                    match(EOF);
                    return _localctx;
                }
            }
            """
                .trimIndent()

        val configured =
            configureGeneratedFiles(
                grammarText = grammarText,
                generatedText = generatedText,
            )

        val offset = configured.psiFile.text.indexOf("helper")
        assertTrue(offset >= 0)

        val sourceElement: PsiElement? = configured.psiFile.findElementAt(offset)
        val targets =
            GeneratedActionGotoDeclarationHandler().getGotoDeclarationTargets(sourceElement, offset, configured.editor)
        assertNotNull(targets)
        val resolvedTargets = targets!!
        assertEquals(1, resolvedTargets.size)
        assertEquals(grammarText.indexOf("boolean helper()"), resolvedTargets.single().textOffset)

        val outcome = computeGtduOutcome(configured.psiFile, configured.editor, offset)
        assertEquals(GotoDeclarationOrUsageHandler2.GTDUOutcome.GTD, outcome)
    }

    fun `test ctrl click on generated lexer action body navigates back to grammar`() {
        val grammarText =
            """
            lexer grammar Demo;

            ID : 'a' { skip(); } ;
            WS : [ \t\r\n]+ -> skip ;
            """
                .trimIndent()
        val generatedText =
            """
            public class DemoLexer extends Lexer {
                private void ID_action(RuleContext _localctx, int actionIndex) {
                    switch (actionIndex) {
                    case 0:
                        skip();
                        break;
                    }
                }
            }
            """
                .trimIndent()

        val configured =
            configureGeneratedFiles(
                grammarText = grammarText,
                generatedText = generatedText,
            )

        val offset = configured.psiFile.text.indexOf("skip")
        assertTrue(offset >= 0)

        val sourceElement: PsiElement? = configured.psiFile.findElementAt(offset)
        val targets =
            GeneratedActionGotoDeclarationHandler().getGotoDeclarationTargets(sourceElement, offset, configured.editor)
        assertNotNull(targets)
        val resolvedTargets = targets!!
        assertEquals(1, resolvedTargets.size)
        assertEquals(grammarText.indexOf("skip();"), resolvedTargets.single().textOffset)

        val outcome = computeGtduOutcome(configured.psiFile, configured.editor, offset)
        assertEquals(GotoDeclarationOrUsageHandler2.GTDUOutcome.GTD, outcome)
    }

    fun `test ctrl click on generated kotlin parser action body navigates back to grammar`() {
        val grammarText =
            """
            parser grammar Demo;

            rule
                : ID { afterId() } EOF
                ;

            ID : [a-zA-Z]+ ;
            WS : [ \t\r\n]+ -> skip ;
            """
                .trimIndent()
        val generatedText =
            """
            class DemoParser {
                fun rule(): RuleContext {
                    match(ID)
                     afterId()
                    match(EOF)
                    return _localctx
                }
            }
            """
                .trimIndent()

        val configured =
            configureGeneratedFiles(
                grammarText = grammarText,
                generatedText = generatedText,
                generatedRelativePath = "gen/preview/kotlin/DemoParser.kt",
            )

        val offset = configured.psiFile.text.indexOf("afterId")
        assertTrue(offset >= 0)

        val sourceElement: PsiElement? = configured.psiFile.findElementAt(offset)
        val targets =
            GeneratedActionGotoDeclarationHandler().getGotoDeclarationTargets(sourceElement, offset, configured.editor)
        assertNotNull(targets)
        val resolvedTargets = targets!!
        assertEquals(1, resolvedTargets.size)
        assertEquals(grammarText.indexOf("afterId()"), resolvedTargets.single().textOffset)

        val outcome = computeGtduOutcome(configured.psiFile, configured.editor, offset)
        assertEquals(GotoDeclarationOrUsageHandler2.GTDUOutcome.GTD, outcome)
    }

    fun `test ctrl click in generated preview editor navigates back to grammar`() {
        val grammarText =
            """
            grammar Demo;

            rule
                : ID { this.IsNotIdentifierAssign() } EOF
                ;

            ID : [a-zA-Z]+ ;
            WS : [ \t\r\n]+ -> skip ;
            """
                .trimIndent()
        val generatedText =
            """
            public class DemoParser extends Parser {
                public final RuleContext rule() throws RecognitionException {
                    match(ID);
                     this.IsNotIdentifierAssign();
                    match(EOF);
                    return _localctx;
                }
            }
            """
                .trimIndent()

        val root = createTemporaryRoot("generated-preview-nav-")
        val grammarFile = File(root, "Demo.g4")
        val generatedFile = File(root, "gen/preview/java/DemoParser.java")

        grammarFile.writeText(grammarText)
        generatedFile.parentFile.mkdirs()
        generatedFile.writeText(generatedText)

        val generatedVFile = runWriteAction {
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(generatedFile)
        }
        assertNotNull(generatedVFile)

        project.getService(ActionLocationMapService::class.java).refreshFor(grammarFile, generatedFile)

        val providerEditor = GeneratedPreviewFileEditorProvider().createEditor(project, generatedVFile!!)
        try {
            val textEditor = providerEditor as? com.intellij.openapi.fileEditor.TextEditor
            assertNotNull(textEditor)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

            val editor = textEditor!!.editor
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
            assertNotNull(psiFile)

            val offset = psiFile!!.text.indexOf("IsNotIdentifierAssign")
            assertTrue(offset >= 0)

            val sourceElement: PsiElement? = psiFile.findElementAt(offset)
            val targets =
                GeneratedActionGotoDeclarationHandler().getGotoDeclarationTargets(sourceElement, offset, editor)
            assertNotNull(targets)
            val resolvedTargets = targets!!
            assertEquals(1, resolvedTargets.size)
            assertEquals(
                grammarText.indexOf("this.IsNotIdentifierAssign()"),
                resolvedTargets.single().textOffset,
            )

            val outcome = computeGtduOutcome(psiFile, editor, offset)
            assertEquals(GotoDeclarationOrUsageHandler2.GTDUOutcome.GTD, outcome)
        } finally {
            providerEditor.dispose()
        }
    }

    fun `test ctrl click on generated action semicolon navigates back to grammar`() {
        val grammarText =
            """
            grammar Demo;

            rule
                : ID { this.IsNotIdentifierAssign() } EOF
                ;

            ID : [a-zA-Z]+ ;
            WS : [ \t\r\n]+ -> skip ;
            """
                .trimIndent()
        val generatedText =
            """
            public class DemoParser extends Parser {
                public final RuleContext rule() throws RecognitionException {
                    match(ID);
                     this.IsNotIdentifierAssign();
                    match(EOF);
                    return _localctx;
                }
            }
            """
                .trimIndent()

        val configured =
            configureGeneratedFiles(
                grammarText = grammarText,
                generatedText = generatedText,
            )

        val semicolonOffset =
            configured.psiFile.text.indexOf("this.IsNotIdentifierAssign();") + "this.IsNotIdentifierAssign()".length
        assertTrue(semicolonOffset >= 0)
        assertEquals(';', configured.psiFile.text[semicolonOffset])

        val adjustedOffset =
            TargetElementUtil.adjustOffset(
                configured.psiFile,
                configured.editor.document,
                semicolonOffset,
            )
        val sourceElement = configured.psiFile.findElementAt(adjustedOffset)
        val targets =
            GeneratedActionGotoDeclarationHandler()
                .getGotoDeclarationTargets(sourceElement, semicolonOffset, configured.editor)
        assertNotNull(targets)
        val resolvedTargets = targets!!
        assertEquals(1, resolvedTargets.size)
        assertEquals(
            grammarText.indexOf("this.IsNotIdentifierAssign()"),
            resolvedTargets.single().textOffset,
        )

        val ctrlMouseData = computeCtrlMouseData(configured.psiFile, configured.editor, semicolonOffset)
        assertNotNull(ctrlMouseData)
        assertTrue(ctrlMouseData!!.isNavigatable)

        val outcome = computeGtduOutcome(configured.psiFile, configured.editor, semicolonOffset)
        assertEquals(GotoDeclarationOrUsageHandler2.GTDUOutcome.GTD, outcome)
    }

    fun `test ctrl click on generated preview semicolon navigates back to grammar`() {
        val grammarText =
            """
            grammar Demo;

            rule
                : ID { this.IsNotIdentifierAssign() } EOF
                ;

            ID : [a-zA-Z]+ ;
            WS : [ \t\r\n]+ -> skip ;
            """
                .trimIndent()
        val generatedText =
            """
            public class DemoParser extends Parser {
                public final RuleContext rule() throws RecognitionException {
                    match(ID);
                     this.IsNotIdentifierAssign();
                    match(EOF);
                    return _localctx;
                }
            }
            """
                .trimIndent()

        val root = createTemporaryRoot("generated-preview-semicolon-nav-")
        val grammarFile = File(root, "Demo.g4")
        val generatedFile = File(root, "gen/preview/java/DemoParser.java")

        grammarFile.writeText(grammarText)
        generatedFile.parentFile.mkdirs()
        generatedFile.writeText(generatedText)

        val generatedVFile = runWriteAction {
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(generatedFile)
        }
        assertNotNull(generatedVFile)

        project.getService(ActionLocationMapService::class.java).refreshFor(grammarFile, generatedFile)

        val providerEditor = GeneratedPreviewFileEditorProvider().createEditor(project, generatedVFile!!)
        try {
            val textEditor = providerEditor as? com.intellij.openapi.fileEditor.TextEditor
            assertNotNull(textEditor)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

            val editor = textEditor!!.editor
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
            assertNotNull(psiFile)

            val semicolonOffset =
                psiFile!!.text.indexOf("this.IsNotIdentifierAssign();") + "this.IsNotIdentifierAssign()".length
            assertTrue(semicolonOffset >= 0)
            assertEquals(';', psiFile.text[semicolonOffset])

            val adjustedOffset = TargetElementUtil.adjustOffset(psiFile, editor.document, semicolonOffset)
            val sourceElement = psiFile.findElementAt(adjustedOffset)
            val targets =
                GeneratedActionGotoDeclarationHandler()
                    .getGotoDeclarationTargets(sourceElement, semicolonOffset, editor)
            assertNotNull(targets)
            val resolvedTargets = targets!!
            assertEquals(1, resolvedTargets.size)
            assertEquals(
                grammarText.indexOf("this.IsNotIdentifierAssign()"),
                resolvedTargets.single().textOffset,
            )

            val ctrlMouseData = computeCtrlMouseData(psiFile, editor, semicolonOffset)
            assertNotNull(ctrlMouseData)
            assertTrue(ctrlMouseData!!.isNavigatable)

            val outcome = computeGtduOutcome(psiFile, editor, semicolonOffset)
            assertEquals(GotoDeclarationOrUsageHandler2.GTDUOutcome.GTD, outcome)
        } finally {
            providerEditor.dispose()
        }
    }

    fun `test ctrl click on combined grammar parser action resolves parser output when lexer map also exists`() {
        val grammarText =
            """
            grammar Demo;

            @members {
                void seenValue() { }
            }

            item
                : pair { this.seenValue(); }
                ;

            pair
                : ID
                ;

            ID : [a-zA-Z]+ ;
            WS : [ \t\r\n]+ -> skip ;
            """
                .trimIndent()
        val parserText =
            """
            public class DemoParser extends Parser {
                void seenValue() { }

                public final ItemContext item() throws RecognitionException {
                    pair();
                     this.seenValue();
                    return _localctx;
                }
            }
            """
                .trimIndent()
        val lexerText =
            """
            public class DemoLexer extends Lexer {
                void seenValue() { }
            }
            """
                .trimIndent()

        val root = createTemporaryRoot("combined-parser-nav-")
        val grammarFile = File(root, "Demo.g4")
        val g4pFile = File(root, "Demo.g4p")
        val parserFile = File(root, "gen/preview/java/DemoParser.java")
        val lexerFile = File(root, "gen/preview/java/DemoLexer.java")

        grammarFile.writeText(grammarText)
        g4pFile.writeText("input")
        parserFile.parentFile.mkdirs()
        parserFile.writeText(parserText)
        lexerFile.writeText(lexerText)

        val grammarVFile = runWriteAction {
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(grammarFile)
        }
        val g4pVFile = runWriteAction {
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(g4pFile)
        }
        assertNotNull(grammarVFile)
        assertNotNull(g4pVFile)

        val previewService = project.getService(PreviewService::class.java)
        previewService.state.g4pInputFile = g4pVFile
        previewService.state.grammarFile = grammarVFile
        previewService.state.lexerGrammarFile = null

        val mapService = project.getService(ActionLocationMapService::class.java)
        mapService.refreshFor(grammarFile, parserFile)
        mapService.refreshFor(grammarFile, lexerFile)
        assertEquals(2, mapService.getAll(grammarFile).size)

        myFixture.configureFromExistingVirtualFile(grammarVFile!!)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val editor = myFixture.editor
        FileDocumentManager.getInstance().saveDocument(editor.document)
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
        assertNotNull(psiFile)

        val offset = psiFile!!.text.indexOf("this.seenValue()") + "this.".length
        assertTrue(offset >= 0)

        val target = GeneratedActionCtrlClickSupport.resolveTarget(editor, offset)
        assertNotNull(target)
        assertEquals("DemoParser.java", target!!.file.name)
        assertEquals(parserText.indexOf("this.seenValue();"), target.offset)

        val sourceElement = psiFile.findElementAt(offset)
        val targets = GeneratedActionGotoDeclarationHandler().getGotoDeclarationTargets(sourceElement, offset, editor)
        assertNotNull(targets)
        assertEquals(1, targets!!.size)
        assertEquals(parserText.indexOf("this.seenValue();"), targets.single().textOffset)

        val kotlinParserFile = File(root, "gen/preview/kotlin/DemoParser.kt")
        kotlinParserFile.parentFile.mkdirs()
        kotlinParserFile.writeText(parserText)
        mapService.refreshFor(grammarFile, kotlinParserFile)
        assertEquals(
            setOf("DemoParser.kt", "DemoLexer.java"),
            mapService.getAll(grammarFile).map { it.generatedFile.name }.toSet(),
        )
    }

    fun `test ctrl click on grammar action opening brace navigates to generated body`() {
        val grammarText =
            """
            grammar Demo;

            rule
                : ID { this.IsNotIdentifierAssign() } EOF
                ;

            ID : [a-zA-Z]+ ;
            WS : [ \t\r\n]+ -> skip ;
            """
                .trimIndent()
        val generatedText =
            """
            public class DemoParser extends Parser {
                public final RuleContext rule() throws RecognitionException {
                    match(ID);
                     this.IsNotIdentifierAssign();
                    match(EOF);
                    return _localctx;
                }
            }
            """
                .trimIndent()

        val (psiFile, editor, _) = configureFiles(grammarText = grammarText, generatedText = generatedText)
        val offset = psiFile.text.indexOf("{ this.IsNotIdentifierAssign() }")
        assertTrue(offset >= 0)

        val sourceElement = psiFile.findElementAt(offset)
        val targets = GeneratedActionGotoDeclarationHandler().getGotoDeclarationTargets(sourceElement, offset, editor)
        assertNotNull(targets)
        assertEquals(1, targets!!.size)

        val outcome = computeGtduOutcome(psiFile, editor, offset)
        assertEquals(GotoDeclarationOrUsageHandler2.GTDUOutcome.GTD, outcome)
    }

    fun `test ctrl click on grammar action interior whitespace navigates to generated body`() {
        val grammarText =
            """
            grammar Demo;

            rule
                : ID { this.IsNotIdentifierAssign() } EOF
                ;

            ID : [a-zA-Z]+ ;
            WS : [ \t\r\n]+ -> skip ;
            """
                .trimIndent()
        val generatedText =
            """
            public class DemoParser extends Parser {
                public final RuleContext rule() throws RecognitionException {
                    match(ID);
                     this.IsNotIdentifierAssign();
                    match(EOF);
                    return _localctx;
                }
            }
            """
                .trimIndent()

        val (psiFile, editor, _) = configureFiles(grammarText = grammarText, generatedText = generatedText)
        val offset = psiFile.text.indexOf("{ this.IsNotIdentifierAssign() }") + 1
        assertTrue(offset >= 0)
        assertEquals(' ', psiFile.text[offset])

        val adjustedOffset = TargetElementUtil.adjustOffset(psiFile, editor.document, offset)
        val sourceElement = psiFile.findElementAt(adjustedOffset)
        val targets = GeneratedActionGotoDeclarationHandler().getGotoDeclarationTargets(sourceElement, offset, editor)
        assertNotNull(targets)
        assertEquals(1, targets!!.size)

        val ctrlMouseData = computeCtrlMouseData(psiFile, editor, offset)
        assertNotNull(ctrlMouseData)
        assertTrue(ctrlMouseData!!.isNavigatable)

        val outcome = computeGtduOutcome(psiFile, editor, offset)
        assertEquals(GotoDeclarationOrUsageHandler2.GTDUOutcome.GTD, outcome)
    }

    fun `test ctrl click on grammar action closing brace navigates to generated body`() {
        val grammarText =
            """
            grammar Demo;

            rule
                : ID { this.IsNotIdentifierAssign() } EOF
                ;

            ID : [a-zA-Z]+ ;
            WS : [ \t\r\n]+ -> skip ;
            """
                .trimIndent()
        val generatedText =
            """
            public class DemoParser extends Parser {
                public final RuleContext rule() throws RecognitionException {
                    match(ID);
                     this.IsNotIdentifierAssign();
                    match(EOF);
                    return _localctx;
                }
            }
            """
                .trimIndent()

        val (psiFile, editor, _) = configureFiles(grammarText = grammarText, generatedText = generatedText)
        val offset = psiFile.text.indexOf("{ this.IsNotIdentifierAssign() }") + "{ this.IsNotIdentifierAssign() ".length
        assertTrue(offset >= 0)
        assertEquals('}', psiFile.text[offset])

        val adjustedOffset = TargetElementUtil.adjustOffset(psiFile, editor.document, offset)
        val sourceElement = psiFile.findElementAt(adjustedOffset)
        val targets = GeneratedActionGotoDeclarationHandler().getGotoDeclarationTargets(sourceElement, offset, editor)
        assertNotNull(targets)
        assertEquals(1, targets!!.size)

        val ctrlMouseData = computeCtrlMouseData(psiFile, editor, offset)
        assertNotNull(ctrlMouseData)
        assertTrue(ctrlMouseData!!.isNavigatable)

        val outcome = computeGtduOutcome(psiFile, editor, offset)
        assertEquals(GotoDeclarationOrUsageHandler2.GTDUOutcome.GTD, outcome)
    }

    fun `test direct ctrl click support resolves grammar highlighted region`() {
        val grammarText =
            """
            grammar Demo;

            rule
                : ID { this.IsNotIdentifierAssign() } EOF
                ;

            ID : [a-zA-Z]+ ;
            WS : [ \t\r\n]+ -> skip ;
            """
                .trimIndent()
        val generatedText =
            """
            public class DemoParser extends Parser {
                public final RuleContext rule() throws RecognitionException {
                    match(ID);
                     this.IsNotIdentifierAssign();
                    match(EOF);
                    return _localctx;
                }
            }
            """
                .trimIndent()

        val (psiFile, editor, _) = configureFiles(grammarText = grammarText, generatedText = generatedText)
        val offset = psiFile.text.indexOf("{ this.IsNotIdentifierAssign() }") + 1
        assertTrue(offset >= 0)

        val target = GeneratedActionCtrlClickSupport.resolveTarget(editor, offset)
        assertNotNull(target)
        assertEquals("DemoParser.java", target!!.file.name)
        assertEquals(generatedText.indexOf("this.IsNotIdentifierAssign()"), target.offset)
    }

    fun `test direct ctrl click support resolves generated preview highlighted region`() {
        val grammarText =
            """
            grammar Demo;

            rule
                : ID { this.IsNotIdentifierAssign() } EOF
                ;

            ID : [a-zA-Z]+ ;
            WS : [ \t\r\n]+ -> skip ;
            """
                .trimIndent()
        val generatedText =
            """
            public class DemoParser extends Parser {
                public final RuleContext rule() throws RecognitionException {
                    match(ID);
                     this.IsNotIdentifierAssign();
                    match(EOF);
                    return _localctx;
                }
            }
            """
                .trimIndent()

        val root = createTemporaryRoot("generated-preview-direct-ctrl-")
        val grammarFile = File(root, "Demo.g4")
        val generatedFile = File(root, "gen/preview/java/DemoParser.java")

        grammarFile.writeText(grammarText)
        generatedFile.parentFile.mkdirs()
        generatedFile.writeText(generatedText)

        val generatedVFile = runWriteAction {
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(generatedFile)
        }
        assertNotNull(generatedVFile)

        project.getService(ActionLocationMapService::class.java).refreshFor(grammarFile, generatedFile)

        val providerEditor = GeneratedPreviewFileEditorProvider().createEditor(project, generatedVFile!!)
        try {
            val textEditor = providerEditor as? com.intellij.openapi.fileEditor.TextEditor
            assertNotNull(textEditor)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

            val editor = textEditor!!.editor
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
            assertNotNull(psiFile)

            val offset = psiFile!!.text.indexOf("this.IsNotIdentifierAssign();") + "this.".length
            assertTrue(offset >= 0)

            val target = GeneratedActionCtrlClickSupport.resolveTarget(editor, offset)
            assertNotNull(target)
            assertEquals("Demo.g4", target!!.file.name)
            assertEquals(grammarText.indexOf("this.IsNotIdentifierAssign()"), target.offset)
        } finally {
            providerEditor.dispose()
        }
    }

    private fun configureFiles(
        grammarText: String,
        generatedText: String,
    ): Triple<com.intellij.psi.PsiFile, com.intellij.openapi.editor.Editor, File> {
        val root = createTemporaryRoot("grammar-nav-")
        val grammarFile = File(root, "Demo.g4")
        val generatedFile = File(root, "gen/preview/java/DemoParser.java")

        grammarFile.writeText(grammarText)
        generatedFile.parentFile.mkdirs()
        generatedFile.writeText(generatedText)

        val grammarVFile = runWriteAction {
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(grammarFile)
        }
        assertNotNull(grammarVFile)

        myFixture.configureFromExistingVirtualFile(grammarVFile!!)
        val editor = myFixture.editor
        FileDocumentManager.getInstance().saveDocument(editor.document)
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
        assertNotNull(psiFile)

        project.getService(ActionLocationMapService::class.java).refreshFor(grammarFile, generatedFile)
        return Triple(psiFile!!, editor, generatedFile)
    }

    private fun computeGtduOutcome(
        psiFile: PsiFile,
        editor: com.intellij.openapi.editor.Editor,
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

    private fun computeCtrlMouseData(
        psiFile: PsiFile,
        editor: com.intellij.openapi.editor.Editor,
        offset: Int,
    ): CtrlMouseData? {
        val future =
            ApplicationManager.getApplication().executeOnPooledThread<CtrlMouseData?> {
                ReadAction.compute<CtrlMouseData?, RuntimeException> {
                    GotoDeclarationOrUsageHandler2.getCtrlMouseData(editor, psiFile, offset)
                }
            }
        return future.get()
    }

    private fun configureGeneratedFiles(
        grammarText: String,
        generatedText: String,
        generatedRelativePath: String = "gen/preview/java/DemoParser.java",
    ): ConfiguredGeneratedFiles {
        val root = createTemporaryRoot("generated-nav-")
        val grammarFile = File(root, "Demo.g4")
        val generatedFile = File(root, generatedRelativePath)

        grammarFile.writeText(grammarText)
        generatedFile.parentFile.mkdirs()
        generatedFile.writeText(generatedText)

        val generatedVFile = runWriteAction {
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(generatedFile)
        }
        assertNotNull(generatedVFile)

        project.getService(ActionLocationMapService::class.java).refreshFor(grammarFile, generatedFile)

        myFixture.configureFromExistingVirtualFile(generatedVFile!!)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val editor = myFixture.editor
        FileDocumentManager.getInstance().saveDocument(editor.document)
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
        assertNotNull(psiFile)

        return ConfiguredGeneratedFiles(
            psiFile = psiFile!!,
            editor = editor,
        )
    }

    private data class ConfiguredGeneratedFiles(
        val psiFile: PsiFile,
        val editor: com.intellij.openapi.editor.Editor,
    )

    private fun createTemporaryRoot(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also(temporaryRoots::add)
}
