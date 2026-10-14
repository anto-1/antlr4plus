package com.antlr4plus.settings

import androidx.compose.foundation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.antlr4plus.color.rememberThemeIconColor
import com.antlr4plus.parsing.KotlinRuntimeCompiler
import com.antlr4plus.parsing.RuntimeCompiler
import kotlinx.coroutines.launch
import org.jetbrains.jewel.ui.component.Text

@Composable
internal fun RuntimeCompilerSelector(
    selectedRuntime: String,
    onRuntimeSelected: (String) -> Unit,
    textSecondary: Color,
    textPrimary: Color,
    roundCornerShape: RoundedCornerShape,
    highlightAnimation: Boolean = false,
    onHighlightComplete: () -> Unit = {},
) {
    val javaCompilerInfo = remember { RuntimeCompiler.getCompilerInfo() }
    val kotlinCompilerInfo = remember { KotlinRuntimeCompiler.getCompilerInfo() }

    val iconColor = rememberThemeIconColor()
    val highlightColor = iconColor

    val scanProgress = remember { androidx.compose.animation.core.Animatable(0f) }
    val borderAlpha = remember { androidx.compose.animation.core.Animatable(0f) }

    val coroutineScope = rememberCoroutineScope()

    val cancelAnimation: () -> Unit = {
        coroutineScope.launch {
            borderAlpha.animateTo(
                targetValue = 0f,
                animationSpec = androidx.compose.animation.core.tween(150),
            )
            onHighlightComplete()
        }
    }

    LaunchedEffect(highlightAnimation) {
        if (highlightAnimation) {
            scanProgress.snapTo(-0.2f)
            borderAlpha.snapTo(1f)

            repeat(3) {
                scanProgress.animateTo(
                    targetValue = 1.2f,
                    animationSpec =
                        androidx.compose.animation.core.tween(
                            durationMillis = 500,
                            easing = androidx.compose.animation.core.LinearEasing,
                        ),
                )
                scanProgress.snapTo(-0.2f)
            }

            borderAlpha.animateTo(
                targetValue = 0f,
                animationSpec = androidx.compose.animation.core.tween(300),
            )

            onHighlightComplete()
        }
    }

    val animationActive = borderAlpha.value > 0f

    val verticalPadding = 4.dp

    Box(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            RuntimeCompilerRow(
                label = "Java Compiler",
                versionText = "ECJ ${javaCompilerInfo.source}",
                isSelected = selectedRuntime == "Java",
                isAvailable = javaCompilerInfo.isAvailable,
                helpDescription =
                    """The plugin uses the Eclipse Compiler for Java (ECJ) which is bundled with the plugin.

No external JDK installation is required.

ECJ compiles the generated parser code at runtime to enable the preview functionality.""",
                onSelect = {
                    cancelAnimation()
                    onRuntimeSelected("Java")
                },
                textSecondary = textSecondary,
                textPrimary = textPrimary,
                roundCornerShape = roundCornerShape,
            )

            Spacer(modifier = Modifier.height(SettingsTheme.ITEM_SPACING))

            RuntimeCompilerRow(
                label = "Kotlin Compiler (Experimental, much slower than ECJ)",
                versionText =
                    if (kotlinCompilerInfo.version != null) "v${kotlinCompilerInfo.version}" else "(unavailable)",
                isSelected = selectedRuntime == "Kotlin",
                isAvailable = kotlinCompilerInfo.isAvailable,
                helpDescription =
                    """The plugin uses the embedded Kotlin compiler (kotlin-compiler-embeddable) for Kotlin preview runtime.

When "Kotlin" is selected as the Preview Runtime, ANTLR generates Kotlin code which is compiled using this bundled compiler.

The Kotlin compiler runs in an isolated classloader to avoid conflicts with the IDE's Kotlin version.""",
                onSelect = {
                    cancelAnimation()
                    onRuntimeSelected("Kotlin")
                },
                textSecondary = textSecondary,
                textPrimary = textPrimary,
                roundCornerShape = roundCornerShape,
            )
        }

        if (animationActive) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val verticalPaddingPx = with(density) { verticalPadding.toPx() }
            // Extend over the parent column padding.
            val leftExtension = with(density) { SettingsTheme.HORIZONTAL_PADDING.toPx() }
            // Allow space for the help button and scrollbar.
            val rightExtension = with(density) { 28.dp.toPx() }

            Canvas(modifier = Modifier.matchParentSize()) {
                val expandedWidth = size.width + leftExtension + rightExtension
                val expandedHeight = size.height + verticalPaddingPx * 2

                drawRect(
                    color = highlightColor.copy(alpha = borderAlpha.value * 0.12f),
                    topLeft = androidx.compose.ui.geometry.Offset(-leftExtension, -verticalPaddingPx),
                    size = androidx.compose.ui.geometry.Size(expandedWidth, expandedHeight),
                )

                val strokeWidth = 2.dp.toPx()
                drawLine(
                    color = highlightColor.copy(alpha = borderAlpha.value * 0.8f),
                    start = androidx.compose.ui.geometry.Offset(-leftExtension, -verticalPaddingPx),
                    end =
                        androidx.compose.ui.geometry.Offset(
                            size.width + rightExtension,
                            -verticalPaddingPx,
                        ),
                    strokeWidth = strokeWidth,
                )
                drawLine(
                    color = highlightColor.copy(alpha = borderAlpha.value * 0.8f),
                    start =
                        androidx.compose.ui.geometry.Offset(
                            -leftExtension,
                            size.height + verticalPaddingPx,
                        ),
                    end =
                        androidx.compose.ui.geometry.Offset(
                            size.width + rightExtension,
                            size.height + verticalPaddingPx,
                        ),
                    strokeWidth = strokeWidth,
                )

                val scanWidth = expandedWidth * 0.25f
                val scanX = expandedWidth * scanProgress.value - leftExtension
                drawRect(
                    brush =
                        Brush.horizontalGradient(
                            colors =
                                listOf(
                                    Color.Transparent,
                                    highlightColor.copy(alpha = borderAlpha.value * 0.3f),
                                    highlightColor.copy(alpha = borderAlpha.value * 0.6f),
                                    highlightColor.copy(alpha = borderAlpha.value * 0.3f),
                                    Color.Transparent,
                                ),
                            startX = scanX - scanWidth,
                            endX = scanX + scanWidth,
                        ),
                    topLeft = androidx.compose.ui.geometry.Offset(-leftExtension, -verticalPaddingPx),
                    size = androidx.compose.ui.geometry.Size(expandedWidth, expandedHeight),
                )
            }
        }
    }
}

@Composable
private fun RuntimeCompilerRow(
    label: String,
    versionText: String,
    isSelected: Boolean,
    isAvailable: Boolean,
    helpDescription: String,
    onSelect: () -> Unit,
    textSecondary: Color,
    textPrimary: Color,
    roundCornerShape: RoundedCornerShape,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .height(32.dp)
                .clickable(
                    interactionSource =
                        remember {
                            androidx.compose.foundation.interaction.MutableInteractionSource()
                        },
                    indication = null,
                    onClick = { onSelect() },
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier.size(16.dp)
                    .clickable(
                        interactionSource =
                            remember {
                                androidx.compose.foundation.interaction.MutableInteractionSource()
                            },
                        indication = null,
                        onClick = { onSelect() },
                    )
                    .border(
                        1.5.dp,
                        if (isSelected) Color(0xFF4CAF50) else textSecondary,
                        CircleShape,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            if (isSelected) {
                Box(
                    modifier = Modifier.size(8.dp).background(Color(0xFF4CAF50), CircleShape),
                )
            }
        }
        Spacer(modifier = Modifier.width(SettingsTheme.ITEM_SPACING))

        Text(
            text = label,
            color = if (isSelected) textPrimary else textSecondary,
            fontSize = SettingsTheme.FONT_SIZE_LABEL,
            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.width(SettingsTheme.ITEM_SPACING))

        if (isSelected && isAvailable) {
            Box(
                modifier = Modifier.size(8.dp).background(Color(0xFF4CAF50), CircleShape),
            )
            Spacer(modifier = Modifier.width(6.dp))
        }

        Text(
            text = versionText,
            color = if (isSelected) textPrimary else textSecondary,
            fontSize = SettingsTheme.FONT_SIZE_VALUE,
            maxLines = 1,
        )

        Spacer(modifier = Modifier.weight(1f))

        HelpButton(
            description = helpDescription,
            roundCornerShape = roundCornerShape,
        )
    }
}
