package com.antlr4plus.statistics

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antlr4plus.color.Color.AMBIGUITY_COLOR
import com.antlr4plus.color.Color.DEEPESTLOOK_COLOR
import com.antlr4plus.color.Color.FULLCTX_COLOR
import com.antlr4plus.color.Color.PREDEVAL_COLOR
import com.antlr4plus.color.rememberIsLightTheme
import com.antlr4plus.color.rememberThemeTextColor
import com.antlr4plus.compat.compatClickable
import com.antlr4plus.statistics.ComposeProfilerPanel.FilterType
import com.intellij.icons.AllIcons
import com.intellij.util.ui.ImageUtil
import java.awt.image.BufferedImage
import javax.swing.Icon
import org.jetbrains.jewel.ui.component.Text

internal fun profilerDecisionColor(info: UnifiedDecisionColorInfo): Color? =
    when {
        info.hasAmbiguities -> Color(AMBIGUITY_COLOR.red, AMBIGUITY_COLOR.green, AMBIGUITY_COLOR.blue)
        info.hasContextSensitivities -> Color(FULLCTX_COLOR.red, FULLCTX_COLOR.green, FULLCTX_COLOR.blue)
        info.hasPredicateEvals -> Color(PREDEVAL_COLOR.red, PREDEVAL_COLOR.green, PREDEVAL_COLOR.blue)
        info.maxLookahead > 1 -> Color(DEEPESTLOOK_COLOR.red, DEEPESTLOOK_COLOR.green, DEEPESTLOOK_COLOR.blue)
        else -> null
    }

internal fun iconToBitmap(icon: Icon): ImageBitmap {
    val bufferedImage =
        ImageUtil.createImage(
            icon.iconWidth,
            icon.iconHeight,
            BufferedImage.TYPE_INT_ARGB,
        )
    val graphics = bufferedImage.createGraphics()
    icon.paintIcon(null, graphics, 0, 0)
    graphics.dispose()
    return bufferedImage.toComposeImageBitmap()
}

@Composable
internal fun StatRow(
    label: String,
    value: String,
    fontSize: Int = 12,
    trailingContent: @Composable RowScope.() -> Unit = {},
) {
    val textColorCompose = rememberThemeTextColor()
    val dimmedLabelColor =
        textColorCompose.copy(
            red = textColorCompose.red * 0.75f,
            green = textColorCompose.green * 0.75f,
            blue = textColorCompose.blue * 0.75f,
        )
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            text = label,
            color = dimmedLabelColor,
            fontSize = fontSize.sp,
            modifier = Modifier.padding(end = 8.dp),
            softWrap = false,
            overflow = TextOverflow.Clip,
            maxLines = 1,
        )
        Text(
            text = value,
            color = textColorCompose,
            fontSize = fontSize.sp,
            fontWeight = FontWeight.Bold,
            softWrap = false,
            overflow = TextOverflow.Clip,
            maxLines = 1,
        )
        trailingContent()
    }
}

@Composable
internal fun BenchmarkIterationsRow(
    value: String,
    refreshIcon: ImageBitmap,
    settingsIcon: ImageBitmap,
    enabled: Boolean,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    failedIndicatorAlpha: Float = 0f,
) =
    StatRow("Benchmark iterations:", value) {
        Spacer(modifier = Modifier.width(6.dp))
        Box(
            modifier = Modifier.size(14.dp).compatClickable(enabled = enabled) { onRefresh() },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = refreshIcon,
                contentDescription = "Re-run benchmark",
                modifier = Modifier.size(12.dp),
                alpha = if (enabled) 0.7f else 0.3f,
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Box(
            modifier = Modifier.size(14.dp).compatClickable(enabled = enabled) { onSettings() },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = settingsIcon,
                contentDescription = "Benchmark settings",
                modifier = Modifier.size(12.dp),
                alpha = if (enabled) 0.7f else 0.3f,
            )
        }
        if (failedIndicatorAlpha > 0f) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "✗",
                color = Color(0xFFCC6666).copy(alpha = failedIndicatorAlpha),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LegendItem(
    text: String,
    color: Color,
    filterType: FilterType,
    currentFilter: FilterType,
    onFilterToggle: (FilterType) -> Unit,
    enabled: Boolean = true,
    tooltip: String = "",
) {
    val textColorCompose = rememberThemeTextColor()
    val isLightTheme = rememberIsLightTheme()
    val tooltipBackgroundColor =
        if (isLightTheme) {
            Color(0xFFE8EEF5)
        } else {
            Color(50, 52, 55)
        }
    val tooltipSecondaryColor =
        if (isLightTheme) {
            Color(0xFF505050)
        } else {
            Color(200, 200, 200)
        }

    val isActive = currentFilter == filterType
    val textColor = if (isActive) color else textColorCompose
    val filterIconBitmap = remember { iconToBitmap(AllIcons.General.Filter) }

    TooltipArea(
        tooltip = {
            if (tooltip.isNotEmpty()) {
                Box(
                    modifier =
                        Modifier.widthIn(max = 400.dp)
                            .background(tooltipBackgroundColor, RoundedCornerShape(6.dp))
                            .padding(12.dp),
                ) {
                    Column {
                        Text(
                            text = text,
                            color = color,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.SansSerif,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        Text(
                            text = tooltip,
                            color = tooltipSecondaryColor,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.SansSerif,
                            lineHeight = 18.sp,
                        )
                    }
                }
            }
        },
        delayMillis = 300,
    ) {
        Box(
            modifier =
                Modifier.compatClickable(enabled = enabled) {
                        val newFilter = if (isActive) FilterType.None else filterType
                        onFilterToggle(newFilter)
                    }
                    .padding(vertical = 2.dp, horizontal = 4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.size(12.dp).background(color))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = text,
                    color = textColor,
                    fontSize = 12.sp,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Image(
                    bitmap = filterIconBitmap,
                    contentDescription = "Filter",
                    modifier = Modifier.size(16.dp),
                    colorFilter = if (isActive) ColorFilter.tint(color) else null,
                )
            }
        }
    }
}
