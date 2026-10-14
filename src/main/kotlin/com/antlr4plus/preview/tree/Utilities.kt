package com.antlr4plus.preview.tree

import java.awt.Toolkit

object Utilities {
    private const val REFERENCE_DPI = 96.0f
    private val screenDPI: Float
        get() = Toolkit.getDefaultToolkit().screenResolution.toFloat()

    fun dpToPixels(dp: Float): Int = (dp * (screenDPI / REFERENCE_DPI)).toInt()
}
