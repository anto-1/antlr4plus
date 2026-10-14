package com.antlr4plus.dotG4Support.errorManager

import org.antlr.v4.Tool
import org.antlr.v4.tool.ANTLRMessage
import org.antlr.v4.tool.ErrorManager
import org.antlr.v4.tool.ErrorType

class PluginIgnoreMissingTokensFileErrorManager(tool: Tool?) : ErrorManager(tool) {
    override fun emit(etype: ErrorType?, msg: ANTLRMessage?) {
        if (
            etype == ErrorType.CANNOT_FIND_TOKENS_FILE_REFD_IN_GRAMMAR ||
                etype == ErrorType.CANNOT_FIND_TOKENS_FILE_GIVEN_ON_CMDLINE
        ) {
            return
        }
        super.emit(etype, msg)
    }
}
