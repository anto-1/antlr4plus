package com.antlr4plus.onboarding

/** Example-to-grammar bindings for import after cloning grammars-v4. */
object GrammarsV4Bindings {

    /** Stored as a resource to keep the generated list out of bytecode. */
    val BINDINGS: String by lazy {
        try {
            GrammarsV4Bindings::class
                .java
                .getResourceAsStream("/bindings/grammars-v4-bindings.txt")
                ?.bufferedReader()
                ?.readText() ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}
