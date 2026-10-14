package com.antlr4plus.color

import java.awt.Color as AwtColor
import javax.swing.AbstractButton
import javax.swing.UIManager

internal fun AwtColor.brighten(percent: Double): AwtColor {
    val factor = 1.0 + percent
    return AwtColor(
        (red * factor).toInt().coerceIn(0, 255),
        (green * factor).toInt().coerceIn(0, 255),
        (blue * factor).toInt().coerceIn(0, 255),
        alpha,
    )
}

internal fun AbstractButton.buttonColor(brightnessPercent: Double): AwtColor =
    (UIManager.getColor("Panel.background") ?: background ?: AwtColor.DARK_GRAY).brighten(
        brightnessPercent,
    )
