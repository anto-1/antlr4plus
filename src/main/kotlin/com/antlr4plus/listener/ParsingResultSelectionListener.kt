package com.antlr4plus.listener

import org.antlr.v4.runtime.tree.Tree

/** Reports viewer selection for input-editor highlighting. */
interface ParsingResultSelectionListener {
    fun onParserRuleSelected(tree: Tree?)
}
