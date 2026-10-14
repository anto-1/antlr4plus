package com.antlr4plus.filebinding

import java.nio.file.Path
import junit.framework.TestCase

class ProjectPathPolicyTest : TestCase() {

    fun `test display path keeps sibling directory with common prefix separate`() {
        assertEquals(
            "../foobar/Demo.g4",
            ProjectPathPolicy.relativeDisplayPath(
                Path.of("/workspace/project/foo"),
                Path.of("/workspace/project/foobar/Demo.g4"),
            ),
        )
    }

    fun `test display path handles same folder child and ancestor`() {
        val profileDirectory = Path.of("/workspace/project/examples")

        assertEquals(
            "Demo.g4",
            ProjectPathPolicy.relativeDisplayPath(
                profileDirectory,
                profileDirectory.resolve("Demo.g4"),
            ),
        )
        assertEquals(
            "grammars/Demo.g4",
            ProjectPathPolicy.relativeDisplayPath(
                profileDirectory,
                profileDirectory.resolve("grammars/Demo.g4"),
            ),
        )
        assertEquals(
            "../Demo.g4",
            ProjectPathPolicy.relativeDisplayPath(
                profileDirectory,
                profileDirectory.parent.resolve("Demo.g4"),
            ),
        )
    }

    fun `test display path normalizes dot segments without filesystem access`() {
        assertEquals(
            "grammars/Demo.g4",
            ProjectPathPolicy.relativeDisplayPath(
                Path.of("/workspace/project/examples/../examples"),
                Path.of("/workspace/project/examples/grammars/./Demo.g4"),
            ),
        )
    }
}
