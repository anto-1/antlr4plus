package com.antlr4plus.preview.tree

import java.awt.Font

data class ViewerStyle(
    var fontName: String = "Helvetica",
    var fontStyle: Int = Font.PLAIN,
    var fontSize: Int = 11,
    var mainFont: Font = Font(fontName, fontStyle, fontSize),
)
