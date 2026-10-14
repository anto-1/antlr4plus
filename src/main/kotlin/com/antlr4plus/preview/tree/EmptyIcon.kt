package com.antlr4plus.preview.tree

import java.awt.Component
import java.awt.Graphics
import javax.swing.Icon

class EmptyIcon : Icon {
    override fun getIconWidth(): Int = 0

    override fun getIconHeight(): Int = 0

    override fun paintIcon(c: Component?, g: Graphics?, x: Int, y: Int) {}
}
