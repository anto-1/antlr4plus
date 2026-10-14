package com.antlr4plus.toolWindow

import com.antlr4plus.filebinding.BindingOperations
import com.antlr4plus.filebinding.FileAttributeHelper
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable

class BindingEditorPaneTest : BasePlatformTestCase() {

    fun `test panel restores staged content instead of loading the bundled corpus`() {
        val stagedContent = "examples/input.g4p -> grammars/Demo.g4"
        PropertiesComponent.getInstance(project)
            .setValue(
                "com.antlr4plus.bindingEditorContent",
                stagedContent,
            )
        val editorsBefore = EditorFactory.getInstance().allEditors.toSet()

        BindingEditorPane.createPane(project, testRootDisposable)
        val editor = (EditorFactory.getInstance().allEditors.toSet() - editorsBefore).single() as EditorEx

        assertEquals(stagedContent, editor.document.text)
        assertTrue(
            "A normal staging document should not contain the 20k-line fixture",
            editor.document.lineCount < 100,
        )
    }

    fun `test panel path highlighting resolves files off the EDT`() {
        myFixture.addFileToProject("grammars/Demo.g4", "grammar Demo; start: EOF;")
        myFixture.addFileToProject("examples/input.txt.g4p", "input")
        val editorsBefore = EditorFactory.getInstance().allEditors.toSet()

        BindingEditorPane.createPane(project, testRootDisposable)
        val editor = (EditorFactory.getInstance().allEditors.toSet() - editorsBefore).single() as EditorEx

        runWriteAction {
            editor.document.setText("examples/input.txt.g4p -> grammars/Demo.g4")
        }

        waitUntil("binding syntax highlighting is applied") {
            editor.markupModel.allHighlighters.size >= 3
        }
        waitUntil("binding background highlighting task has completed") {
            !BindingEditorPane.hasPendingHighlightingForTest(editor)
        }

        assertFalse(editor.isDisposed)
        assertEquals("examples/input.txt.g4p -> grammars/Demo.g4", editor.document.text)
    }

    fun `test execution plan rejects paths that escape the project`() {
        val projectRoot = Path.of(requireNotNull(project.basePath)).toAbsolutePath().normalize()
        val outsideFiles = onBackground {
            val outsideRoot = Files.createTempDirectory("antlr4plus-binding-outside-")
            val outsideProfile = Files.writeString(outsideRoot.resolve("input.g4p"), "input")
            val outsideGrammar =
                Files.writeString(
                    outsideRoot.resolve("Outside.g4"),
                    "grammar Outside; start: EOF;",
                )
            Triple(outsideRoot, outsideProfile, outsideGrammar)
        }
        try {
            val (_, outsideProfile, outsideGrammar) = outsideFiles
            val traversalProfile = projectRoot.relativize(outsideProfile).toString().replace('\\', '/')
            val traversalGrammar = projectRoot.relativize(outsideGrammar).toString().replace('\\', '/')

            val bindings =
                listOf(
                    BindingOperations.ParsedBinding(
                        outsideProfile.toString(),
                        outsideGrammar.toString(),
                    ),
                    BindingOperations.ParsedBinding(traversalProfile, traversalGrammar),
                )
            val plan = BindingOperations.buildExecutionPlan(projectRoot, bindings)

            assertEquals(bindings.size, plan.size)
            assertTrue(
                "Escaping bindings must all be rejected: $plan",
                plan.all { it is BindingOperations.PlannedAction.Skip },
            )
        } finally {
            onBackground { outsideFiles.first.toFile().deleteRecursively() }
        }
    }

    fun `test execution plan requires regular profile and grammar files`() {
        val root = onBackground {
            val created = Files.createTempDirectory("antlr4plus-binding-plan-")
            Files.createDirectories(created.resolve("grammars"))
            Files.createDirectories(created.resolve("profiles"))
            Files.writeString(created.resolve("grammars/not-a-grammar.txt"), "text")
            Files.writeString(created.resolve("profiles/valid.g4p"), "input")
            Files.createDirectories(created.resolve("grammars/directory.g4"))
            Files.createDirectories(created.resolve("profiles/directory.g4p"))
            created
        }
        try {
            val plan =
                BindingOperations.buildExecutionPlan(
                    root,
                    listOf(
                        BindingOperations.ParsedBinding(
                            "profiles/valid.g4p",
                            "grammars/not-a-grammar.txt",
                        ),
                        BindingOperations.ParsedBinding(
                            "profiles/valid.g4p",
                            "grammars/directory.g4",
                        ),
                        BindingOperations.ParsedBinding(
                            "profiles/directory.g4p",
                            "grammars/directory.g4",
                        ),
                    ),
                )

            assertTrue(
                "Non-grammar files and directories must never become binding targets: $plan",
                plan.all {
                    it is BindingOperations.PlannedAction.Skip
                },
            )
        } finally {
            onBackground { root.toFile().deleteRecursively() }
        }
    }

    fun `test execution plan finds source for every g4p extension casing`() {
        val root = onBackground {
            val created = Files.createTempDirectory("antlr4plus-binding-plan-")
            Files.createDirectories(created.resolve("grammars"))
            Files.createDirectories(created.resolve("examples"))
            Files.writeString(created.resolve("grammars/Demo.g4"), "grammar Demo; start: EOF;")
            Files.writeString(created.resolve("examples/input.txt"), "input")
            created
        }
        try {
            val plan =
                BindingOperations.buildExecutionPlan(
                    root,
                    listOf(
                        BindingOperations.ParsedBinding(
                            "examples/input.txt.G4p",
                            "grammars/Demo.g4",
                        ),
                    ),
                )

            assertTrue(
                "Mixed-case .g4p suffix should still locate the base input: $plan",
                plan.single() is BindingOperations.PlannedAction.RenameAndBind,
            )
        } finally {
            onBackground { root.toFile().deleteRecursively() }
        }
    }

    fun `test execution plan serializes duplicate targets and rejects conflicts`() {
        val root = onBackground {
            val created = Files.createTempDirectory("antlr4plus-binding-plan-")
            Files.createDirectories(created.resolve("grammars"))
            Files.createDirectories(created.resolve("profiles"))
            Files.writeString(created.resolve("grammars/First.g4"), "grammar First; start: EOF;")
            Files.writeString(created.resolve("grammars/Second.g4"), "grammar Second; start: EOF;")
            Files.writeString(created.resolve("profiles/input.g4p"), "input")
            created
        }
        try {
            val sameBinding = BindingOperations.ParsedBinding("profiles/input.g4p", "grammars/First.g4")

            val duplicatePlan = BindingOperations.buildExecutionPlan(root, listOf(sameBinding, sameBinding))
            assertEquals(1, duplicatePlan.count { it is BindingOperations.PlannedAction.BindOnly })
            assertEquals(1, duplicatePlan.count { it is BindingOperations.PlannedAction.Skip })

            val conflictPlan =
                BindingOperations.buildExecutionPlan(
                    root,
                    listOf(
                        sameBinding,
                        BindingOperations.ParsedBinding("profiles/input.g4p", "grammars/Second.g4"),
                    ),
                )
            assertTrue(
                "Conflicting writes to the same profile must not race: $conflictPlan",
                conflictPlan.all {
                    it is BindingOperations.PlannedAction.Skip
                },
            )
        } finally {
            onBackground { root.toFile().deleteRecursively() }
        }
    }

    fun `test planned binding actions rename and persist real xattrs`() {
        val root = onBackground {
            val created = Files.createTempDirectory("antlr4plus-binding-execute-")
            Files.createDirectories(created.resolve("grammars"))
            Files.createDirectories(created.resolve("profiles"))
            Files.writeString(created.resolve("grammars/Demo.g4"), "grammar Demo; start: EOF;")
            Files.writeString(created.resolve("profiles/existing.g4p"), "existing")
            Files.writeString(created.resolve("profiles/to-rename.txt"), "rename")
            created
        }
        try {
            val bindings =
                listOf(
                    BindingOperations.ParsedBinding("profiles/existing.g4p", "grammars/Demo.g4"),
                    BindingOperations.ParsedBinding(
                        "profiles/to-rename.txt.g4p",
                        "grammars/Demo.g4",
                    ),
                )
            val plan = BindingOperations.buildExecutionPlan(root, bindings)
            assertTrue(plan[0] is BindingOperations.PlannedAction.BindOnly)
            assertTrue(plan[1] is BindingOperations.PlannedAction.RenameAndBind)

            val results = onBackground {
                plan.map { BindingOperations.executePlannedAction(root, it) }
            }
            assertTrue("Binding execution failed: $results", results.all { it.success })
            val filesystemState = onBackground {
                Triple(
                    Files.exists(root.resolve("profiles/to-rename.txt")),
                    Files.isRegularFile(root.resolve("profiles/to-rename.txt.g4p")),
                    bindings.map { binding ->
                        FileAttributeHelper.readAttribute(
                            root.resolve(binding.g4pPath),
                            FileAttributeHelper.GRAMMAR_BINDING_ATTR,
                        )
                    },
                )
            }
            assertFalse(filesystemState.first)
            assertTrue(filesystemState.second)
            bindings.zip(filesystemState.third).forEach { (binding, storedPath) ->
                assertEquals(
                    binding.grammarPath,
                    storedPath,
                )
            }
        } finally {
            onBackground { root.toFile().deleteRecursively() }
        }
    }

    private fun waitUntil(
        description: String,
        timeoutMs: Long = 10_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            if (condition()) return
            Thread.sleep(50)
        }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertTrue("Timed out waiting for: $description", condition())
    }

    private fun <T> onBackground(action: () -> T): T =
        ApplicationManager.getApplication().executeOnPooledThread(Callable(action)).get()
}
