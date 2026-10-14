package com.antlr4plus.parsing.actionMap

import com.antlr4plus.dotG4Support.lineMarker.GeneratedActionNavigationOffsetCalculator
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test

/** Tests action mapping with synthetic grammar and generated-source pairs. */
class ActionLocationMapBuilderTest {

    private lateinit var tempDir: File

    @After
    fun cleanup() {
        if (::tempDir.isInitialized && tempDir.exists()) {
            tempDir.deleteRecursively()
        }
    }

    private fun mkfile(name: String, content: String): File {
        if (!::tempDir.isInitialized) {
            tempDir = createTempDirectory("action-map-test").toFile().canonicalFile
        }
        val f = File(tempDir, name)
        f.parentFile?.mkdirs()
        f.writeText(content)
        return f
    }

    @Test
    fun `maps a single parser action to its body in the generated source`() {
        val grammar =
            mkfile(
                "Foo.g4",
                """
                parser grammar Foo;

                program
                    : ID { System.out.println("hi"); }
                    ;

                ID : [a-zA-Z]+ ;
                """
                    .trimIndent(),
            )
        val generated =
            mkfile(
                "FooParser.java",
                """
                // Generated from Foo.g4 by ANTLR 4.13.2
                public class FooParser extends Parser {
                    public final ProgramContext program() throws RecognitionException {
                        ProgramContext _localctx = new ProgramContext(_ctx, getState());
                        enterRule(_localctx, 0, RULE_program);
                        try {
                            enterOuterAlt(_localctx, 1);
                            setState(2);
                            match(ID);
                             System.out.println("hi");
                        }
                        return _localctx;
                    }
                }
                """
                    .trimIndent(),
            )

        val map = ActionLocationMapBuilder.build(grammar, generated)
        assertNotNull(map)
        assertEquals(1, map.entries.size)

        val entry = map.entries.single()
        assertEquals("Parser Action", entry.kind)
        assertEquals("""System.out.println("hi");""", entry.body)

        // Generated ranges include line indentation, while entry.body is trimmed.
        val generatedText = generated.readText()
        val generatedSlice =
            generatedText.substring(
                entry.generatedBodyRange.first,
                entry.generatedBodyRange.last + 1,
            )
        assertTrue(
            entry.body in generatedSlice,
            "trimmed body should be inside the generated body slice. Slice=[$generatedSlice]",
        )

        // Grammar ranges preserve whitespace between the braces.
        val grammarText = grammar.readText()
        val grammarBodyText = grammarText.substring(entry.grammarBodyRange.first, entry.grammarBodyRange.last + 1)
        assertTrue(
            entry.body in grammarBodyText,
            "trimmed body should be inside the grammar body range",
        )
    }

    @Test
    fun `preserves raw grammar body whitespace alongside the canonical matching body`() {
        val grammar =
            mkfile(
                "FooWhitespace.g4",
                """
                parser grammar FooWhitespace;

                program
                    : ID { first(); }
                    ;

                ID : [a-zA-Z]+ ;
                """
                    .trimIndent(),
            )
        val generated =
            mkfile(
                "FooWhitespaceParser.java",
                """
                public class FooWhitespaceParser extends Parser {
                    public final ProgramContext program() throws RecognitionException {
                        match(ID);
                         first();
                        return _localctx;
                    }
                }
                """
                    .trimIndent(),
            )

        val map = ActionLocationMapBuilder.build(grammar, generated)
        assertNotNull(map)

        val entry = map.entries.single()
        assertEquals("first();", entry.body)
        assertEquals(" first(); ", entry.rawBody)
    }

    @Test
    fun `maps a members block to the generated class body`() {
        val grammar =
            mkfile(
                "MembersDemo.g4",
                """
                grammar MembersDemo;

                @members {
                    boolean helper() {
                        return true;
                    }
                }

                rule : ID EOF ;
                ID : [a-zA-Z]+ ;
                WS : [ \t\r\n]+ -> skip ;
                """
                    .trimIndent(),
            )
        val generatedText =
            """
            public class MembersDemoParser extends Parser {
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
        val generated = mkfile("MembersDemoParser.java", generatedText)

        val map = ActionLocationMapBuilder.build(grammar, generated)
        assertNotNull(map)
        assertEquals(1, map.entries.size)

        val entry = map.entries.single()
        assertEquals("Members", entry.kind)

        val expectedStart = generatedText.indexOf("boolean helper()")
        assertEquals(expectedStart, entry.generatedBodyRange.first)
        assertTrue(entry.body.contains("boolean helper()"))
        assertTrue(entry.body.contains("return true;"))
    }

    @Test
    fun `maps scoped parser members only in parser output`() {
        val grammar =
            mkfile(
                "ScopedMembersDemo.g4",
                """
                grammar ScopedMembersDemo;

                @parser::members {
                    boolean parserOnly() {
                        return true;
                    }
                }

                @lexer::members {
                    void lexerOnly() {
                        skip();
                    }
                }

                rule : ID EOF ;
                ID : [a-zA-Z]+ ;
                WS : [ \t\r\n]+ -> skip ;
                """
                    .trimIndent(),
            )
        val generatedText =
            """
            public class ScopedMembersDemoParser extends Parser {
                boolean parserOnly() {
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
        val generated = mkfile("ScopedMembersDemoParser.java", generatedText)

        val map = ActionLocationMapBuilder.build(grammar, generated)
        assertNotNull(map)
        assertEquals(1, map.entries.size)

        val entry = map.entries.single()
        assertEquals("Scoped Members", entry.kind)
        assertEquals("parser::members", entry.scope)
        assertEquals(generatedText.indexOf("boolean parserOnly()"), entry.generatedBodyRange.first)
        assertTrue(entry.body.contains("parserOnly()"))
        assertFalse(entry.body.contains("lexerOnly()"))
    }

    @Test
    fun `maps scoped lexer members only in lexer output`() {
        val grammar =
            mkfile(
                "ScopedLexerMembersDemo.g4",
                """
                lexer grammar ScopedLexerMembersDemo;

                @parser::members {
                    boolean parserOnly() {
                        return true;
                    }
                }

                @lexer::members {
                    void lexerOnly() {
                        skip();
                    }
                }

                ID : [a-zA-Z]+ ;
                WS : [ \t\r\n]+ -> skip ;
                """
                    .trimIndent(),
            )
        val generatedText =
            """
            public class ScopedLexerMembersDemoLexer extends Lexer {
                void lexerOnly() {
                    skip();
                }
            }
            """
                .trimIndent()
        val generated = mkfile("ScopedLexerMembersDemoLexer.java", generatedText)

        val map = ActionLocationMapBuilder.build(grammar, generated)
        assertNotNull(map)
        assertEquals(1, map.entries.size)

        val entry = map.entries.single()
        assertEquals("Scoped Members", entry.kind)
        assertEquals("lexer::members", entry.scope)
        assertEquals(generatedText.indexOf("void lexerOnly()"), entry.generatedBodyRange.first)
        assertTrue(entry.body.contains("lexerOnly()"))
        assertFalse(entry.body.contains("parserOnly()"))
    }

    @Test
    fun `maps rule parameters returns and locals to generated context fields`() {
        val grammar =
            mkfile(
                "ArgumentFields.g4",
                """
                grammar ArgumentFields;

                expr[int level] returns [int value]
                locals [int n = 0]
                    : ID EOF
                    ;

                ID : [a-zA-Z]+ ;
                WS : [ \t\r\n]+ -> skip ;
                """
                    .trimIndent(),
            )
        val generatedText =
            """
            public class ArgumentFieldsParser extends Parser {
                public static class ExprContext extends ParserRuleContext {
                    public int level;
                    public int value;
                    public int n = 0;
                    public TerminalNode ID() { return getToken(ArgumentFieldsParser.ID, 0); }
                    public TerminalNode EOF() { return getToken(ArgumentFieldsParser.EOF, 0); }
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
        val generated = mkfile("ArgumentFieldsParser.java", generatedText)

        val map = ActionLocationMapBuilder.build(grammar, generated)
        assertNotNull(map)

        val byBody = map.entries.associateBy { it.body }
        val parameterEntry = assertNotNull(byBody["int level"])
        assertEquals("Arguments", parameterEntry.kind)
        assertEquals(generatedText.indexOf("int level;"), parameterEntry.generatedBodyRange.first)

        val returnsEntry = assertNotNull(byBody["int value"])
        assertEquals(generatedText.indexOf("int value;"), returnsEntry.generatedBodyRange.first)

        val localsEntry = assertNotNull(byBody["int n = 0"])
        assertEquals(generatedText.indexOf("int n = 0;"), localsEntry.generatedBodyRange.first)
    }

    @Test
    fun `maps simple rule call arguments to the generated invocation`() {
        val grammar =
            mkfile(
                "CallArguments.g4",
                """
                grammar CallArguments;

                start
                    : atom[1, 2] EOF
                    ;

                atom[int a, int b]
                    : ID
                    ;

                ID : [a-zA-Z]+ ;
                WS : [ \t\r\n]+ -> skip ;
                """
                    .trimIndent(),
            )
        val generatedText =
            """
            public class CallArgumentsParser extends Parser {
                public static class StartContext extends ParserRuleContext {
                    public AtomContext atom() { return getRuleContext(AtomContext.class, 0); }
                    public TerminalNode EOF() { return getToken(CallArgumentsParser.EOF, 0); }
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
                    public TerminalNode ID() { return getToken(CallArgumentsParser.ID, 0); }
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
        val generated = mkfile("CallArgumentsParser.java", generatedText)

        val map = ActionLocationMapBuilder.build(grammar, generated)
        assertNotNull(map)

        val callEntry = assertNotNull(map.entries.firstOrNull { it.body == "1, 2" })
        assertEquals("Arguments", callEntry.kind)
        assertEquals(generatedText.indexOf("1, 2"), callEntry.generatedBodyRange.first)
        assertEquals(
            generatedText.indexOf("1, 2"),
            GeneratedActionNavigationOffsetCalculator.calculate(
                generatedText,
                callEntry.generatedBodyRange,
            ),
        )
    }

    @Test
    fun `maps rule init before the generated try block`() {
        val grammar =
            mkfile(
                "RuleInitDemo.g4",
                """
                grammar RuleInitDemo;

                rule
                @init {
                    setup();
                }
                    : ID EOF
                    ;

                ID : [a-zA-Z]+ ;
                WS : [ \t\r\n]+ -> skip ;
                """
                    .trimIndent(),
            )
        val generatedText =
            """
            public class RuleInitDemoParser extends Parser {
                public final RuleContext rule() throws RecognitionException {
                    setup();
                    try {
                        enterOuterAlt(_localctx, 1);
                        match(ID);
                        match(EOF);
                    }
                    finally {
                        exitRule();
                    }
                    return _localctx;
                }
            }
            """
                .trimIndent()
        val generated = mkfile("RuleInitDemoParser.java", generatedText)

        val map = ActionLocationMapBuilder.build(grammar, generated)
        assertNotNull(map)
        assertEquals(1, map.entries.size)

        val entry = map.entries.single()
        assertEquals("Rule Init", entry.kind)
        assertEquals("rule", entry.scope)
        assertEquals(generatedText.indexOf("setup();"), entry.generatedBodyRange.first)
        assertTrue(entry.generatedBodyRange.last < generatedText.indexOf("try {"))
    }

    @Test
    fun `maps rule after inside generated finally before exitRule`() {
        val grammar =
            mkfile(
                "RuleAfterDemo.g4",
                """
                grammar RuleAfterDemo;

                rule
                @after {
                    cleanup();
                }
                    : ID EOF
                    ;

                ID : [a-zA-Z]+ ;
                WS : [ \t\r\n]+ -> skip ;
                """
                    .trimIndent(),
            )
        val generatedText =
            """
            public class RuleAfterDemoParser extends Parser {
                public final RuleContext rule() throws RecognitionException {
                    try {
                        enterOuterAlt(_localctx, 1);
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
        val generated = mkfile("RuleAfterDemoParser.java", generatedText)

        val map = ActionLocationMapBuilder.build(grammar, generated)
        assertNotNull(map)
        assertEquals(1, map.entries.size)

        val entry = map.entries.single()
        assertEquals("Rule After", entry.kind)
        assertEquals("rule", entry.scope)
        assertEquals(generatedText.indexOf("cleanup();"), entry.generatedBodyRange.first)
        assertTrue(entry.generatedBodyRange.last < generatedText.indexOf("exitRule();"))
    }

    @Test
    fun `maps lexer action through generated action dispatcher`() {
        val grammar =
            mkfile(
                "LexerActionDemo.g4",
                """
                lexer grammar LexerActionDemo;

                ID : 'a' { skip(); } ;
                WS : [ \t\r\n]+ -> skip ;
                """
                    .trimIndent(),
            )
        val generatedText =
            """
            public class LexerActionDemoLexer extends Lexer {
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
        val generated = mkfile("LexerActionDemoLexer.java", generatedText)

        val map = ActionLocationMapBuilder.build(grammar, generated)
        assertNotNull(map)
        assertEquals(1, map.entries.size)

        val entry = map.entries.single()
        assertEquals("Lexer Action", entry.kind)
        assertEquals("ID", entry.scope)
        assertEquals(generatedText.indexOf(" skip();"), entry.generatedBodyRange.first)
        assertEquals("skip();", entry.body)
    }

    @Test
    fun `maps exception handlers and finally clauses inside generated rule epilogue`() {
        val grammar =
            mkfile(
                "RuleEpilogue.g4",
                """
                grammar RuleEpilogue;

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
                    .trimIndent(),
            )
        val generatedText =
            """
            public class RuleEpilogueParser extends Parser {
                public final RuleContext rule() throws RecognitionException {
                    try {
                        enterOuterAlt(_localctx, 1);
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
        val generated = mkfile("RuleEpilogueParser.java", generatedText)

        val map = ActionLocationMapBuilder.build(grammar, generated)
        assertNotNull(map)
        assertEquals(2, map.entries.size)

        val catchEntry = assertNotNull(map.entries.firstOrNull { it.kind == "Exception Handler" })
        assertEquals("recoverHere(re, _localctx);", catchEntry.body)
        assertEquals(
            generatedText.indexOf("recoverHere(re, _localctx);"),
            catchEntry.generatedBodyRange.first,
        )

        val finallyEntry = assertNotNull(map.entries.firstOrNull { it.kind == "Finally Clause" })
        assertEquals("cleanup();", finallyEntry.body)
        assertEquals(generatedText.indexOf("cleanup();"), finallyEntry.generatedBodyRange.first)
        assertTrue(
            finallyEntry.generatedBodyRange.last < generatedText.indexOf("exitRule();"),
            "finally clause mapping should stop before the generated exitRule()",
        )
    }

    @Test
    fun `maps empty exception handlers and finally clauses to clause insertion slots`() {
        val grammar =
            mkfile(
                "RuleEpilogueEmpty.g4",
                """
                grammar RuleEpilogueEmpty;

                rule
                    : ID
                    ;
                    catch [RecognitionException re] {
                    }
                    finally {
                    }

                ID : [a-zA-Z]+ ;
                WS : [ \t\r\n]+ -> skip ;
                """
                    .trimIndent(),
            )
        val generatedText =
            """
            public class RuleEpilogueEmptyParser extends Parser {
                public final RuleContext rule() throws RecognitionException {
                    try {
                        match(ID);
                    }
                    catch (RecognitionException re) {
                    }
                    finally {
                        exitRule();
                    }
                    return _localctx;
                }
            }
            """
                .trimIndent()
        val generated = mkfile("RuleEpilogueEmptyParser.java", generatedText)

        val map = ActionLocationMapBuilder.build(grammar, generated)
        assertNotNull(map)
        assertEquals(2, map.entries.size)

        val catchEntry = assertNotNull(map.entries.firstOrNull { it.kind == "Exception Handler" })
        val catchExpectedOffset =
            generatedText.indexOf("catch (RecognitionException re) {") + "catch (RecognitionException re) {".length
        assertEquals("", catchEntry.body)
        assertTrue(catchEntry.generatedBodyRange.isEmpty())
        assertEquals(catchExpectedOffset, catchEntry.generatedBodyRange.first)
        assertEquals(
            catchExpectedOffset,
            GeneratedActionNavigationOffsetCalculator.calculate(
                generatedText,
                catchEntry.generatedBodyRange,
            ),
        )

        val finallyEntry = assertNotNull(map.entries.firstOrNull { it.kind == "Finally Clause" })
        val finallyExpectedOffset = generatedText.indexOf("finally {") + "finally {".length
        assertEquals("", finallyEntry.body)
        assertTrue(finallyEntry.generatedBodyRange.isEmpty())
        assertEquals(finallyExpectedOffset, finallyEntry.generatedBodyRange.first)
        assertEquals(
            finallyExpectedOffset,
            GeneratedActionNavigationOffsetCalculator.calculate(
                generatedText,
                finallyEntry.generatedBodyRange,
            ),
        )
    }

    @Test
    fun `buildFromText maps current editor snapshot to generated source`() {
        val grammarText =
            """
            parser grammar Foo;

            program
                : ID { System.out.println("hi"); }
                ;

            ID : [a-zA-Z]+ ;
            """
                .trimIndent()
        val generated =
            mkfile(
                "FooParser.java",
                """
                public class FooParser extends Parser {
                    public final ProgramContext program() throws RecognitionException {
                        match(ID);
                         System.out.println("hi");
                        return _localctx;
                    }
                }
                """
                    .trimIndent(),
            )

        val map =
            ActionLocationMapBuilder.buildFromText(
                grammarPath = File(tempDir, "Foo.g4").absolutePath,
                grammarText = grammarText,
                generatedFile = generated,
            )

        assertNotNull(map)
        assertEquals(1, map.entries.size)
        assertEquals("""System.out.println("hi");""", map.entries.single().body)
    }

    @Test
    fun `maps parser action when generated code appends a terminator`() {
        val grammar =
            mkfile(
                "Terminator.g4",
                """
                parser grammar Terminator;

                rule
                    : ID { this.IsNotIdentifierAssign() } EOF
                    ;

                ID : [a-zA-Z]+ ;
                """
                    .trimIndent(),
            )
        val generatedText =
            """
            public class TerminatorParser extends Parser {
                public final RuleContext rule() throws RecognitionException {
                    match(ID);
                     this.IsNotIdentifierAssign();
                    match(EOF);
                    return _localctx;
                }
            }
            """
                .trimIndent()
        val generated = mkfile("TerminatorParser.java", generatedText)

        val map = ActionLocationMapBuilder.build(grammar, generated)
        assertNotNull(map)
        assertEquals(1, map.entries.size)

        val entry = map.entries.single()
        val expectedStart = generatedText.indexOf(" this.IsNotIdentifierAssign()")
        assertEquals(
            expectedStart until (expectedStart + " this.IsNotIdentifierAssign()".length),
            entry.generatedBodyRange,
        )
        assertEquals(
            expectedStart + 1,
            GeneratedActionNavigationOffsetCalculator.calculate(
                generatedText,
                entry.generatedBodyRange,
            ),
        )
    }

    @Test
    fun `maps an empty parser action to the blank generated line in Java`() {
        val grammar =
            mkfile(
                "EmptyJava.g4",
                """
                parser grammar EmptyJava;

                program
                    : ID {} NUM
                    ;

                ID : [a-zA-Z]+ ;
                NUM : [0-9]+ ;
                """
                    .trimIndent(),
            )
        val generatedText =
            """
            public class EmptyJavaParser extends Parser {

                public final ProgramContext program() throws RecognitionException {
                    ProgramContext _localctx = new ProgramContext(_ctx, getState());
                    enterRule(_localctx, 0, RULE_program);
                    try {
                        enterOuterAlt(_localctx, 1);
                        setState(2);
                        match(ID);

                        setState(4);
                        match(NUM);
                    }
                    finally {
                        exitRule();
                    }
                    return _localctx;
                }
            }
            """
                .trimIndent()
        val generated = mkfile("EmptyJavaParser.java", generatedText)

        val map = ActionLocationMapBuilder.build(grammar, generated)
        assertNotNull(map)
        assertEquals(1, map.entries.size)

        val entry = map.entries.single()
        assertEquals("", entry.body)
        assertTrue(
            entry.grammarBodyRange.isEmpty(),
            "empty grammar action should preserve an empty body range",
        )
        assertTrue(
            entry.generatedBodyRange.isEmpty(),
            "empty generated action should preserve an empty generated range",
        )

        val expectedOffset = generatedText.indexOf("match(ID);") + "match(ID);\n".length
        assertEquals(expectedOffset, entry.generatedBodyRange.first)
        assertEquals(
            expectedOffset,
            GeneratedActionNavigationOffsetCalculator.calculate(
                generatedText,
                entry.generatedBodyRange,
            ),
        )
    }

    @Test
    fun `maps an empty parser action to the insertion column of a whitespace only generated line`() {
        val grammar =
            mkfile(
                "EmptyWhitespaceOnly.g4",
                """
                parser grammar EmptyWhitespaceOnly;

                program
                    : ID {} EOF
                    ;

                ID : [a-zA-Z]+ ;
                """
                    .trimIndent(),
            )
        val generatedText =
            listOf(
                    "public class EmptyWhitespaceOnlyParser extends Parser {",
                    "    public final ProgramContext program() throws RecognitionException {",
                    "        match(ID);",
                    "            ",
                    "        match(EOF);",
                    "        return _localctx;",
                    "    }",
                    "}",
                )
                .joinToString("\n")
        val generated = mkfile("EmptyWhitespaceOnlyParser.java", generatedText)

        val map = ActionLocationMapBuilder.build(grammar, generated)
        assertNotNull(map)
        assertEquals(1, map.entries.size)

        val entry = map.entries.single()
        val blankLineStart = generatedText.indexOf("\n            \n") + 1
        val expectedOffset = blankLineStart + "            ".length

        assertTrue(entry.generatedBodyRange.isEmpty())
        assertEquals(expectedOffset, entry.generatedBodyRange.first)
        assertEquals(
            expectedOffset,
            GeneratedActionNavigationOffsetCalculator.calculate(
                generatedText,
                entry.generatedBodyRange,
            ),
        )
    }

    @Test
    fun `maps an empty parser action to the blank generated line in Kotlin-style output`() {
        val grammar =
            mkfile(
                "EmptyKotlin.g4",
                """
                parser grammar EmptyKotlin;

                program
                    : ID {} EOF
                    ;

                ID : [a-zA-Z]+ ;
                """
                    .trimIndent(),
            )
        val generatedText =
            """
            class EmptyKotlinParser {
                public fun program(): ProgramContext {
                    var _localctx = ProgramContext(context, state)
                    var _token: Token?
                    var _ctx: RuleContext?

                    enterRule(_localctx, 0, Rules.Program)

                    try {
                        this.state = 1
                        match(ID)

                        this.state = 2
                        match(EOF)
                    }
                    catch (re: RecognitionException) {
                    }

                    return _localctx
                }
            }
            """
                .trimIndent()
        val generated = mkfile("EmptyKotlinParser.kt", generatedText)

        val map = ActionLocationMapBuilder.build(grammar, generated)
        assertNotNull(map)
        assertEquals(1, map.entries.size)

        val entry = map.entries.single()
        val expectedOffset = generatedText.indexOf("match(ID)") + "match(ID)\n".length

        assertEquals("", entry.body)
        assertEquals(expectedOffset, entry.generatedBodyRange.first)
        assertEquals(
            expectedOffset,
            GeneratedActionNavigationOffsetCalculator.calculate(
                generatedText,
                entry.generatedBodyRange,
            ),
        )
    }

    @Test
    fun `maps an empty parser action from explicit generated sentinels`() {
        val grammar =
            mkfile(
                "EmptyStamped.g4",
                """
                parser grammar EmptyStamped;

                program
                    : ID {} EOF
                    ;

                ID : [a-zA-Z]+ ;
                """
                    .trimIndent(),
            )
        val beginComment = "/*1*/"
        val endComment = "/*1*/"
        val generatedText =
            """
            public class EmptyStampedParser extends Parser {
                public final ProgramContext program() throws RecognitionException {
                    match(ID);
                    $beginComment$endComment
                    match(EOF);
                    return _localctx;
                }
            }
        """
                .trimIndent()
        val generated = mkfile("EmptyStampedParser.java", generatedText)

        val map = ActionLocationMapBuilder.build(grammar, generated)
        assertNotNull(map)
        assertEquals(1, map.entries.size)

        val entry = map.entries.single()
        val expectedOffset = generatedText.indexOf(beginComment) + beginComment.length

        assertEquals("", entry.body)
        assertTrue(
            entry.generatedBodyRange.isEmpty(),
            "sentinel-backed empty action should stay empty",
        )
        assertEquals(expectedOffset, entry.generatedBodyRange.first)
        assertEquals(
            expectedOffset,
            GeneratedActionNavigationOffsetCalculator.calculate(
                generatedText,
                entry.generatedBodyRange,
            ),
        )
    }

    @Test
    fun `maps generated body ranges correctly in CRLF generated files`() {
        val grammar =
            mkfile(
                "CrlfDemo.g4",
                """
                parser grammar CrlfDemo;

                program
                    : ID { first(); } EOF
                    ;

                ID : [a-zA-Z]+ ;
                """
                    .trimIndent(),
            )
        val generatedText =
            listOf(
                    "public class CrlfDemoParser extends Parser {",
                    "    public final ProgramContext program() throws RecognitionException {",
                    "        match(ID);",
                    "         first();",
                    "        match(EOF);",
                    "        return _localctx;",
                    "    }",
                    "}",
                )
                .joinToString("\r\n")
        val generated = mkfile("CrlfDemoParser.java", generatedText)

        val map = ActionLocationMapBuilder.build(grammar, generated)
        assertNotNull(map)
        assertEquals(1, map.entries.size)

        val entry = map.entries.single()
        val expectedStart = generatedText.indexOf(" first();")
        val expectedRange = expectedStart until (expectedStart + " first();".length)

        assertEquals(expectedRange, entry.generatedBodyRange)
        assertEquals(
            expectedStart + 1,
            GeneratedActionNavigationOffsetCalculator.calculate(
                generatedText,
                entry.generatedBodyRange,
            ),
        )
    }

    @Test
    fun `stamped preview uses source ordinals per code block`() {
        val grammar =
            mkfile(
                "OrdinalDemo.g4",
                """
                parser grammar OrdinalDemo;

                rule
                    : ID { first(); } NUM { second(); } EOF
                    ;

                ID  : [a-zA-Z]+ ;
                NUM : [0-9]+ ;
                """
                    .trimIndent(),
            )
        val generatedText =
            """
            public class OrdinalDemoParser extends Parser {
                public final RuleContext rule() {
                    match(ID);
                     first();
                    match(NUM);
                     second();
                    match(EOF);
                    return _localctx;
                }
            }
            """
                .trimIndent()
        val generated = mkfile("OrdinalDemoParser.java", generatedText)

        val map = ActionLocationMapBuilder.build(grammar, generated)
        assertNotNull(map)
        assertEquals(2, map.entries.size)

        val stampedText = GeneratedActionSentinelSupport.renderStampedText(generatedText, map.entries)
        val sentinelIds = Regex("/\\*(\\d+)\\*/").findAll(stampedText).map { it.groupValues[1].toInt() }.toList()

        assertEquals(listOf(1, 1, 2, 2), sentinelIds)
        assertEquals(listOf(1, 2), map.entries.map { it.sourceOrdinal })
    }

    @Test
    fun `stamped preview ordinals follow full grammar code block order`() {
        val grammar =
            mkfile(
                "OrdinalWithHeader.g4",
                """
                parser grammar OrdinalWithHeader;

                @header {
                    package demo;
                }

                rule
                    : ID { first(); } NUM { second(); } EOF
                    ;

                ID  : [a-zA-Z]+ ;
                NUM : [0-9]+ ;
                """
                    .trimIndent(),
            )
        val generatedText =
            """
            package demo;

            public class OrdinalWithHeaderParser extends Parser {
                public final RuleContext rule() {
                    match(ID);
                     first();
                    match(NUM);
                     second();
                    match(EOF);
                    return _localctx;
                }
            }
            """
                .trimIndent()
        val generated = mkfile("OrdinalWithHeaderParser.java", generatedText)

        val map = ActionLocationMapBuilder.build(grammar, generated)
        assertNotNull(map)
        assertEquals(2, map.entries.size)

        val stampedText = GeneratedActionSentinelSupport.renderStampedText(generatedText, map.entries)
        val sentinelIds = Regex("/\\*(\\d+)\\*/").findAll(stampedText).map { it.groupValues[1].toInt() }.toList()

        assertEquals(listOf(2, 2, 3, 3), sentinelIds)
        assertEquals(listOf(2, 3), map.entries.map { it.sourceOrdinal })
    }

    @Test
    fun `stamped semantic predicate preview body is normalized for compilation`() {
        val grammar =
            mkfile(
                "PollutedPredicate.g4",
                """
                parser grammar PollutedPredicate;

                rule
                    : { this.IsReady(); }? ID
                    ;

                ID : [a-zA-Z]+ ;
                """
                    .trimIndent(),
            )
        val generatedText =
            """
            public class PollutedPredicateParser extends Parser {
                public final RuleContext rule() {
                    if (!( this.IsReady(); )) throw new FailedPredicateException(this, "this.IsReady();");
                    return _localctx;
                }

                private boolean rule_sempred(RuleContext _localctx, int predIndex) {
                    switch (predIndex) {
                    case 0:
                        return this.IsReady();;
                    }
                    return true;
                }
            }
            """
                .trimIndent()
        val generated = mkfile("PollutedPredicateParser.java", generatedText)

        val map = ActionLocationMapBuilder.build(grammar, generated)
        assertNotNull(map)
        assertEquals(1, map.entries.size)

        val stampedText = GeneratedActionSentinelSupport.renderStampedText(generatedText, map.entries)
        assertTrue(stampedText.contains("/*1*/"))
        assertTrue(stampedText.contains("this.IsReady()"))
        assertFalse(stampedText.contains("this.IsReady();/*1*/"))
        assertFalse(
            Regex("""if\s*\(!\(\s*this\.IsReady\(\);\s*\)\)""").containsMatchIn(stampedText),
        )
        assertTrue(Regex("""return\s+this\.IsReady\(\);\s""").containsMatchIn(stampedText))
        assertFalse(stampedText.contains("return this.IsReady();;"))
        assertTrue(stampedText.contains("\"this.IsReady()\""))
    }

    @Test
    fun `pairs duplicate-body actions in source order`() {
        val grammar =
            mkfile(
                "Dup.g4",
                """
                parser grammar Dup;

                ruleA : ID { count++; } ;
                ruleB : NUM { count++; } ;

                ID  : [a-zA-Z]+ ;
                NUM : [0-9]+ ;
                """
                    .trimIndent(),
            )
        val generated =
            mkfile(
                "DupParser.java",
                """
                public class DupParser extends Parser {
                    public final RuleAContext ruleA() {
                        enterOuterAlt(_localctx, 1);
                        match(ID);
                         count++;
                        return _localctx;
                    }
                    public final RuleBContext ruleB() {
                        enterOuterAlt(_localctx, 1);
                        match(NUM);
                         count++;
                        return _localctx;
                    }
                }
                """
                    .trimIndent(),
            )

        val map = ActionLocationMapBuilder.build(grammar, generated)
        assertNotNull(map)
        assertEquals(2, map.entries.size)

        val (first, second) = map.entries
        assertTrue(
            first.generatedBodyRange.first < second.generatedBodyRange.first,
            "Source-order pairing should produce monotonically increasing generated offsets",
        )
    }

    @Test
    fun `maps a semantic predicate body`() {
        val grammar =
            mkfile(
                "Pred.g4",
                """
                parser grammar Pred;

                program
                    : { isValid() }? ID
                    ;

                ID : [a-zA-Z]+ ;
                """
                    .trimIndent(),
            )
        val generated =
            mkfile(
                "PredParser.java",
                """
                public class PredParser extends Parser {
                    public final ProgramContext program() {
                        if (!( isValid() )) throw new FailedPredicateException(this, "isValid()");
                        setState(2);
                        match(ID);
                        return _localctx;
                    }
                }
                """
                    .trimIndent(),
            )

        val map = ActionLocationMapBuilder.build(grammar, generated)
        assertNotNull(map)
        // This case only checks classification when a mapping is present.
        if (map.entries.isNotEmpty()) {
            val entry = map.entries.first()
            assertTrue(
                entry.kind == "Semantic Predicate" || entry.kind == "Parser Action",
                "Predicate should be classified as Semantic Predicate or Parser Action; got ${entry.kind}",
            )
        }
    }

    @Test
    fun `maps semantic predicate to the inner generated predicate expression`() {
        val grammar =
            mkfile(
                "PredExact.g4",
                """
                parser grammar PredExact;

                program
                    : { this.IsNotIdentifierAssign() }? ID
                    ;

                ID : [a-zA-Z]+ ;
                """
                    .trimIndent(),
            )
        val generatedText =
            """
            public class PredExactParser extends Parser {
                public final ProgramContext program() {
                    if (!( this.IsNotIdentifierAssign() )) throw new FailedPredicateException(this, "this.IsNotIdentifierAssign()");
                    setState(2);
                    match(ID);
                    return _localctx;
                }
            }
            """
                .trimIndent()
        val generated = mkfile("PredExactParser.java", generatedText)

        val map = ActionLocationMapBuilder.build(grammar, generated)
        assertNotNull(map)
        assertEquals(1, map.entries.size)

        val entry = map.entries.single()
        assertEquals("Semantic Predicate", entry.kind)

        val expectedStart = generatedText.indexOf(" this.IsNotIdentifierAssign() ")
        val expectedRange = expectedStart until (expectedStart + " this.IsNotIdentifierAssign() ".length)
        assertEquals(expectedRange, entry.generatedBodyRange)
        assertEquals(
            expectedStart + 1,
            GeneratedActionNavigationOffsetCalculator.calculate(
                generatedText,
                entry.generatedBodyRange,
            ),
        )
    }

    @Test
    fun `maps multiple distinct actions inside one rule`() {
        val grammar =
            mkfile(
                "Multi.g4",
                """
                parser grammar Multi;

                program
                    : ID { first(); } NUM { second(); } EOF
                    ;

                ID  : [a-zA-Z]+ ;
                NUM : [0-9]+ ;
                """
                    .trimIndent(),
            )
        val generated =
            mkfile(
                "MultiParser.java",
                """
                public class MultiParser extends Parser {
                    public final ProgramContext program() {
                        enterOuterAlt(_localctx, 1);
                        match(ID);
                         first();
                        match(NUM);
                         second();
                        match(EOF);
                        return _localctx;
                    }
                }
                """
                    .trimIndent(),
            )

        val map = ActionLocationMapBuilder.build(grammar, generated)
        assertNotNull(map)
        assertEquals(2, map.entries.size)
        assertEquals("first();", map.entries[0].body)
        assertEquals("second();", map.entries[1].body)
        assertTrue(
            map.entries[0].generatedBodyRange.first < map.entries[1].generatedBodyRange.first,
            "Actions should appear in source order in the generated file",
        )
    }

    @Test
    fun `omits actions whose body cannot be found in the generated source`() {
        val grammar =
            mkfile(
                "Missing.g4",
                """
                parser grammar Missing;

                program : ID { uniqueBodyThatDoesNotAppearInGenerated(); } ;
                ID : [a-zA-Z]+ ;
                """
                    .trimIndent(),
            )
        val generated =
            mkfile(
                "MissingParser.java",
                """
                public class MissingParser extends Parser {
                    public final ProgramContext program() {
                        match(ID);
                        return _localctx;
                    }
                }
                """
                    .trimIndent(),
            )

        val map = ActionLocationMapBuilder.build(grammar, generated)
        assertNotNull(map)
        assertEquals(0, map.entries.size)
    }

    @Test
    fun `findByGrammarOffset returns the right entry`() {
        val grammar =
            mkfile(
                "Lookup.g4",
                """
                parser grammar Lookup;

                program : ID { hello(); } ;
                ID : [a-zA-Z]+ ;
                """
                    .trimIndent(),
            )
        val generated =
            mkfile(
                "LookupParser.java",
                """
                public class LookupParser extends Parser {
                    public final ProgramContext program() {
                        match(ID);
                         hello();
                        return _localctx;
                    }
                }
                """
                    .trimIndent(),
            )

        val map = ActionLocationMapBuilder.build(grammar, generated)!!
        val entry = map.entries.single()

        val bodyMid = (entry.grammarBodyRange.first + entry.grammarBodyRange.last) / 2
        assertEquals(entry, map.findByGrammarOffset(bodyMid))

        assertNull(map.findByGrammarOffset(0))
    }

    @Test
    fun `returns empty map for grammar with no embedded code`() {
        val grammar =
            mkfile(
                "Bare.g4",
                """
                parser grammar Bare;
                program : ID ;
                ID : [a-zA-Z]+ ;
                """
                    .trimIndent(),
            )
        val generated = mkfile("BareParser.java", "public class BareParser {}")

        val map = ActionLocationMapBuilder.build(grammar, generated)
        assertNotNull(map)
        assertEquals(0, map.entries.size)
    }

    @Test
    fun `returns null for missing files`() {
        val phantom = File("/tmp/does-not-exist-${System.nanoTime()}.g4")
        val realGen = mkfile("RealParser.java", "public class RealParser {}")

        assertNull(ActionLocationMapBuilder.build(phantom, realGen))

        val realGrammar = mkfile("Real.g4", "parser grammar Real; program : ID ; ID : [a-z]+ ;")
        val phantomGen = File("/tmp/does-not-exist-${System.nanoTime()}.java")
        assertNull(ActionLocationMapBuilder.build(realGrammar, phantomGen))
    }
}
