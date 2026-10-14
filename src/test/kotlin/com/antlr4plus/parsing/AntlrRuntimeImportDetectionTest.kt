package com.antlr4plus.parsing

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/** Checks import detection for incompatible Java runtime types in Kotlin preview sources. */
class AntlrRuntimeImportDetectionTest {

    @Test
    fun `detects single-class import from java runtime`() {
        val src =
            """
            package com.example

            import org.antlr.v4.runtime.Lexer

            abstract class MyBase
            """
                .trimIndent()
        assertTrue(CompiledParsingService.containsJavaAntlrRuntimeImport(src))
    }

    @Test
    fun `detects wildcard import from java runtime`() {
        val src =
            """
            package com.example

            import org.antlr.v4.runtime.*

            abstract class MyBase
            """
                .trimIndent()
        assertTrue(CompiledParsingService.containsJavaAntlrRuntimeImport(src))
    }

    @Test
    fun `detects ParserRuleContext import from java runtime`() {
        val src =
            """
            package com.example

            import org.antlr.v4.runtime.ParserRuleContext
            import org.antlr.v4.runtime.RecognitionException

            class MyContextBase : ParserRuleContext()
            """
                .trimIndent()
        assertTrue(CompiledParsingService.containsJavaAntlrRuntimeImport(src))
    }

    @Test
    fun `detects import even when surrounded by other imports`() {
        val src =
            """
            package com.example

            import kotlin.collections.List
            import java.util.HashMap
            import org.antlr.v4.runtime.CharStream
            import com.example.other.Thing

            class Foo
            """
                .trimIndent()
        assertTrue(CompiledParsingService.containsJavaAntlrRuntimeImport(src))
    }

    @Test
    fun `detects import with leading whitespace`() {
        val src = "    import org.antlr.v4.runtime.Lexer\n"
        assertTrue(CompiledParsingService.containsJavaAntlrRuntimeImport(src))
    }

    @Test
    fun `does not detect kotlinruntime import`() {
        val src =
            """
            package com.example

            import org.antlr.v4.kotlinruntime.Lexer
            import org.antlr.v4.kotlinruntime.CharStream

            abstract class MyBase
            """
                .trimIndent()
        assertFalse(CompiledParsingService.containsJavaAntlrRuntimeImport(src))
    }

    @Test
    fun `does not detect kotlinruntime wildcard import`() {
        val src =
            """
            package com.example

            import org.antlr.v4.kotlinruntime.*

            abstract class MyBase
            """
                .trimIndent()
        assertFalse(CompiledParsingService.containsJavaAntlrRuntimeImport(src))
    }

    @Test
    fun `does not detect on completely unrelated imports`() {
        val src =
            """
            package com.example

            import java.util.HashMap
            import kotlin.collections.List
            import com.example.other.Thing

            class Foo
            """
                .trimIndent()
        assertFalse(CompiledParsingService.containsJavaAntlrRuntimeImport(src))
    }

    @Test
    fun `does not detect on empty file`() {
        assertFalse(CompiledParsingService.containsJavaAntlrRuntimeImport(""))
    }

    @Test
    fun `does not false-positive on a comment that mentions the package`() {
        // A package mention is not an import line; this case does not test imports inside comments.
        val src =
            """
            package com.example

            /**
             * This base class deliberately does NOT use org.antlr.v4.runtime.
             * It uses the Kotlin runtime.
             */
            import org.antlr.v4.kotlinruntime.Lexer

            abstract class MyBase
            """
                .trimIndent()
        assertFalse(
            CompiledParsingService.containsJavaAntlrRuntimeImport(src),
            "Doc-comment mention of the runtime path must not trigger detection",
        )
    }

    @Test
    fun `detects aliased import from java runtime`() {
        val src =
            """
            package com.example

            import org.antlr.v4.runtime.Lexer as JavaLexer

            class Foo
            """
                .trimIndent()
        assertTrue(CompiledParsingService.containsJavaAntlrRuntimeImport(src))
    }

    @Test
    fun `does not detect a substring inside a longer package name`() {
        val src =
            """
            package com.example

            import org.antlr.v4.runtime2.SomeFakeClass

            class Foo
            """
                .trimIndent()
        assertFalse(CompiledParsingService.containsJavaAntlrRuntimeImport(src))
    }
}
