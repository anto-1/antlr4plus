package com.antlr4plus.projectTreeDecoration

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class G4PFileNodeDecoratorTest {

    @Test
    fun `dependency decoration requires an exact normalized path`() {
        val active = setOf("/workspace/project/src/main/java/com/acme/Helper.java")

        assertTrue(
            matchesActiveDependencyPath(
                "/workspace/project/src/main/java/com/acme/./Helper.java",
                active,
            ),
        )
        assertFalse(
            matchesActiveDependencyPath(
                "/workspace/project/src/test/java/com/other/Helper.java",
                active,
            ),
        )
        assertFalse(
            matchesActiveDependencyPath(
                "/workspace/project-copy/src/main/java/com/acme/Helper.java",
                active,
            ),
        )
    }
}
