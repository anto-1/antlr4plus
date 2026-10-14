package com.antlr4plus.parsing

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test

/** Prevents words in comments and strings from becoming spurious source dependencies. */
class GrammarActionAnalyzerCommentStrippingTest {

    @Test
    fun `grammar actions discover Java constructors and field types`() {
        val grammar =
            """
            grammar Dependencies;
            @header { import support.ImportedHelper; }
            @members {
                FieldHelper field;
                ConstructorHelper helper = new ConstructorHelper();
            }
            start: { new ActionHelper(); } EOF;
            """
                .trimIndent()

        assertEquals(
            setOf("ImportedHelper", "FieldHelper", "ConstructorHelper", "ActionHelper"),
            GrammarActionAnalyzer.analyzeGrammarContent(grammar).referencedClasses,
        )
    }

    @Test
    fun `grammar actions discover Kotlin constructors imports and nullable types`() {
        val grammar =
            """
            grammar Dependencies;
            options { language=Kotlin; }
            @header {
                import support.ImportedHelper as Support
            }
            @members {
                val field: FieldHelper? = null
                val helper = ConstructorHelper()
                fun run(): Unit { val count: Int = 1 }
            }
            start: { ActionHelper() } EOF;
            """
                .trimIndent()

        assertEquals(
            setOf("ImportedHelper", "FieldHelper", "ConstructorHelper", "ActionHelper"),
            GrammarActionAnalyzer.analyzeGrammarContent(grammar).referencedClasses,
        )
    }

    @Test
    fun `dependency discovery covers rule declarations predicates and exception actions`() {
        val grammar =
            """
            grammar Dependencies;
            start[ArgumentHelper value] returns [ReturnHelper result] locals [LocalHelper local]
            @init { InitHelper.prepare(); }
            @after { AfterHelper.finish(); }
                : { accepts(new PredicateHelper()) }? sub[new CallHelper()] EOF
                ;
            catch [RecognitionException e] { new CatchHelper(); }
            finally { new FinallyHelper(); }
            sub[CallHelper value]: TOKEN;
            TOKEN: 'x' { new LexerHelper(); };
            """
                .trimIndent()

        assertEquals(
            setOf(
                "ArgumentHelper",
                "ReturnHelper",
                "LocalHelper",
                "InitHelper",
                "AfterHelper",
                "PredicateHelper",
                "CallHelper",
                "CatchHelper",
                "FinallyHelper",
                "LexerHelper",
            ),
            GrammarActionAnalyzer.analyzeGrammarContent(grammar).referencedClasses,
        )
    }

    @Test
    fun `grammar comments literals and options do not become action dependencies`() {
        val grammar =
            """
            grammar Dependencies;
            options { superClass=options.OptionHelper; }
            // { CommentHelper.call(); }
            /* { BlockCommentHelper.call(); } */
            @members {
                String message = "StringHelper.call() { }";
                // InsideCommentHelper.call();
                /* InsideBlockHelper.call(); */
                void execute() { if (true) { RealHelper.call(); } }
            }
            start: '{LiteralHelper.call()}' EOF;
            """
                .trimIndent()

        assertEquals(
            setOf("RealHelper"),
            GrammarActionAnalyzer.analyzeGrammarContent(grammar).referencedClasses,
        )
    }

    private lateinit var tempDir: File

    @After
    fun cleanup() {
        if (::tempDir.isInitialized && tempDir.exists()) {
            tempDir.deleteRecursively()
        }
    }

    private fun mkfile(name: String, content: String): File {
        if (!::tempDir.isInitialized) {
            tempDir = createTempDirectory("antlr4plus-strip-test").toFile().canonicalFile
        }
        val f = File(tempDir, name)
        f.parentFile?.mkdirs()
        f.writeText(content)
        return f
    }

    @Test
    fun `block comment is stripped`() {
        val src = "val x = 1 /* This is a Comment with Words */ val y = 2"
        val stripped = GrammarActionAnalyzer.stripCommentsAndStrings(src)
        assertFalse("This" in stripped, "Block comment content must not survive")
        assertFalse("Comment" in stripped, "Block comment content must not survive")
        assertFalse("Words" in stripped, "Block comment content must not survive")
        assertTrue("val x = 1" in stripped, "Code outside comment must remain")
        assertTrue("val y = 2" in stripped, "Code after block comment must remain")
    }

    @Test
    fun `kdoc comment is stripped but identifiers around it survive`() {
        val src =
            """
            /**
             * Note that this references CustomerLexerBase.
             * Demonstrates the Helpers pattern.
             */
            class Foo : RealBase() {
                fun bar() { }
            }
            """
                .trimIndent()
        val stripped = GrammarActionAnalyzer.stripCommentsAndStrings(src)
        assertFalse("Note" in stripped, "KDoc comment word should be gone")
        assertFalse("Demonstrates" in stripped, "KDoc comment word should be gone")
        assertFalse("Helpers" in stripped, "KDoc comment word should be gone")
        assertTrue("Foo" in stripped, "Class name must remain")
        assertTrue("RealBase" in stripped, "Real superclass must remain")
        assertTrue(
            "CustomerLexerBase" !in stripped,
            "Identifier that only appeared inside doc comment must be gone",
        )
    }

    @Test
    fun `line comment is stripped to end of line`() {
        val src =
            """
            val x = 1 // This Trailing Comment About Things
            val y: SomeType = 2
            """
                .trimIndent()
        val stripped = GrammarActionAnalyzer.stripCommentsAndStrings(src)
        assertFalse("Trailing" in stripped)
        assertFalse("Comment" in stripped)
        assertFalse("Things" in stripped)
        assertTrue("SomeType" in stripped, "Identifier on a different line must remain")
    }

    @Test
    fun `string literal contents are stripped`() {
        val src = """val msg = "Look at MyClass.foo() in the docs"; val x: RealType = 1"""
        val stripped = GrammarActionAnalyzer.stripCommentsAndStrings(src)
        assertFalse("MyClass" in stripped, "String literal content must not be matched as code")
        assertFalse("Look" in stripped, "String literal content must not be matched as code")
        assertTrue("RealType" in stripped, "Identifier outside the string must remain")
    }

    @Test
    fun `triple-quoted Kotlin string is stripped`() {
        val src =
            """
            val poem = ""${'"'}
                FakeClassA
                AnotherFakeClass
                Lines about Things
            ""${'"'}
            class RealClass
            """
                .trimIndent()
        val stripped = GrammarActionAnalyzer.stripCommentsAndStrings(src)
        assertFalse("FakeClassA" in stripped)
        assertFalse("AnotherFakeClass" in stripped)
        assertTrue("RealClass" in stripped)
    }

    @Test
    fun `escaped quote inside string does not end string early`() {
        val src = """val a = "He said \"NotAClass\" loudly"; class Real"""
        val stripped = GrammarActionAnalyzer.stripCommentsAndStrings(src)
        assertFalse("NotAClass" in stripped)
        assertTrue("Real" in stripped)
    }

    @Test
    fun `nested-looking comment markers in strings are not treated as comments`() {
        val src = """val pattern = "/* not a comment */"; class RealClass"""
        val stripped = GrammarActionAnalyzer.stripCommentsAndStrings(src)
        assertFalse("not" in stripped)
        assertFalse("comment" in stripped)
        assertTrue("RealClass" in stripped)
    }

    /** Keep the verbose fixture comments: their words exercise dependency filtering. */
    @Test
    fun `customer_repro lexer base shape produces no bogus class refs`() {
        val src =
            """
            package com.example.lexerbase

            import org.antlr.v4.kotlinruntime.CharStream
            import org.antlr.v4.kotlinruntime.Lexer

            /**
             * Lexer superClass for the customer reproduction case.
             *
             * Lives at:
             *     algol60/customer_repro/examples/lexer-side/CustomerLexerBase.kt
             *
             * IMPORTANT: this extends org.antlr.v4.kotlinruntime.Lexer, NOT
             * org.antlr.v4.runtime.Lexer. The ANTLR4+ plugin uses the Kotlin port
             * of the ANTLR runtime when generating Kotlin preview code, so any
             * base class meant to be extended by generated Kotlin lexers must
             * come from the kotlinruntime package. Mixing the two runtimes
             * produces opaque "argument type mismatch" errors.
             *
             * Note that the parent directory is named examples, which is on the
             * plugin's old SKIP_DIRS blacklist. Before the FilenameIndex
             * augmentation, this file was unreachable to the directory walk.
             *
             * Demonstrates the customer's "lexer rule action calling a superClass
             * method" pattern. Real-world bases typically use this for token
             * injection, mode switching (pushMode/popMode), or stateful counting.
             */
            abstract class CustomerLexerBase(input: CharStream) : Lexer(input) {

                /** Number of WORD tokens lexed so far. Updated from a rule action. */
                var wordCount: Int = 0
                    protected set

                /**
                 * Called from the WORD rule action in CustomerLexer.g4.
                 *
                 * If the plugin really instantiated the user's compiled lexer subclass
                 * the counter will be observable on the lexer instance after a parse.
                 * Available everywhere the base class is.
                 */
                fun onWord(text: String) {
                    wordCount++
                }
            }
            """
                .trimIndent()

        val file = mkfile("CustomerLexerBase.kt", src)
        val refs = GrammarActionAnalyzer.analyzeSourceFile(file)

        val bogusWords =
            setOf(
                "Lives",
                "Note",
                "Before",
                "FilenameIndex",
                "The",
                "Demonstrates",
                "Number",
                "Updated",
                "Called",
                "This",
                "Helpers",
                "Available",
                "Lexer", // Runtime types must also be excluded from source dependencies.
                "WORD",
                "IMPORTANT",
                "ANTLR",
                "Mixing",
                "SKIP_DIRS",
                "Real",
                "Kotlin",
                "Java",
                "Pre",
            )
        val leaked = refs intersect bogusWords
        assertTrue(
            leaked.isEmpty(),
            "Bogus words from doc comments leaked into class references: $leaked",
        )
    }

    @Test
    fun `analyze still picks up real superclass after stripping`() {
        val src =
            """
            package com.example

            import com.example.RealBase

            /** A pointless docstring referencing FakeClassA and FakeClassB. */
            class Foo : RealBase() {
                fun bar(): RealReturnType = TODO()
            }
            interface CompactChild: CompactParent{}
            """
                .trimIndent()
        val file = mkfile("Foo.kt", src)
        val refs = GrammarActionAnalyzer.analyzeSourceFile(file)

        assertTrue("RealBase" in refs, "Genuine superclass must still be detected. Found: $refs")
        assertTrue(
            "RealReturnType" in refs,
            "Genuine return type must still be detected. Found: $refs",
        )
        assertTrue(
            "CompactParent" in refs,
            "Superclass detection must not require whitespace before its body",
        )
        assertFalse("FakeClassA" in refs, "Doc comment word must be gone")
        assertFalse("FakeClassB" in refs, "Doc comment word must be gone")
    }
}
