package com.antlr4plus.parsing.kotlin

import com.antlr4plus.parsing.CaseChangingStrategy
import java.lang.reflect.Proxy
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.misc.Interval

/**
 * Bridge the isolated Kotlin interface to Java's code-point stream. The bundled Kotlin StringCharStream incorrectly
 * returns EOF for a supplementary character at the end of input. Folding only lookahead also keeps token text and
 * offsets tied to the original source in both preview runtimes.
 */
internal fun kotlinCharStream(text: String, name: String, streamClass: Class<*>, strategy: CaseChangingStrategy): Any {
    val source = CharStreams.fromString(text, name)
    val input = strategy.applyTo(source) ?: source
    return Proxy.newProxyInstance(streamClass.classLoader, arrayOf(streamClass)) { proxy, method, args ->
        when (method.name) {
            "LA" -> input.LA(args!![0] as Int)
            "index" -> input.index()
            "size" -> input.size()
            "mark" -> input.mark()
            "consume" -> {
                input.consume()
                null
            }
            "seek" -> {
                input.seek(args!![0] as Int)
                null
            }
            "release" -> {
                input.release(args!![0] as Int)
                null
            }
            "getSourceName" -> input.sourceName
            "getText" -> {
                val interval = args!![0]
                val start = interval.javaClass.getMethod("getA").invoke(interval) as Int
                val stop = interval.javaClass.getMethod("getB").invoke(interval) as Int
                input.getText(Interval.of(start, stop))
            }
            "equals" -> proxy === args?.getOrNull(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> text
            else -> throw UnsupportedOperationException("Unsupported Kotlin CharStream method: ${method.name}")
        }
    }
}
