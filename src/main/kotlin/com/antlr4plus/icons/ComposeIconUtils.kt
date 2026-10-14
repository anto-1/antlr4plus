package com.antlr4plus.icons

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.intellij.openapi.util.IconLoader
import java.awt.image.BufferedImage
import javax.swing.Icon

class ComposeIconUtils(private val icon: Icon) : Painter() {
    override val intrinsicSize: Size
        get() = Size(icon.iconWidth.toFloat(), icon.iconHeight.toFloat())

    override fun DrawScope.onDraw() {
        val bufferedImage =
            BufferedImage(
                icon.iconWidth,
                icon.iconHeight,
                BufferedImage.TYPE_INT_ARGB,
            )
        val graphics = bufferedImage.createGraphics()
        icon.paintIcon(null, graphics, 0, 0)
        graphics.dispose()
        val imageBitmap = bufferedImage.toComposeImageBitmap()
        drawImage(imageBitmap)
    }
}

@Composable
fun rememberIntelliJIconPainter(path: String, classLoader: ClassLoader): Painter {
    val icon = IconLoader.findIcon(path, classLoader) ?: throw IllegalArgumentException("Icon not found at $path")
    return remember(icon) { ComposeIconUtils(icon) }
}
