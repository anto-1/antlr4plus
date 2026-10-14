package com.antlr4plus.filebinding

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable

class FileBindingServiceIntegrationTest : BasePlatformTestCase() {

    private lateinit var fixtureRoot: Path

    override fun setUp() {
        super.setUp()
        val projectRoot = Path.of(requireNotNull(project.basePath))
        fixtureRoot = onBackground {
            Files.createDirectories(projectRoot)
            Files.createDirectory(projectRoot.resolve("binding-service-${System.nanoTime()}"))
        }
    }

    override fun tearDown() {
        try {
            if (::fixtureRoot.isInitialized) {
                onBackground {
                    fixtureRoot.toFile().deleteRecursively()
                }
            }
        } finally {
            super.tearDown()
        }
    }

    fun `test real xattr binding lifecycle and boundary policy`() {
        assertEquals(
            project.basePath,
            project.getService(FileBindingService::class.java).getProjectRoot()?.path,
        )
        verifyMultipleProfilesBindResolveAndUnbind()
        verifyGrammarRenameUpdatesEveryBinding()
        verifyGrammarDirectoryRenameUpdatesEveryBinding()
        verifyRapidSequentialGrammarRenamesConverge()
        verifyGrammarRenamedAwayFromG4BecomesBroken()
        verifyProfileRenamePreservesDecorationCache()
        verifyDeletionRefreshesDecorationCache()
        verifyDirectoryAndInvalidEndpointsAreRejected()
        verifyMalformedPathsRemainBroken()
        verifyOutsideEndpointsAreRejected()
        onBackground {
            project.getService(FileBindingService::class.java).awaitPendingPathChangesForTest()
        }
    }

    private fun verifyMultipleProfilesBindResolveAndUnbind() {
        val grammar = createFile("grammars/Demo.g4", "grammar Demo; start: EOF;")
        val firstProfile = createFile("inputs/first.g4p", "first")
        val secondProfile = createFile("inputs/second.g4p", "second")
        val service = project.getService(FileBindingService::class.java)

        val bound = onBackground {
            service.setAll(listOf(firstProfile, secondProfile), grammar)
        }

        assertTrue(bound)
        for (profile in listOf(firstProfile, secondProfile)) {
            assertEquals(
                relativePath("grammars/Demo.g4"),
                onBackground { service.getBoundGrammarPath(profile) },
            )
            assertEquals(grammar, onBackground { service.getBoundGrammar(profile) })
            assertFalse(onBackground { service.isBindingBroken(profile) })
        }

        assertTrue(onBackground { service.unbindAll(listOf(firstProfile, secondProfile)) })
        assertNull(onBackground { service.getBoundGrammarPath(firstProfile) })
        assertNull(onBackground { service.getBoundGrammarPath(secondProfile) })
    }

    private fun verifyGrammarRenameUpdatesEveryBinding() {
        val grammar = createFile("grammars/Before.g4", "grammar Before; start: EOF;")
        val profiles =
            listOf(
                createFile("inputs/one.g4p", "one"),
                createFile("inputs/two.g4p", "two"),
            )
        val service = project.getService(FileBindingService::class.java)
        assertTrue(onBackground { service.setAll(profiles, grammar) })

        runWriteAction {
            grammar.rename(this, "After.g4")
        }

        val expectedPath = relativePath("grammars/After.g4")
        waitUntil("renamed grammar binding is propagated") {
            profiles.all { profile ->
                onBackground { service.getBoundGrammarPath(profile) } == expectedPath
            }
        }
        profiles.forEach { profile ->
            assertEquals(grammar, onBackground { service.getBoundGrammar(profile) })
        }
    }

    private fun verifyGrammarDirectoryRenameUpdatesEveryBinding() {
        val grammar =
            createFile(
                "directory-rename/Before/Nested.g4",
                "grammar Nested; start: EOF;",
            )
        val profiles =
            listOf(
                createFile("directory-rename/inputs/one.g4p", "one"),
                createFile("directory-rename/inputs/two.g4p", "two"),
            )
        val service = project.getService(FileBindingService::class.java)
        assertTrue(onBackground { service.setAll(profiles, grammar) })

        runWriteAction {
            grammar.parent.rename(this, "After")
        }

        val expectedPath = relativePath("directory-rename/After/Nested.g4")
        waitUntil("grammar-directory rename is propagated to every binding") {
            profiles.all { profile ->
                onBackground { service.getBoundGrammarPath(profile) } == expectedPath
            }
        }
        profiles.forEach { profile ->
            assertEquals(grammar, onBackground { service.getBoundGrammar(profile) })
        }
    }

    private fun verifyRapidSequentialGrammarRenamesConverge() {
        val grammar = createFile("rapid-rename/Stage0.g4", "grammar Stage0; start: EOF;")
        val profiles =
            (1..16).map { index ->
                createFile("rapid-rename/inputs/input-$index.g4p", "input $index")
            }
        val service = project.getService(FileBindingService::class.java)
        assertTrue(onBackground { service.setAll(profiles, grammar) })

        runWriteAction {
            (1..12).forEach { stage -> grammar.rename(this, "Stage$stage.g4") }
        }

        val expectedPath = relativePath("rapid-rename/Stage12.g4")
        waitUntil("rapid sequential grammar renames converge on the final path") {
            profiles.all { profile ->
                onBackground { service.getBoundGrammarPath(profile) } == expectedPath
            }
        }
        profiles.forEach { profile ->
            assertEquals(grammar, onBackground { service.getBoundGrammar(profile) })
        }
    }

    private fun verifyProfileRenamePreservesDecorationCache() {
        val grammar = createFile("profile-rename/Demo.g4", "grammar Demo; start: EOF;")
        val profile = createFile("profile-rename/original.g4p", "input")
        val service = project.getService(FileBindingService::class.java)
        assertTrue(onBackground { service.bind(profile, grammar) })
        assertTrue(service.getDecorationInfo(profile).isBound)

        runWriteAction {
            profile.rename(this, "renamed.g4p")
        }

        waitUntil("renamed profile decoration is recomputed under its new path") {
            service.getDecorationInfo(profile).isBound
        }

        val replacement = createFile("profile-rename/original.g4p", "unbound replacement")
        assertFalse(
            "A newly-created file inherited the renamed profile's stale decoration",
            service.getDecorationInfo(replacement).isBound,
        )
    }

    private fun verifyDeletionRefreshesDecorationCache() {
        val grammar = createFile("deletion/Demo.g4", "grammar Demo; start: EOF;")
        val profile = createFile("deletion/input.g4p", "input")
        val service = project.getService(FileBindingService::class.java)
        assertTrue(onBackground { service.bind(profile, grammar) })
        assertFalse(service.getDecorationInfo(profile).isBroken)

        runWriteAction { grammar.delete(this) }

        waitUntil("deleted grammar is reflected in the profile decoration") {
            service.getDecorationInfo(profile).isBroken
        }

        runWriteAction { profile.delete(this) }
        val replacement = createFile("deletion/input.g4p", "unbound replacement")
        waitUntil("deleted profile decoration is evicted from the cache") {
            !service.getDecorationInfo(replacement).isBound
        }
    }

    private fun verifyGrammarRenamedAwayFromG4BecomesBroken() {
        val grammar = createFile("extension-rename/Demo.g4", "grammar Demo; start: EOF;")
        val profile = createFile("extension-rename/input.g4p", "input")
        val service = project.getService(FileBindingService::class.java)
        assertTrue(onBackground { service.bind(profile, grammar) })

        runWriteAction {
            grammar.rename(this, "Demo.txt")
        }

        val renamedPath = relativePath("extension-rename/Demo.txt")
        waitUntil("renamed non-grammar target is marked broken") {
            onBackground { service.getBoundGrammarPath(profile) } == renamedPath &&
                onBackground { service.isBindingBroken(profile) } &&
                service.getDecorationInfo(profile).isBroken
        }
        assertNull(onBackground { service.getBoundGrammar(profile) })
    }

    private fun verifyDirectoryAndInvalidEndpointsAreRejected() {
        val validGrammar = createFile("endpoint-validation/Valid.g4", "grammar Valid; start: EOF;")
        val validProfile = createFile("endpoint-validation/valid.g4p", "input")
        val grammarDirectory = createDirectory("endpoint-validation/Directory.g4")
        val profileDirectory = createDirectory("endpoint-validation/directory.g4p")
        val deletedGrammar = createFile("endpoint-validation/Deleted.g4", "grammar Deleted; start: EOF;")
        val deletedProfile = createFile("endpoint-validation/deleted.g4p", "input")
        val service = project.getService(FileBindingService::class.java)

        runWriteAction {
            deletedGrammar.delete(this)
            deletedProfile.delete(this)
        }

        assertFalse(
            "A .g4 directory must not be accepted as a grammar",
            onBackground {
                service.bind(validProfile, grammarDirectory)
            },
        )
        assertFalse(
            "A .g4p directory must not be accepted as a profile",
            onBackground {
                service.bind(profileDirectory, validGrammar)
            },
        )
        assertFalse(
            "An invalid grammar VirtualFile must not be accepted",
            onBackground {
                service.bind(validProfile, deletedGrammar)
            },
        )
        assertFalse(
            "An invalid profile VirtualFile must not be accepted",
            onBackground {
                service.bind(deletedProfile, validGrammar)
            },
        )
        assertNull(onBackground { service.getBoundGrammarPath(validProfile) })
    }

    private fun verifyMalformedPathsRemainBroken() {
        val profile = createFile("inputs/input.g4p", "input")
        val service = project.getService(FileBindingService::class.java)
        val unsafePaths =
            listOf(
                "../Outside.g4",
                "/tmp/Outside.g4",
                "C:/Outside.g4",
                "grammars\\Outside.g4",
            )

        for (unsafePath in unsafePaths) {
            assertTrue(
                onBackground { FileAttributeHelper.writeGrammarBinding(profile, unsafePath) },
            )
            assertNull(
                "Unsafe binding resolved: $unsafePath",
                onBackground { service.getBoundGrammar(profile) },
            )
            assertTrue(
                "Unsafe binding was not marked broken: $unsafePath",
                onBackground {
                    service.isBindingBroken(profile)
                },
            )
        }
    }

    private fun verifyOutsideEndpointsAreRejected() {
        val insideGrammar = createFile("grammars/Inside.g4", "grammar Inside; start: EOF;")
        val insideProfile = createFile("inputs/inside.g4p", "inside")
        val outsideRoot = Files.createTempDirectory("antlr4plus-binding-outside-")
        try {
            val outsideGrammar =
                createExternalFile(
                    outsideRoot.resolve("Outside.g4"),
                    "grammar Outside; start: EOF;",
                )
            val outsideProfile = createExternalFile(outsideRoot.resolve("outside.g4p"), "outside")
            val service = project.getService(FileBindingService::class.java)

            assertFalse(onBackground { service.bind(insideProfile, outsideGrammar) })
            assertFalse(onBackground { service.bind(outsideProfile, insideGrammar) })
            assertNull(onBackground { service.getBoundGrammarPath(insideProfile) })
            assertNull(onBackground { service.getBoundGrammarPath(outsideProfile) })
        } finally {
            onBackground {
                outsideRoot.toFile().deleteRecursively()
            }
        }
    }

    private fun createFile(relativePath: String, content: String): VirtualFile {
        val path = fixtureRoot.resolve(relativePath)
        return createExternalFile(path, content)
    }

    private fun createDirectory(relativePath: String): VirtualFile {
        val path = fixtureRoot.resolve(relativePath)
        onBackground { Files.createDirectories(path) }
        return runWriteAction {
            requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
        }
    }

    private fun createExternalFile(path: Path, content: String): VirtualFile {
        onBackground {
            Files.createDirectories(path.parent)
            Files.writeString(path, content)
        }
        // Fixture setup must deliver the VFS creation event on EDT before the test continues.
        return runWriteAction {
            requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
        }
    }

    private fun relativePath(path: String): String = "${fixtureRoot.fileName}/$path"

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
