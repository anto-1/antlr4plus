package com.antlr4plus.dotG4Support.actionInlay

import com.antlr4plus.actions.SyncGeneratedActionToGrammarAction
import com.antlr4plus.filebinding.FileBindingService
import com.antlr4plus.parsing.actionMap.ActionLocationMap
import com.antlr4plus.parsing.actionMap.ActionLocationMapService
import com.antlr4plus.parsing.actionMap.GeneratedSyncPreviewRefreshSupport
import com.antlr4plus.previewEditor.GeneratedPreviewEditorSupport
import com.antlr4plus.previewEditor.GeneratedPreviewFileEditorProvider
import com.antlr4plus.previewService.PreviewService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class GeneratedActionRegionHighlighterTest : BasePlatformTestCase() {
    private val temporaryRoots = mutableListOf<File>()

    override fun setUp() {
        super.setUp()
        project.getService(PreviewService::class.java).isEnabled = true
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    override fun tearDown() {
        try {
            temporaryRoots.forEach(File::deleteRecursively)
            temporaryRoots.clear()
        } finally {
            super.tearDown()
        }
    }

    fun `test generated java editor rebuilds session map and gets region highlights`() {
        val grammarText =
            """
            grammar Demo;

            rule
                : { this.isReady() }? ID { this.afterId() } EOF
                ;

            ID : [a-zA-Z]+ ;
            WS : [ \t\r\n]+ -> skip ;
            """
                .trimIndent()
        val generatedText =
            """
            public class DemoParser extends Parser {
                public final RuleContext rule() throws RecognitionException {
                    if (!( this.isReady() )) throw new FailedPredicateException(this, "this.isReady()");
                    match(ID);
                     this.afterId();
                    match(EOF);
                    return _localctx;
                }
            }
            """
                .trimIndent()

        val configured = configureFiles(grammarText, generatedText)
        assertFalse(File(configured.generatedFile.parentFile, ".antlr4plus").exists())
        assertEquals(generatedText, configured.generatedFile.readText())

        val mapService = project.getService(ActionLocationMapService::class.java)
        mapService.clearAll()
        val rebuiltMap = mapService.getByGeneratedFile(configured.generatedFile)
        assertNotNull(rebuiltMap)
        assertEquals(configured.map.entries, rebuiltMap!!.entries)

        val editor = configured.editor
        val ourHighlighters = waitForGeneratedHighlighterCount(editor, expectedCount = 2)

        val expectedRanges =
            rebuiltMap.entries
                .sortedBy { it.generatedBodyRange.first }
                .map { it.generatedBodyRange.first to (it.generatedBodyRange.last + 1) }

        assertEquals(expectedRanges.map { it.first }, ourHighlighters.map { it.startOffset })
        assertEquals(expectedRanges.map { it.second }, ourHighlighters.map { it.endOffset })
        assertTrue(
            ourHighlighters.all {
                (it.errorStripeTooltip as? String)?.startsWith("Generated counterpart:") == true
            },
        )
        assertTrue(ourHighlighters.all { it.gutterIconRenderer == null })
        assertTrue(ourHighlighters.all { it.getTextAttributes(editor.colorsScheme)?.backgroundColor != null })
        assertTrue(ourHighlighters.all { it.getTextAttributes(editor.colorsScheme)?.effectType == null })
    }

    fun `test disabling plugin clears generated action highlights and re-enabling restores them`() {
        val grammarText =
            """
            grammar Demo;
            rule : ID { afterId(); } EOF;
            ID : [a-zA-Z]+;
            """
                .trimIndent()
        val generatedText =
            """
            public class DemoParser extends Parser {
                void rule() {
                    match(ID);
                     afterId();
                    match(EOF);
                }
            }
            """
                .trimIndent()
        val configured = configureFiles(grammarText, generatedText)
        waitForGeneratedHighlighterCount(configured.editor, expectedCount = 1)

        val pluginTransient =
            configured.editor.markupModel
                .addRangeHighlighter(
                    0,
                    1,
                    com.intellij.openapi.editor.markup.HighlighterLayer.SELECTION,
                    null,
                    com.intellij.openapi.editor.markup.HighlighterTargetArea.EXACT_RANGE,
                )
                .apply {
                    putUserData(PreviewService.CARET_TOKEN_HIGHLIGHT_KEY, true)
                }
        val foreignHighlighter =
            configured.editor.markupModel.addRangeHighlighter(
                1,
                2,
                com.intellij.openapi.editor.markup.HighlighterLayer.SELECTION,
                null,
                com.intellij.openapi.editor.markup.HighlighterTargetArea.EXACT_RANGE,
            )

        project.getService(PreviewService::class.java).isEnabled = false
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        waitForGeneratedHighlighterCount(configured.editor, expectedCount = 0)
        assertFalse(pluginTransient.isValid)
        assertTrue(foreignHighlighter.isValid)

        project.getService(PreviewService::class.java).isEnabled = true
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        waitForGeneratedHighlighterCount(configured.editor, expectedCount = 1)
    }

    fun `test editing generated java action body only syncs after manual action`() {
        val grammarText =
            """
            grammar Demo;

            rule
                : ID { first(); } EOF
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
                     first();
                    match(EOF);
                    return _localctx;
                }
            }
            """
                .trimIndent()

        val configured = configureFiles(grammarText, generatedText)
        val entry = configured.map.entries.single()

        WriteCommandAction.runWriteCommandAction(project) {
            configured.editor.document.replaceString(
                entry.generatedBodyRange.first,
                entry.generatedBodyRange.last + 1,
                "second();",
            )
        }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        val grammarDocument = FileDocumentManager.getInstance().getDocument(configured.grammarVFile)
        assertNotNull(grammarDocument)
        assertFalse(grammarDocument!!.text.contains("{ second(); }"))
        assertNull(
            project.getService(ActionLocationMapService::class.java).peekPendingGeneratedSync(configured.grammarFile),
        )

        syncHighlightedBlock(configured.editor, entry.generatedBodyRange.first)
        assertTrue(grammarDocument.text.contains("{ second(); }"))

        val pending =
            project.getService(ActionLocationMapService::class.java).peekPendingGeneratedSync(configured.grammarFile)
        assertNotNull(pending)
        assertEquals(
            configured.g4pVFile.path,
            GeneratedSyncPreviewRefreshSupport.resolvePendingRefreshTarget(
                    project,
                    configured.grammarVFile,
                )
                ?.path,
        )
    }

    fun `test typing into empty generated slot only syncs after manual action`() {
        val grammarText =
            """
            grammar Demo;

            rule
                : ID {} EOF
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

                    match(EOF);
                    return _localctx;
                }
            }
            """
                .trimIndent()

        val configured = configureFiles(grammarText, generatedText)
        val entry = configured.map.entries.single()
        assertTrue(entry.generatedBodyRange.isEmpty())

        WriteCommandAction.runWriteCommandAction(project) {
            configured.editor.document.insertString(
                entry.generatedBodyRange.first,
                "createdLater()",
            )
        }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        val grammarDocument = FileDocumentManager.getInstance().getDocument(configured.grammarVFile)
        assertNotNull(grammarDocument)
        assertFalse(grammarDocument!!.text.contains("{createdLater()}"))

        syncHighlightedBlock(configured.editor, entry.generatedBodyRange.first)
        assertTrue(grammarDocument.text.contains("{createdLater()}"))
    }

    fun `test generated preview editor uses synthetic document backed by the real generated file`() {
        val grammarText =
            """
            grammar Demo;

            rule
                : ID { first(); } EOF
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
                     first();
                    match(EOF);
                    return _localctx;
                }
            }
            """
                .trimIndent()

        val prepared = prepareFiles(grammarText, generatedText)
        val providerEditor = GeneratedPreviewFileEditorProvider().createEditor(project, prepared.generatedVFile)
        try {
            val textEditor = providerEditor as? com.intellij.openapi.fileEditor.TextEditor
            assertNotNull(textEditor)

            val realGeneratedDocument = FileDocumentManager.getInstance().getDocument(prepared.generatedVFile)
            assertNotNull(realGeneratedDocument)

            assertNotSame(realGeneratedDocument, textEditor!!.editor.document)
            assertEquals(
                prepared.generatedVFile.path,
                GeneratedPreviewEditorSupport.resolveBackingFile(textEditor.editor.document)?.path,
            )
            assertSame(prepared.generatedVFile, providerEditor.file)
            assertEquals(generatedText, textEditor.editor.document.text)
            waitUntil("generated preview editor has finished asynchronous initialization") {
                com.intellij.openapi.fileEditor.impl.text.AsyncEditorLoader.isEditorLoaded(
                    textEditor.editor,
                )
            }
        } finally {
            providerEditor.dispose()
        }
    }

    fun `test editing generated preview editor only syncs after manual action`() {
        val grammarText =
            """
            grammar Demo;

            rule
                : ID { first(); } EOF
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
                     first();
                    match(EOF);
                    return _localctx;
                }
            }
            """
                .trimIndent()

        val prepared = prepareFiles(grammarText, generatedText)
        val providerEditor = GeneratedPreviewFileEditorProvider().createEditor(project, prepared.generatedVFile)
        try {
            val textEditor = providerEditor as? com.intellij.openapi.fileEditor.TextEditor
            assertNotNull(textEditor)

            waitForGeneratedHighlighterCount(
                textEditor!!.editor,
                expectedCount = prepared.map.entries.size,
            )

            val entry = prepared.map.entries.single()
            WriteCommandAction.runWriteCommandAction(project) {
                textEditor.editor.document.replaceString(
                    entry.generatedBodyRange.first,
                    entry.generatedBodyRange.last + 1,
                    "second();",
                )
            }

            val grammarDocument = requireNotNull(FileDocumentManager.getInstance().getDocument(prepared.grammarVFile))
            assertFalse(grammarDocument.text.contains("{ second(); }"))

            syncHighlightedBlock(textEditor.editor, entry.generatedBodyRange.first)
            waitUntil("manual generated preview sync updates grammar body") {
                grammarDocument.text.contains("{ second(); }")
            }

            val backingGeneratedDocument =
                requireNotNull(FileDocumentManager.getInstance().getDocument(prepared.generatedVFile))
            assertTrue(backingGeneratedDocument.text.contains("second();"))
        } finally {
            providerEditor.dispose()
        }
    }

    fun `test generated edit records bound g4p owner when active preview points elsewhere`() {
        val grammarText =
            """
            grammar Demo;

            rule
                : ID { first(); } EOF
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
                     first();
                    match(EOF);
                    return _localctx;
                }
            }
            """
                .trimIndent()

        val prepared = prepareFilesWithSecondaryBinding(grammarText, generatedText)
        val previewService = project.getService(PreviewService::class.java)
        previewService.state.g4pInputFile = prepared.primaryG4pVFile
        previewService.state.grammarFile = prepared.grammarVFile
        previewService.state.lexerGrammarFile = null

        myFixture.configureFromExistingVirtualFile(prepared.generatedVFile)
        waitForGeneratedHighlighterCount(myFixture.editor, prepared.map.entries.size)

        val entry = prepared.map.entries.single()
        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.replaceString(
                entry.generatedBodyRange.first,
                entry.generatedBodyRange.last + 1,
                "second();",
            )
        }
        settleEdt()

        assertNull(
            project.getService(ActionLocationMapService::class.java).peekPendingGeneratedSync(prepared.grammarFile),
        )

        syncHighlightedBlock(myFixture.editor, entry.generatedBodyRange.first)

        val pending =
            project.getService(ActionLocationMapService::class.java).peekPendingGeneratedSync(prepared.grammarFile)
        assertNotNull(pending)
        assertEquals(prepared.secondaryG4pVFile.path, pending!!.preferredG4pPath)
        assertEquals(
            prepared.secondaryG4pVFile.path,
            GeneratedSyncPreviewRefreshSupport.resolvePendingRefreshTarget(
                    project,
                    prepared.grammarVFile,
                )
                ?.path,
        )
    }

    fun `test semantic predicate sync strips trailing semicolon before writing grammar`() {
        val grammarText =
            """
            grammar Demo;

            rule
                : { this.isReady() }? ID
                ;

            ID : [a-zA-Z]+ ;
            WS : [ \t\r\n]+ -> skip ;
            """
                .trimIndent()
        val generatedText =
            """
            public class DemoParser extends Parser {
                public final RuleContext rule() throws RecognitionException {
                    if (!( this.isReady() )) throw new FailedPredicateException(this, "this.isReady()");
                    match(ID);
                    return _localctx;
                }
            }
            """
                .trimIndent()

        val configured = configureFiles(grammarText, generatedText)
        val entry = configured.map.entries.single()
        assertEquals("Semantic Predicate", entry.kind)

        WriteCommandAction.runWriteCommandAction(project) {
            configured.editor.document.replaceString(
                entry.generatedBodyRange.first,
                entry.generatedBodyRange.last + 1,
                "this.isReady();",
            )
        }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        val grammarDocument = FileDocumentManager.getInstance().getDocument(configured.grammarVFile)
        assertNotNull(grammarDocument)
        assertTrue(grammarDocument!!.text.contains("{ this.isReady() }?"))

        syncHighlightedBlock(configured.editor, entry.generatedBodyRange.first)
        assertTrue(grammarDocument.text.contains("{ this.isReady() }?"))
        assertFalse(grammarDocument.text.contains("{ this.isReady(); }?"))
    }

    fun `test editing generated java outside mapped code blocks does not sync to grammar`() {
        val grammarText =
            """
            grammar Demo;

            rule
                : ID { first(); } EOF
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
                     first();
                    match(EOF);
                    return _localctx;
                }
            }
            """
                .trimIndent()

        val configured = configureFiles(grammarText, generatedText)
        val grammarDocument = FileDocumentManager.getInstance().getDocument(configured.grammarVFile)
        assertNotNull(grammarDocument)
        val originalGrammarText = grammarDocument!!.text

        WriteCommandAction.runWriteCommandAction(project) {
            configured.editor.document.insertString(0, "// outside mapped region\n")
        }
        settleEdt()

        assertManualSyncUnavailable(configured.editor, 0)
        assertEquals(originalGrammarText, grammarDocument.text)
        assertNull(
            project.getService(ActionLocationMapService::class.java).peekPendingGeneratedSync(configured.grammarFile),
        )
    }

    fun `test manual sync action requires caret or selection inside a mapped block`() {
        val grammarText =
            """
            grammar Demo;

            rule
                : ID { first(); } EOF
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
                     first();
                    match(EOF);
                    return _localctx;
                }
            }
            """
                .trimIndent()

        val configured = configureFiles(grammarText, generatedText)
        val entry = configured.map.entries.single()
        val selectionStart = entry.generatedBodyRange.first
        val selectionEnd = (entry.generatedBodyRange.first + 3).coerceAtMost(entry.generatedBodyRange.last + 1)
        assertManualSyncAvailable(configured.editor, selectionStart, selectionEnd)

        val grammarDocument = FileDocumentManager.getInstance().getDocument(configured.grammarVFile)
        assertNotNull(grammarDocument)
        assertTrue(grammarDocument!!.text.contains("{ first(); }"))

        assertManualSyncUnavailable(
            editor = configured.editor,
            caretOffset = entry.generatedBodyRange.first - 1,
        )
        assertManualSyncUnavailable(
            editor = configured.editor,
            caretOffset = selectionStart,
            selectionEndExclusive = entry.generatedBodyRange.last + 2,
        )
    }

    fun `test session map rebuild drops entries missing from changed generated file`() {
        val grammarText =
            """
            grammar Demo;

            rule
                : ID { first(); } EOF
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
                     first();
                    match(EOF);
                    return _localctx;
                }
            }
            """
                .trimIndent()

        val configured = configureFiles(grammarText, generatedText)
        configured.generatedFile.writeText(generatedText.replace("first();", "externalChange();"))
        runWriteAction {
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(configured.generatedFile)
        }

        val mapService = project.getService(ActionLocationMapService::class.java)
        mapService.clearAll()
        val rebuilt = mapService.getByGeneratedFile(configured.generatedFile)
        assertNotNull(rebuilt)
        assertTrue(rebuilt!!.entries.isEmpty())
    }

    fun `test backing generated document refresh updates preview editor without syncing grammar`() {
        val grammarText =
            """
            grammar Demo;

            rule
                : ID { first(); } EOF
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
                     first();
                    match(EOF);
                    return _localctx;
                }
            }
            """
                .trimIndent()

        val prepared = prepareFiles(grammarText, generatedText)
        val providerEditor = GeneratedPreviewFileEditorProvider().createEditor(project, prepared.generatedVFile)
        try {
            val textEditor = providerEditor as? com.intellij.openapi.fileEditor.TextEditor
            assertNotNull(textEditor)

            val grammarDocument = FileDocumentManager.getInstance().getDocument(prepared.grammarVFile)
            val backingGeneratedDocument = FileDocumentManager.getInstance().getDocument(prepared.generatedVFile)
            assertNotNull(grammarDocument)
            assertNotNull(backingGeneratedDocument)
            val originalGrammarText = grammarDocument!!.text

            WriteCommandAction.runWriteCommandAction(project) {
                backingGeneratedDocument!!.replaceString(
                    prepared.map.entries.single().generatedBodyRange.first,
                    prepared.map.entries.single().generatedBodyRange.last + 1,
                    "regenerated();",
                )
            }
            settleEdt()

            assertTrue(textEditor!!.editor.document.text.contains("regenerated();"))
            assertEquals(originalGrammarText, grammarDocument.text)
            assertNull(
                project.getService(ActionLocationMapService::class.java).peekPendingGeneratedSync(prepared.grammarFile),
            )
        } finally {
            providerEditor.dispose()
        }
    }

    fun `test generated kotlin editor gets region background highlights and only syncs manually`() {
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
            configureFiles(
                grammarText = grammarText,
                generatedText = generatedText,
                generatedRelativePath = "gen/preview/kotlin/DemoParser.kt",
            )

        val highlighters = waitForGeneratedHighlighterCount(configured.editor, expectedCount = 1)
        assertTrue(
            highlighters.all {
                (it.errorStripeTooltip as? String)?.startsWith("Generated counterpart:") == true
            },
        )
        assertTrue(highlighters.all { it.gutterIconRenderer == null })
        assertTrue(
            highlighters.all { it.getTextAttributes(configured.editor.colorsScheme)?.backgroundColor != null },
        )
        assertTrue(highlighters.all { it.getTextAttributes(configured.editor.colorsScheme)?.effectType == null })

        val entry = configured.map.entries.single()
        WriteCommandAction.runWriteCommandAction(project) {
            configured.editor.document.replaceString(
                entry.generatedBodyRange.first,
                entry.generatedBodyRange.last + 1,
                "afterIdUpdated()",
            )
        }
        settleEdt()

        val grammarDocument = FileDocumentManager.getInstance().getDocument(configured.grammarVFile)
        assertNotNull(grammarDocument)
        assertFalse(grammarDocument!!.text.contains("{ afterIdUpdated() }"))

        syncHighlightedBlock(configured.editor, entry.generatedBodyRange.first)
        assertTrue(grammarDocument.text.contains("{ afterIdUpdated() }"))
    }

    fun `test generated split editors keep identical highlight offsets`() {
        val grammarText =
            """
            grammar Demo;

            rule
                : { this.isReady() }? ID { this.afterId() } EOF
                ;

            ID : [a-zA-Z]+ ;
            WS : [ \t\r\n]+ -> skip ;
            """
                .trimIndent()
        val generatedText =
            """
            public class DemoParser extends Parser {
                public final RuleContext rule() throws RecognitionException {
                    if (!( this.isReady() )) throw new FailedPredicateException(this, "this.isReady()");
                    match(ID);
                     this.afterId();
                    match(EOF);
                    return _localctx;
                }
            }
            """
                .trimIndent()

        val configured = configureFiles(grammarText, generatedText)
        val primaryEditor = configured.editor
        val primaryHighlighters = waitForGeneratedHighlighterCount(primaryEditor, expectedCount = 2)

        val secondaryEditor =
            EditorFactory.getInstance().createEditor(primaryEditor.document, project, configured.generatedVFile, false)
        try {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

            val secondaryHighlighters = waitForGeneratedHighlighterCount(secondaryEditor, expectedCount = 2)
            assertEquals(
                primaryHighlighters.map { it.startOffset to it.endOffset },
                secondaryHighlighters.map { it.startOffset to it.endOffset },
            )
        } finally {
            EditorFactory.getInstance().releaseEditor(secondaryEditor)
        }
    }

    private fun prepareFiles(
        grammarText: String,
        generatedText: String,
        generatedRelativePath: String = "gen/preview/java/DemoParser.java",
    ): PreparedFiles {
        val root = createTemporaryRoot("generated-sync-")

        val grammarFile = File(root, "Demo.g4")
        val g4pFile = File(root, "Demo.g4p")
        val generatedFile = File(root, generatedRelativePath)
        grammarFile.writeText(grammarText)
        g4pFile.writeText("input")
        generatedFile.parentFile.mkdirs()
        generatedFile.writeText(generatedText)

        val grammarVFile =
            requireNotNull(
                runWriteAction {
                    LocalFileSystem.getInstance().refreshAndFindFileByIoFile(grammarFile)
                },
            )
        val g4pVFile =
            requireNotNull(
                runWriteAction {
                    LocalFileSystem.getInstance().refreshAndFindFileByIoFile(g4pFile)
                },
            )
        val generatedVFile =
            requireNotNull(
                runWriteAction {
                    LocalFileSystem.getInstance().refreshAndFindFileByIoFile(generatedFile)
                },
            )

        val bindingService = project.getService(FileBindingService::class.java)
        assertTrue(bindingService.bind(g4pVFile, grammarVFile))

        val mapService = project.getService(ActionLocationMapService::class.java)
        mapService.refreshFor(grammarFile, generatedFile)
        val map = requireNotNull(mapService.get(grammarFile))

        return PreparedFiles(
            grammarFile = grammarFile,
            grammarVFile = grammarVFile,
            g4pVFile = g4pVFile,
            generatedFile = generatedFile,
            generatedVFile = generatedVFile,
            map = map,
        )
    }

    private fun configureFiles(
        grammarText: String,
        generatedText: String,
        generatedRelativePath: String = "gen/preview/java/DemoParser.java",
    ): ConfiguredFiles {
        val prepared = prepareFiles(grammarText, generatedText, generatedRelativePath)

        myFixture.configureFromExistingVirtualFile(prepared.generatedVFile)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        waitForGeneratedHighlighterCount(myFixture.editor, prepared.map.entries.size)

        return ConfiguredFiles(
            grammarFile = prepared.grammarFile,
            grammarVFile = prepared.grammarVFile,
            g4pVFile = prepared.g4pVFile,
            generatedFile = prepared.generatedFile,
            generatedVFile = prepared.generatedVFile,
            editor = myFixture.editor,
            map = prepared.map,
        )
    }

    private fun prepareFilesWithSecondaryBinding(
        grammarText: String,
        generatedText: String,
    ): MultiBoundPreparedFiles {
        val root = createTemporaryRoot("generated-sync-multi-")
        val primaryDir = File(root, "primary")
        val secondaryDir = File(root, "secondary")
        primaryDir.mkdirs()
        secondaryDir.mkdirs()

        val grammarFile = File(root, "Demo.g4")
        val primaryG4pFile = File(primaryDir, "DemoPrimary.g4p")
        val secondaryG4pFile = File(secondaryDir, "DemoSecondary.g4p")
        val generatedFile = File(secondaryDir, "gen/preview/java/DemoParser.java")

        grammarFile.writeText(grammarText)
        primaryG4pFile.writeText("input primary")
        secondaryG4pFile.writeText("input secondary")
        generatedFile.parentFile.mkdirs()
        generatedFile.writeText(generatedText)

        val grammarVFile = runWriteAction {
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(grammarFile)
        }
        val primaryG4pVFile = runWriteAction {
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(primaryG4pFile)
        }
        val secondaryG4pVFile = runWriteAction {
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(secondaryG4pFile)
        }
        val generatedVFile = runWriteAction {
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(generatedFile)
        }
        assertNotNull(grammarVFile)
        assertNotNull(primaryG4pVFile)
        assertNotNull(secondaryG4pVFile)
        assertNotNull(generatedVFile)

        val bindingService = project.getService(FileBindingService::class.java)
        assertTrue(bindingService.bind(primaryG4pVFile!!, grammarVFile!!))
        assertTrue(bindingService.bind(secondaryG4pVFile!!, grammarVFile))

        val mapService = project.getService(ActionLocationMapService::class.java)
        mapService.refreshFor(grammarFile, generatedFile)
        val map = mapService.get(grammarFile)
        assertNotNull(map)

        return MultiBoundPreparedFiles(
            grammarFile = grammarFile,
            grammarVFile = grammarVFile,
            primaryG4pVFile = primaryG4pVFile,
            secondaryG4pVFile = secondaryG4pVFile,
            generatedFile = generatedFile,
            generatedVFile = generatedVFile!!,
            map = map!!,
        )
    }

    private fun waitForGeneratedHighlighterCount(
        editor: com.intellij.openapi.editor.Editor,
        expectedCount: Int,
    ): List<com.intellij.openapi.editor.markup.RangeHighlighter> {
        var result = emptyList<com.intellij.openapi.editor.markup.RangeHighlighter>()
        waitUntil("generated highlighter count reaches $expectedCount") {
            val markupModel = DocumentMarkupModel.forDocument(editor.document, project, false)
            result =
                (markupModel?.allHighlighters ?: emptyArray())
                    .filter {
                        it.getUserData(GeneratedActionRegionHighlighter.OUR_HIGHLIGHTER_KEY) == true
                    }
                    .sortedBy { it.startOffset }
            result.size == expectedCount
        }
        return result
    }

    private fun createTemporaryRoot(prefix: String): File =
        File(project.basePath!!, "$prefix${System.nanoTime()}").also {
            assertTrue(it.mkdirs())
            temporaryRoots += it
        }

    private fun settleEdt(waitMs: Long = 300) {
        Thread.sleep(waitMs)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private fun syncHighlightedBlock(
        editor: com.intellij.openapi.editor.Editor,
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
        settleEdt()
    }

    private fun assertManualSyncUnavailable(
        editor: com.intellij.openapi.editor.Editor,
        caretOffset: Int,
        selectionEndExclusive: Int? = null,
    ) {
        val action = updateManualSyncAction(editor, caretOffset, selectionEndExclusive)
        assertFalse(action.presentation.isEnabledAndVisible)
    }

    private fun assertManualSyncAvailable(
        editor: com.intellij.openapi.editor.Editor,
        caretOffset: Int,
        selectionEndExclusive: Int? = null,
    ) {
        waitUntil("manual generated-action sync to become available") {
            updateManualSyncAction(editor, caretOffset, selectionEndExclusive).presentation.isEnabledAndVisible
        }
    }

    private fun updateManualSyncAction(
        editor: com.intellij.openapi.editor.Editor,
        caretOffset: Int,
        selectionEndExclusive: Int?,
    ): AnActionEvent {
        positionEditor(editor, caretOffset, selectionEndExclusive)

        val action = SyncGeneratedActionToGrammarAction()
        val event = createEditorActionEvent(action, editor)
        action.update(event)
        return event
    }

    private fun positionEditor(
        editor: com.intellij.openapi.editor.Editor,
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

    private fun createEditorActionEvent(
        action: AnAction,
        editor: com.intellij.openapi.editor.Editor,
    ): AnActionEvent {
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

    private data class PreparedFiles(
        val grammarFile: File,
        val grammarVFile: com.intellij.openapi.vfs.VirtualFile,
        val g4pVFile: com.intellij.openapi.vfs.VirtualFile,
        val generatedFile: File,
        val generatedVFile: com.intellij.openapi.vfs.VirtualFile,
        val map: ActionLocationMap,
    )

    private data class ConfiguredFiles(
        val grammarFile: File,
        val grammarVFile: com.intellij.openapi.vfs.VirtualFile,
        val g4pVFile: com.intellij.openapi.vfs.VirtualFile,
        val generatedFile: File,
        val generatedVFile: com.intellij.openapi.vfs.VirtualFile,
        val editor: com.intellij.openapi.editor.Editor,
        val map: ActionLocationMap,
    )

    private data class MultiBoundPreparedFiles(
        val grammarFile: File,
        val grammarVFile: com.intellij.openapi.vfs.VirtualFile,
        val primaryG4pVFile: com.intellij.openapi.vfs.VirtualFile,
        val secondaryG4pVFile: com.intellij.openapi.vfs.VirtualFile,
        val generatedFile: File,
        val generatedVFile: com.intellij.openapi.vfs.VirtualFile,
        val map: ActionLocationMap,
    )
}
