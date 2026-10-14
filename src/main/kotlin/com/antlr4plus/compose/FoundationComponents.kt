package com.antlr4plus.compose

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.antlr4plus.compat.compatClickable

// Foundation-based controls avoid dependencies on version-specific Jewel widget APIs.
@Composable
fun SimpleCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val checkedColor = Color(0xFF4A90D9)
    val uncheckedColor = Color.Gray.copy(alpha = 0.5f)
    val checkmarkColor = Color.White
    val boxColor = if (checked) checkedColor else uncheckedColor

    Box(
        modifier =
            modifier
                .size(16.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(boxColor)
                .border(1.dp, boxColor.copy(alpha = 0.8f), RoundedCornerShape(3.dp))
                .then(
                    if (onCheckedChange != null && enabled) {
                        Modifier.compatClickable { onCheckedChange(!checked) }
                    } else {
                        Modifier
                    },
                ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Canvas(modifier = Modifier.size(10.dp)) {
                val strokeWidth = 2.dp.toPx()
                drawLine(
                    color = checkmarkColor,
                    start = Offset(size.width * 0.15f, size.height * 0.5f),
                    end = Offset(size.width * 0.4f, size.height * 0.75f),
                    strokeWidth = strokeWidth,
                )
                drawLine(
                    color = checkmarkColor,
                    start = Offset(size.width * 0.4f, size.height * 0.75f),
                    end = Offset(size.width * 0.85f, size.height * 0.25f),
                    strokeWidth = strokeWidth,
                )
            }
        }
    }
}

@Composable
fun SimpleCircularProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF4A90D9),
    strokeWidth: Dp = 2.dp,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "progress")
    val rotation by
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(1000, easing = LinearEasing),
                ),
            label = "rotation",
        )

    Canvas(modifier = modifier.size(24.dp)) {
        val strokeWidthPx = strokeWidth.toPx()
        val arcSize = size.minDimension - strokeWidthPx

        drawArc(
            color = color.copy(alpha = 0.2f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(strokeWidthPx / 2, strokeWidthPx / 2),
            size = androidx.compose.ui.geometry.Size(arcSize, arcSize),
            style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round),
        )

        drawArc(
            color = color,
            startAngle = rotation - 90f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(strokeWidthPx / 2, strokeWidthPx / 2),
            size = androidx.compose.ui.geometry.Size(arcSize, arcSize),
            style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round),
        )
    }
}
