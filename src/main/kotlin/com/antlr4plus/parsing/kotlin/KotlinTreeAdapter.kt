package com.antlr4plus.parsing.kotlin

import com.antlr4plus.util.rethrowFatalOrCancellation
import org.antlr.v4.runtime.Parser
import org.antlr.v4.runtime.RuleContext
import org.antlr.v4.runtime.misc.Interval
import org.antlr.v4.runtime.tree.ParseTree
import org.antlr.v4.runtime.tree.ParseTreeVisitor
import org.antlr.v4.runtime.tree.RuleNode

/**
 * Reflective adapter from the isolated Kotlin runtime to Java ParseTree. Implements RuleNode for every node; use
 * [isRuleContext] or [isTerminalNode] to distinguish them.
 */
class KotlinTreeAdapter
private constructor(
    private val kotlinTree: Any,
    private val classLoader: ClassLoader,
) : ParseTree, RuleNode {

    companion object {
        fun wrap(kotlinTree: Any?, classLoader: ClassLoader): KotlinTreeAdapter? = kotlinTree?.let {
            KotlinTreeAdapter(it, classLoader)
        }
    }

    private val kotlinRuleContextClass: Class<*> by lazy {
        classLoader.loadClass("org.antlr.v4.kotlinruntime.RuleContext")
    }

    private val kotlinParserRuleContextClass: Class<*> by lazy {
        classLoader.loadClass("org.antlr.v4.kotlinruntime.ParserRuleContext")
    }

    override fun getParent(): ParseTree? =
        try {
            val method = kotlinTree.javaClass.getMethod("getParent")
            val parent = method.invoke(kotlinTree)
            wrap(parent, classLoader)
        } catch (e: Exception) {
            rethrowFatalOrCancellation(e)
            null
        }

    override fun getPayload(): Any? =
        try {
            val method = kotlinTree.javaClass.getMethod("getPayload")
            method.invoke(kotlinTree)
        } catch (e: Exception) {
            rethrowFatalOrCancellation(e)
            kotlinTree
        }

    override fun getChild(i: Int): ParseTree? =
        try {
            val method = kotlinTree.javaClass.getMethod("getChild", Int::class.java)
            val child = method.invoke(kotlinTree, i)
            wrap(child, classLoader)
        } catch (e: Exception) {
            rethrowFatalOrCancellation(e)
            null
        }

    override fun getChildCount(): Int =
        try {
            val method = kotlinTree.javaClass.getMethod("getChildCount")
            method.invoke(kotlinTree) as? Int ?: 0
        } catch (e: Exception) {
            rethrowFatalOrCancellation(e)
            0
        }

    override fun toStringTree(): String =
        try {
            val method = kotlinTree.javaClass.getMethod("toStringTree")
            method.invoke(kotlinTree) as? String ?: kotlinTree.toString()
        } catch (e: Exception) {
            rethrowFatalOrCancellation(e)
            kotlinTree.toString()
        }

    override fun getSourceInterval(): Interval =
        try {
            val method = kotlinTree.javaClass.getMethod("getSourceInterval")
            val kotlinInterval = method.invoke(kotlinTree)
            if (kotlinInterval != null) {
                val aMethod = kotlinInterval.javaClass.getMethod("getA")
                val bMethod = kotlinInterval.javaClass.getMethod("getB")
                val a = aMethod.invoke(kotlinInterval) as? Int ?: -1
                val b = bMethod.invoke(kotlinInterval) as? Int ?: -1
                Interval.of(a, b)
            } else {
                Interval.INVALID
            }
        } catch (e: Exception) {
            rethrowFatalOrCancellation(e)
            Interval.INVALID
        }

    override fun getText(): String =
        try {
            val method = kotlinTree.javaClass.getMethod("getText")
            method.invoke(kotlinTree) as? String ?: ""
        } catch (e: Exception) {
            rethrowFatalOrCancellation(e)
            ""
        }

    override fun setParent(parent: RuleContext?) {
        // This adapter supports read-only traversal.
    }

    // Visitor dispatch is not implemented by this adapter.
    override fun <T> accept(visitor: ParseTreeVisitor<out T>?): T? = null

    override fun toStringTree(parser: Parser?): String = toStringTree()

    override fun getRuleContext(): RuleContext? =
        if (isRuleContext()) {
            KotlinRuleContextAdapter(kotlinTree, classLoader)
        } else {
            null
        }

    fun isTerminalNode(): Boolean =
        try {
            val terminalNodeClass = classLoader.loadClass("org.antlr.v4.kotlinruntime.tree.TerminalNode")
            terminalNodeClass.isInstance(kotlinTree)
        } catch (e: Exception) {
            rethrowFatalOrCancellation(e)
            false
        }

    fun isErrorNode(): Boolean =
        try {
            val errorNodeClass = classLoader.loadClass("org.antlr.v4.kotlinruntime.tree.ErrorNode")
            errorNodeClass.isInstance(kotlinTree)
        } catch (e: Exception) {
            rethrowFatalOrCancellation(e)
            false
        }

    fun isRuleContext(): Boolean =
        try {
            kotlinRuleContextClass.isInstance(kotlinTree)
        } catch (e: Exception) {
            rethrowFatalOrCancellation(e)
            false
        }

    fun isParserRuleContext(): Boolean =
        try {
            kotlinParserRuleContextClass.isInstance(kotlinTree)
        } catch (e: Exception) {
            rethrowFatalOrCancellation(e)
            false
        }

    fun getException(): Any? =
        try {
            if (kotlinParserRuleContextClass.isInstance(kotlinTree)) {
                val field = kotlinTree.javaClass.getField("exception")
                field.get(kotlinTree)
            } else {
                null
            }
        } catch (e: Exception) {
            rethrowFatalOrCancellation(e)
            null
        }

    /** Returns the rule index, or -1 for non-rule nodes or reflection failure. */
    fun getRuleIndex(): Int =
        try {
            if (isRuleContext()) {
                val method = kotlinTree.javaClass.getMethod("getRuleIndex")
                method.invoke(kotlinTree) as? Int ?: -1
            } else {
                -1
            }
        } catch (e: Exception) {
            rethrowFatalOrCancellation(e)
            -1
        }

    fun getSymbol(): Any? =
        try {
            if (isTerminalNode()) {
                val method = kotlinTree.javaClass.getMethod("getSymbol")
                method.invoke(kotlinTree)
            } else {
                null
            }
        } catch (e: Exception) {
            rethrowFatalOrCancellation(e)
            null
        }

    fun getTokenType(): Int =
        try {
            val symbol = getSymbol()
            if (symbol != null) {
                val method = symbol.javaClass.getMethod("getType")
                method.invoke(symbol) as? Int ?: -1
            } else {
                -1
            }
        } catch (e: Exception) {
            rethrowFatalOrCancellation(e)
            -1
        }

    fun getTokenText(): String =
        try {
            val symbol = getSymbol()
            if (symbol != null) {
                val method = symbol.javaClass.getMethod("getText")
                method.invoke(symbol) as? String ?: ""
            } else {
                text
            }
        } catch (e: Exception) {
            rethrowFatalOrCancellation(e)
            text
        }

    fun getStart(): Any? =
        try {
            if (kotlinParserRuleContextClass.isInstance(kotlinTree)) {
                try {
                    val method = kotlinTree.javaClass.getMethod("getStart")
                    method.invoke(kotlinTree)
                } catch (e: NoSuchMethodException) {
                    val field = kotlinTree.javaClass.getField("start")
                    field.get(kotlinTree)
                }
            } else {
                null
            }
        } catch (e: Exception) {
            rethrowFatalOrCancellation(e)
            null
        }

    fun getStop(): Any? =
        try {
            if (kotlinParserRuleContextClass.isInstance(kotlinTree)) {
                try {
                    val method = kotlinTree.javaClass.getMethod("getStop")
                    method.invoke(kotlinTree)
                } catch (e: NoSuchMethodException) {
                    val field = kotlinTree.javaClass.getField("stop")
                    field.get(kotlinTree)
                }
            } else {
                null
            }
        } catch (e: Exception) {
            rethrowFatalOrCancellation(e)
            null
        }

    fun getTokenIndex(token: Any?): Int {
        return try {
            if (token == null) return -1
            val method = token.javaClass.getMethod("getTokenIndex")
            method.invoke(token) as? Int ?: -1
        } catch (e: Exception) {
            rethrowFatalOrCancellation(e)
            -1
        }
    }

    /** Tests for a parser exception with a stop token preceding the start token. */
    fun ruleFailedAndMatchedNothing(): Boolean {
        return try {
            if (!kotlinParserRuleContextClass.isInstance(kotlinTree)) return false
            if (getException() == null) return false
            val start = getStart() ?: return false
            val stop = getStop() ?: return false
            val startIndex = getTokenIndex(start)
            val stopIndex = getTokenIndex(stop)
            stopIndex < startIndex
        } catch (e: Exception) {
            rethrowFatalOrCancellation(e)
            false
        }
    }

    override fun toString(): String = "KotlinTreeAdapter(${kotlinTree.javaClass.simpleName})"

    // Wrappers of the same underlying node must share map keys.

    override fun equals(other: Any?): Boolean =
        this === other || (other is KotlinTreeAdapter && kotlinTree === other.kotlinTree)

    override fun hashCode(): Int = System.identityHashCode(kotlinTree)
}

class KotlinRuleContextAdapter(
    private val kotlinRuleContext: Any,
    private val classLoader: ClassLoader,
) : RuleContext() {

    override fun getRuleIndex(): Int =
        try {
            val method = kotlinRuleContext.javaClass.getMethod("getRuleIndex")
            method.invoke(kotlinRuleContext) as? Int ?: -1
        } catch (e: Exception) {
            rethrowFatalOrCancellation(e)
            -1
        }

    override fun getChildCount(): Int =
        try {
            val method = kotlinRuleContext.javaClass.getMethod("getChildCount")
            method.invoke(kotlinRuleContext) as? Int ?: 0
        } catch (e: Exception) {
            rethrowFatalOrCancellation(e)
            0
        }

    override fun getChild(i: Int): ParseTree? =
        try {
            val method = kotlinRuleContext.javaClass.getMethod("getChild", Int::class.java)
            val child = method.invoke(kotlinRuleContext, i)
            KotlinTreeAdapter.wrap(child, classLoader)
        } catch (e: Exception) {
            rethrowFatalOrCancellation(e)
            null
        }

    override fun getParent(): RuleContext? =
        try {
            val method = kotlinRuleContext.javaClass.getMethod("getParent")
            val parent = method.invoke(kotlinRuleContext)
            if (parent != null) {
                KotlinRuleContextAdapter(parent, classLoader)
            } else {
                null
            }
        } catch (e: Exception) {
            rethrowFatalOrCancellation(e)
            null
        }

    override fun getText(): String =
        try {
            val method = kotlinRuleContext.javaClass.getMethod("getText")
            method.invoke(kotlinRuleContext) as? String ?: ""
        } catch (e: Exception) {
            rethrowFatalOrCancellation(e)
            ""
        }

    override fun getSourceInterval(): Interval =
        try {
            val method = kotlinRuleContext.javaClass.getMethod("getSourceInterval")
            val kotlinInterval = method.invoke(kotlinRuleContext)
            if (kotlinInterval != null) {
                val aMethod = kotlinInterval.javaClass.getMethod("getA")
                val bMethod = kotlinInterval.javaClass.getMethod("getB")
                val a = aMethod.invoke(kotlinInterval) as? Int ?: -1
                val b = bMethod.invoke(kotlinInterval) as? Int ?: -1
                Interval.of(a, b)
            } else {
                Interval.INVALID
            }
        } catch (e: Exception) {
            rethrowFatalOrCancellation(e)
            Interval.INVALID
        }

    override fun equals(other: Any?): Boolean =
        this === other || (other is KotlinRuleContextAdapter && kotlinRuleContext === other.kotlinRuleContext)

    override fun hashCode(): Int = System.identityHashCode(kotlinRuleContext)
}
