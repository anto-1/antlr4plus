package com.antlr4plus.parsing.kotlin;

import org.antlr.v4.kotlinruntime.CharStream;
import org.antlr.v4.kotlinruntime.Lexer;
import org.antlr.v4.kotlinruntime.atn.LexerATNSimulator;

/** Keeps the ANTLR Kotlin 1.0.9 lexer on code-point coordinates without exposing its Kotlin metadata. */
public final class KotlinLexerCompatibility {
    private KotlinLexerCompatibility() {}

    /** Called after construction, before the lexer consumes any preview input. */
    public static void install(Object candidate) {
        Lexer lexer = (Lexer) candidate;
        LexerATNSimulator original = lexer.getInterpreter();
        // A grammar can supply its own simulator, including overrides of consume/match.
        if (original.getClass() == LexerATNSimulator.class) {
            lexer.setInterpreter(new CodePointLexerATNSimulator(lexer, original));
        }
    }

    private static final class CodePointLexerATNSimulator extends LexerATNSimulator {
        CodePointLexerATNSimulator(Lexer lexer, LexerATNSimulator original) {
            super(lexer, original.getAtn(), original.getDecisionToDFA(), original.getSharedContextCache());
            copyState(original);
        }

        @Override
        public void consume(CharStream input) {
            // Do not narrow to char: U+1000A and its higher-plane counterparts are not LF.
            if (input.LA(1) == '\n') {
                setLine(getLine() + 1);
                setCharPositionInLine(0);
            } else {
                setCharPositionInLine(getCharPositionInLine() + 1);
            }
            input.consume();
        }
    }
}
