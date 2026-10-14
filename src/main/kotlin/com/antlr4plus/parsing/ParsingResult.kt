package com.antlr4plus.parsing

import com.antlr4plus.parsing.kotlin.KotlinTreeAdapter
import com.antlr4plus.util.rethrowFatalOrCancellation
import com.intellij.openapi.diagnostic.Logger
import org.antlr.intellij.adaptor.parser.SyntaxErrorListener
import org.antlr.v4.runtime.Parser
import org.antlr.v4.runtime.Vocabulary
import org.antlr.v4.runtime.tree.ParseTree
import org.antlr.v4.runtime.tree.Tree

private val LOG = Logger.getInstance(ParsingResult::class.java)

/**
 * Normalizes Java and Kotlin parser results for viewers. Java results hold [parser]; Kotlin results hold a reflective
 * parser and [KotlinTreeAdapter].
 */
class ParsingResult
private constructor(
    val parser: Parser?,
    val tree: Tree,
    val syntaxErrorListener: SyntaxErrorListener?,
    internal val kotlinParser: Any?,
    private val classLoader: ClassLoader?,
    val errors: List<String> = emptyList(),
) {
    companion object {
        fun java(
            parser: Parser,
            tree: Tree,
            syntaxErrorListener: SyntaxErrorListener?,
        ): ParsingResult = ParsingResult(parser, tree, syntaxErrorListener, null, null)

        fun kotlin(
            parser: Any,
            tree: Any,
            classLoader: ClassLoader,
            errors: List<String>,
            syntaxErrorListener: SyntaxErrorListener? = null,
        ): ParsingResult {
            val wrappedTree =
                KotlinTreeAdapter.wrap(tree, classLoader)
                    ?: throw IllegalArgumentException("Failed to wrap Kotlin parse tree")
            return ParsingResult(
                null,
                wrappedTree,
                syntaxErrorListener,
                parser,
                classLoader,
                errors,
            )
        }
    }

    val isKotlin: Boolean
        get() = kotlinParser != null

    val javaTree: ParseTree?
        get() = tree as? ParseTree

    val ruleNames: Array<String>?
        get() =
            if (isKotlin) {
                getKotlinRuleNames()
            } else {
                parser?.ruleNames
            }

    /** Java vocabulary, or null for Kotlin results; use name helpers for either runtime. */
    val vocabulary: Vocabulary?
        get() = parser?.vocabulary

    fun getSymbolicName(tokenType: Int): String? =
        if (isKotlin) {
            getKotlinVocabularyName(tokenType, "getSymbolicName")
        } else {
            vocabulary?.getSymbolicName(tokenType)
        }

    fun getDisplayName(tokenType: Int): String? =
        if (isKotlin) {
            getKotlinVocabularyName(tokenType, "getDisplayName")
        } else {
            vocabulary?.getDisplayName(tokenType)
        }

    /** Finds a terminal whose inclusive UTF-16 range contains the editor offset. */
    fun findTerminalAtOffset(offset: Int): ParseTree? = javaTree?.let { root ->
        findTerminalNodeAtOffset(root, offset)
    }

    /** Copies token fields, converting start/stop indices to editor UTF-16 offsets. */
    fun getTokenFromTerminal(terminal: ParseTree): TokenInfo? = tokenInfoFromTerminal(terminal)

    /** Returns inclusive UTF-16 offsets from the rule start/stop tokens. */
    fun getRuleCharacterRange(rule: ParseTree): Pair<Int, Int>? = ruleCharacterRange(rule)

    private fun getKotlinRuleNames(): Array<String>? =
        try {
            val method = kotlinParser?.javaClass?.getMethod("getRuleNames")
            @Suppress("UNCHECKED_CAST")
            method?.invoke(kotlinParser) as? Array<String>
        } catch (e: Exception) {
            rethrowFatalOrCancellation(e)
            null
        }

    private fun getKotlinVocabulary(): Any? =
        try {
            val method = kotlinParser?.javaClass?.getMethod("getVocabulary")
            method?.invoke(kotlinParser)
        } catch (e: Exception) {
            rethrowFatalOrCancellation(e)
            null
        }

    private fun getKotlinVocabularyName(tokenType: Int, getter: String): String? {
        return try {
            val vocab = getKotlinVocabulary() ?: return null
            val method = vocab.javaClass.getMethod(getter, Int::class.javaPrimitiveType)
            method.invoke(vocab, tokenType) as? String
        } catch (e: Exception) {
            rethrowFatalOrCancellation(e)
            null
        }
    }

    data class TokenInfo(
        val type: Int,
        val text: String,
        val line: Int,
        val charPositionInLine: Int,
        val startIndex: Int,
        val stopIndex: Int,
        val channel: Int,
        val tokenIndex: Int,
    )

    data class KotlinDecisionInfo(
        val decision: Int,
        val invocations: Long,
        val timeInPrediction: Long,
        val sllTotalLook: Long,
        val sllMinLook: Long,
        val sllMaxLook: Long,
        val llTotalLook: Long,
        val llMinLook: Long,
        val llMaxLook: Long,
        val ambiguitiesCount: Long,
        val contextSensitivitiesCount: Long,
        val predicateEvalsCount: Long = 0,
        val sllATNTransitions: Long = 0,
        val llATNTransitions: Long = 0,
    )

    fun getKotlinDecisionInfoList(): List<KotlinDecisionInfo>? {
        if (!isKotlin || kotlinParser == null) {
            LOG.debug(
                "getKotlinDecisionInfoList: isKotlin=$isKotlin, kotlinParser=${kotlinParser != null}",
            )
            return null
        }

        return try {
            LOG.debug(
                "getKotlinDecisionInfoList: getting parseInfo from ${kotlinParser.javaClass.name}",
            )
            val getParseInfoMethod = kotlinParser.javaClass.getMethod("getParseInfo")
            val parseInfo = getParseInfoMethod.invoke(kotlinParser)
            LOG.debug(
                "getKotlinDecisionInfoList: parseInfo=${parseInfo?.javaClass?.name ?: "NULL"}",
            )
            if (parseInfo == null) return null

            val getDecisionInfoMethod = parseInfo.javaClass.getMethod("getDecisionInfo")
            val decisionInfoArray = getDecisionInfoMethod.invoke(parseInfo) as? Array<*>
            LOG.debug(
                "getKotlinDecisionInfoList: decisionInfoArray size=${decisionInfoArray?.size ?: "NULL"}",
            )
            if (decisionInfoArray == null) return null

            decisionInfoArray.firstOrNull()?.let { firstInfo ->
                LOG.debug(
                    "getKotlinDecisionInfoList: first entry class=${firstInfo.javaClass.name}",
                )
                LOG.debug(
                    "getKotlinDecisionInfoList: fields=${firstInfo.javaClass.fields.map { it.name }}",
                )
                LOG.debug(
                    "getKotlinDecisionInfoList: declaredFields=${firstInfo.javaClass.declaredFields.map { it.name }}",
                )
                LOG.debug(
                    "getKotlinDecisionInfoList: methods=${firstInfo.javaClass.methods.filter { it.name.startsWith("get") || it.parameterCount == 0 }.map { it.name }}",
                )
            }

            decisionInfoArray
                .mapNotNull { info ->
                    if (info == null) return@mapNotNull null
                    try {
                        // Read Kotlin properties through their private backing fields.
                        fun getIntField(name: String): Int {
                            val field = info.javaClass.getDeclaredField(name)
                            field.isAccessible = true
                            return field.getInt(info)
                        }
                        fun getLongField(name: String): Long {
                            val field = info.javaClass.getDeclaredField(name)
                            field.isAccessible = true
                            return field.getLong(info)
                        }
                        fun getListSizeField(name: String): Long {
                            val field = info.javaClass.getDeclaredField(name)
                            field.isAccessible = true
                            val list = field.get(info) as? List<*>
                            return list?.size?.toLong() ?: 0L
                        }

                        KotlinDecisionInfo(
                            decision = getIntField("decision"),
                            invocations = getLongField("invocations"),
                            timeInPrediction = getLongField("timeInPrediction"),
                            sllTotalLook = getLongField("SLL_TotalLook"),
                            sllMinLook = getLongField("SLL_MinLook"),
                            sllMaxLook = getLongField("SLL_MaxLook"),
                            llTotalLook = getLongField("LL_TotalLook"),
                            llMinLook = getLongField("LL_MinLook"),
                            llMaxLook = getLongField("LL_MaxLook"),
                            ambiguitiesCount = getListSizeField("ambiguities"),
                            contextSensitivitiesCount = getListSizeField("contextSensitivities"),
                            predicateEvalsCount =
                                try {
                                    getListSizeField("predicateEvals")
                                } catch (e: Exception) {
                                    rethrowFatalOrCancellation(e)
                                    0L
                                },
                            sllATNTransitions =
                                try {
                                    getLongField("SLL_ATNTransitions")
                                } catch (e: Exception) {
                                    rethrowFatalOrCancellation(e)
                                    0L
                                },
                            llATNTransitions =
                                try {
                                    getLongField("LL_ATNTransitions")
                                } catch (e: Exception) {
                                    rethrowFatalOrCancellation(e)
                                    0L
                                },
                        )
                    } catch (e: Exception) {
                        rethrowFatalOrCancellation(e)
                        if (decisionInfoArray.indexOf(info) == 0) {
                            LOG.warn(
                                "getKotlinDecisionInfoList: failed to map entry: ${e.javaClass.simpleName}: ${e.message}",
                            )
                        }
                        null
                    }
                }
                .also { list ->
                    LOG.debug("getKotlinDecisionInfoList: mapped ${list.size} entries")
                }
                .ifEmpty { null }
        } catch (e: Exception) {
            rethrowFatalOrCancellation(e)
            LOG.warn(
                "getKotlinDecisionInfoList: exception during retrieval: ${e.javaClass.simpleName}: ${e.message}",
            )
            null
        }
    }

    data class KotlinProfilingStats(
        val totalTimeInPrediction: Long,
        val totalSLLLookaheadOps: Long,
        val totalLLLookaheadOps: Long,
        val totalATNLookaheadOps: Long,
    )

    fun getKotlinProfilingStats(): KotlinProfilingStats? {
        if (!isKotlin || kotlinParser == null) return null

        return try {
            val getParseInfoMethod = kotlinParser.javaClass.getMethod("getParseInfo")
            val parseInfo = getParseInfoMethod.invoke(kotlinParser) ?: return null

            KotlinProfilingStats(
                totalTimeInPrediction = getKotlinProfileTotal(parseInfo, "getTotalTimeInPrediction"),
                totalSLLLookaheadOps = getKotlinProfileTotal(parseInfo, "getTotalSLLLookaheadOps"),
                totalLLLookaheadOps = getKotlinProfileTotal(parseInfo, "getTotalLLLookaheadOps"),
                totalATNLookaheadOps = getKotlinProfileTotal(parseInfo, "getTotalATNLookaheadOps"),
            )
        } catch (e: Exception) {
            rethrowFatalOrCancellation(e)
            null
        }
    }

    private fun getKotlinProfileTotal(parseInfo: Any, getter: String): Long =
        try {
            parseInfo.javaClass.getMethod(getter).invoke(parseInfo) as Long
        } catch (e: Exception) {
            rethrowFatalOrCancellation(e)
            0L
        }

    val tokenCount: Int
        get() =
            if (isKotlin) {
                getKotlinTokenCount()
            } else {
                (parser?.inputStream as? org.antlr.v4.runtime.CommonTokenStream)?.tokens?.size ?: 0
            }

    private fun getKotlinTokenCount(): Int {
        return try {
            val getTokenStreamMethod = kotlinParser?.javaClass?.getMethod("getTokenStream")
            val tokenStream = getTokenStreamMethod?.invoke(kotlinParser) ?: return 0
            val sizeMethod = tokenStream.javaClass.getMethod("size")
            sizeMethod.invoke(tokenStream) as? Int ?: 0
        } catch (e: Exception) {
            rethrowFatalOrCancellation(e)
            0
        }
    }
}
