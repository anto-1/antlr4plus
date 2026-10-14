package com.antlr4plus.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antlr4plus.color.rememberIsLightTheme
import com.antlr4plus.compat.compatClickable
import com.antlr4plus.icons.Icons
import com.intellij.util.ui.ImageUtil
import java.awt.Cursor
import java.awt.image.BufferedImage
import javax.swing.Icon
import org.jetbrains.jewel.ui.component.Text

@Composable
internal fun CopyMarkdownButton(
    onClick: () -> Unit,
    selectedCount: Int,
    modifier: Modifier = Modifier,
) {
    val isLightTheme = rememberIsLightTheme()
    val copyIconBitmap = remember(isLightTheme) { iconToBitmapAtSize(Icons.COPY, 22) }
    val fabBackgroundColor =
        remember(isLightTheme) {
            val baseColor = Color(0xFF3C3F41)
            if (isLightTheme) Color(0xFFE8E8E8) else baseColor
        }
    var isHovered by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Box(
            modifier =
                Modifier.size(34.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (isHovered) fabBackgroundColor.copy(alpha = 0.9f) else fabBackgroundColor.copy(alpha = 0.7f),
                    )
                    .compatClickable { onClick() }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                isHovered =
                                    event.type == PointerEventType.Enter ||
                                        (event.type == PointerEventType.Move && isHovered)
                                if (event.type == PointerEventType.Exit) {
                                    isHovered = false
                                }
                            }
                        }
                    }
                    .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR))),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = copyIconBitmap,
                contentDescription = "Copy to Markdown",
                modifier = Modifier.size(22.dp),
                alpha = if (isHovered) 1f else 0.8f,
            )
        }

        if (selectedCount > 0) {
            Box(
                modifier =
                    Modifier.align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-4).dp)
                        .size(16.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xFF4A9EFF)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (selectedCount > 99) "99+" else selectedCount.toString(),
                    color = Color.White,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun iconToBitmapAtSize(icon: Icon, targetSize: Int): ImageBitmap {
    val scaledIcon = com.intellij.util.IconUtil.scale(icon, null, targetSize.toFloat() / icon.iconWidth)
    val bufferedImage =
        ImageUtil.createImage(
            targetSize,
            targetSize,
            BufferedImage.TYPE_INT_ARGB,
        )
    val graphics = bufferedImage.createGraphics()
    scaledIcon.paintIcon(null, graphics, 0, 0)
    graphics.dispose()
    return bufferedImage.toComposeImageBitmap()
}
