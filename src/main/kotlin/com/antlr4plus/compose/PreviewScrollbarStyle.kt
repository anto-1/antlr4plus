package com.antlr4plus.compose

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal fun previewScrollbarStyle(
    scrollbarColor: Color,
    unhoverAlpha: Float,
    hoverAlpha: Float,
): ScrollbarStyle =
    ScrollbarStyle(
        minimalHeight = 16.dp,
        thickness = 8.dp,
        shape = RoundedCornerShape(4.dp),
        hoverDurationMillis = 300,
        unhoverColor = scrollbarColor.copy(alpha = unhoverAlpha),
        hoverColor = scrollbarColor.copy(alpha = hoverAlpha),
    )
