package com.antlr4plus.color

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import kotlin.math.pow

fun Color.darken(factor: Float): Color {
    require(factor in 0f..1f) { "Factor must be between 0 and 1" }
    val scale = 1 - factor
    return Color(
        red = (this.red * scale).coerceIn(0f, 1f),
        green = (this.green * scale).coerceIn(0f, 1f),
        blue = (this.blue * scale).coerceIn(0f, 1f),
        alpha = this.alpha,
    )
}

fun Color.brighten(factor: Float): Color {
    require(factor in 0f..1f) { "Factor must be between 0 and 1" }
    return Color(
        red = (this.red + (1f - this.red) * factor).coerceIn(0f, 1f),
        green = (this.green + (1f - this.green) * factor).coerceIn(0f, 1f),
        blue = (this.blue + (1f - this.blue) * factor).coerceIn(0f, 1f),
        alpha = this.alpha,
    )
}

/** Relative luminance from RGB components using the legacy sRGB cutoff of 0.03928. */
fun Color.luminance(): Float {
    fun linearize(c: Float): Float =
        if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    return 0.2126f * linearize(red) + 0.7152f * linearize(green) + 0.0722f * linearize(blue)
}

fun Color.isDark(): Boolean = luminance() < 0.5f

/** Uses the IDE theme classification rather than inferring it from one color. */
fun isIntelliJDarkTheme(): Boolean = !JBColor.isBright()

private fun java.awt.Color.toComposeColor(): Color =
    Color(
        red = this.red / 255f,
        green = this.green / 255f,
        blue = this.blue / 255f,
        alpha = this.alpha / 255f,
    )

fun getThemeTextColor(): Color = UIUtil.getLabelForeground().toComposeColor()

fun getThemeBackgroundColor(): Color = JBColor.PanelBackground.toComposeColor()

fun getThemeScrollbarColor(): Color {
    val background = getThemeBackgroundColor()
    return if (background.isDark()) Color.White else Color.Black
}

fun getThemeIconColor(): Color {
    val background = getThemeBackgroundColor()
    return if (background.isDark()) Color(0xFFCED0D6) else Color(0xFF6C707E)
}

// These helpers use panel background as a cache key; callers must still trigger recomposition.
@Composable
fun rememberThemeTextColor(): Color {
    val bgKey = JBColor.PanelBackground.rgb
    return remember(bgKey) { getThemeTextColor() }
}

@Composable
fun rememberThemeBackgroundColor(): Color {
    val bgKey = JBColor.PanelBackground.rgb
    return remember(bgKey) { getThemeBackgroundColor() }
}

@Composable
fun rememberThemeScrollbarColor(): Color {
    val bgKey = JBColor.PanelBackground.rgb
    return remember(bgKey) { getThemeScrollbarColor() }
}

@Composable
fun rememberThemeIconColor(): Color {
    val bgKey = JBColor.PanelBackground.rgb
    return remember(bgKey) { getThemeIconColor() }
}

@Composable
fun rememberIsLightTheme(): Boolean {
    val bgKey = JBColor.PanelBackground.rgb
    return remember(bgKey) { !isIntelliJDarkTheme() }
}
