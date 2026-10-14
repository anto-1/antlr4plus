package com.antlr4plus.parsing.kotlin

import com.antlr4plus.parsing.CaseChangingStrategy
import java.lang.reflect.Constructor

/** Shares stream semantics and runtime fixes between preview parsing and timed benchmark iterations. */
internal class KotlinLexerFactory(private val constructor: Constructor<*>) {
    private val streamClass = constructor.parameterTypes.single()
    private val installCompatibility =
        constructor.declaringClass.classLoader
            .loadClass("com.antlr4plus.parsing.kotlin.KotlinLexerCompatibility")
            .getMethod("install", Any::class.java)

    fun create(text: String, sourceName: String, strategy: CaseChangingStrategy): Any {
        val stream = kotlinCharStream(text, sourceName, streamClass, strategy)
        return constructor.newInstance(stream).also { installCompatibility.invoke(null, it) }
    }
}
