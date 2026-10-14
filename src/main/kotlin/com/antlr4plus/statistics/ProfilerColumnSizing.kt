package com.antlr4plus.statistics

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antlr4plus.settings.LiveSettings

object ProfilerColumnConfig {
    /** Horizontal padding on each side of a cell. */
    val cellPadding = 10.dp

    val headerRowHeight = 22.dp

    val cellRowHeight = 20.dp

    /** Columns sized from content; remaining columns use their header width. */
    val autoSizeColumns = setOf("Rule", "#", "ms")

    /** Reserves room for values such as 0.0000. */
    const val minMsColumnChars = 6

    /** Number of rows with the highest prediction times sampled for Rule width. */
    const val topRowsForRuleWidth = 30

    val cellFontSize = 12.sp
}

/** Resizable pixel widths and their Dp projection, with estimated header-width minimums. */
class ProfilerColumnWidthState(
    val widthsPx: SnapshotStateList<Float>,
    val widthsDp: List<Dp>,
    val minWidthsPx: List<Float>,
    val charWidthPx: Int,
    val paddingPx: Float,
) {
    fun getMinWidthPx(columnIndex: Int): Float = minWidthsPx.getOrElse(columnIndex) { minWidthsPx.firstOrNull() ?: 0f }
}

/** Estimates widths from character counts, sampling the slowest rules for the Rule column. */
private fun calculateColumnWidths(
    tableModel: ProfilerTableDataModel,
    charWidthPx: Int,
    paddingPx: Float,
): List<Float> {
    val msColIdx =
        (0 until tableModel.columnCount).firstOrNull {
            tableModel.getColumnName(it) == "ms"
        }

    val rowsSortedByMs =
        if (msColIdx != null) {
            (0 until tableModel.rowCount).sortedByDescending { row ->
                (tableModel.getValueAt(row, msColIdx) as? Number)?.toDouble() ?: 0.0
            }
        } else {
            (0 until tableModel.rowCount).toList()
        }

    return (0 until tableModel.columnCount).map { column ->
        val headerText = tableModel.getColumnName(column)
        val headerChars = headerText.length
        val headerWidth = (headerChars * charWidthPx) + (paddingPx * 2)

        if (headerText in ProfilerColumnConfig.autoSizeColumns) {
            val rowsToConsider =
                if (headerText == "Rule") {
                    rowsSortedByMs.take(ProfilerColumnConfig.topRowsForRuleWidth)
                } else {
                    (0 until tableModel.rowCount).toList()
                }

            val maxCellChars =
                rowsToConsider.maxOfOrNull { row ->
                    tableModel.getValueAt(row, column).toString().length
                } ?: 0

            val minCellChars = if (headerText == "ms") ProfilerColumnConfig.minMsColumnChars else 0
            val effectiveCellChars = maxOf(maxCellChars, minCellChars)
            val maxCellWidth = (effectiveCellChars * charWidthPx) + (paddingPx * 2) + charWidthPx

            maxOf(headerWidth, maxCellWidth)
        } else {
            headerWidth
        }
    }
}

/** Parses comma-separated widths and requires the expected number of numeric values. */
private fun parseSavedWidths(savedWidthsString: String?, expectedColumnCount: Int): List<Float>? =
    savedWidthsString?.split(",")?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == expectedColumnCount }

private fun serializeWidths(widths: List<Float>): String = widths.joinToString(",") { it.toString() }

/** Restores and persists separate simple/expert widths, clamped to estimated header minimums. */
@Composable
fun rememberProfilerColumnWidths(
    tableModel: ProfilerTableDataModel,
    isExpertMode: Boolean,
    liveSettings: LiveSettings?,
    onColumnWidthsCalculated: (List<Dp>) -> Unit,
): ProfilerColumnWidthState {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val paddingPx = with(density) { ProfilerColumnConfig.cellPadding.toPx() }

    val charWidthPx =
        textMeasurer
            .measure(
                text = "W",
                style =
                    TextStyle(
                        fontSize = ProfilerColumnConfig.cellFontSize,
                        fontFamily = FontFamily.Monospace,
                    ),
            )
            .size
            .width

    val calculatedColumnWidthsPx =
        remember(tableModel) {
            calculateColumnWidths(tableModel, charWidthPx, paddingPx)
        }

    val savedWidthsString =
        if (isExpertMode) {
            liveSettings?.expertProfilerColumnWidths
        } else {
            liveSettings?.simpleProfilerColumnWidths
        }

    val savedColumnWidthsPx =
        remember(savedWidthsString, tableModel.columnCount) {
            parseSavedWidths(savedWidthsString, tableModel.columnCount)
        }

    val headerMinWidths =
        remember(tableModel) {
            (0 until tableModel.columnCount).map { column ->
                val headerText = tableModel.getColumnName(column)
                (headerText.length * charWidthPx) + (paddingPx * 2)
            }
        }

    val columnWidthsPx =
        remember(tableModel, savedColumnWidthsPx) {
            val baseWidths = savedColumnWidthsPx ?: calculatedColumnWidthsPx
            val enforcedWidths = baseWidths.mapIndexed { index, width ->
                maxOf(width, headerMinWidths[index])
            }
            mutableStateListOf(*enforcedWidths.toTypedArray())
        }

    LaunchedEffect(columnWidthsPx.toList()) {
        val widthsString = serializeWidths(columnWidthsPx)
        liveSettings?.let {
            if (isExpertMode) {
                it.expertProfilerColumnWidths = widthsString
            } else {
                it.simpleProfilerColumnWidths = widthsString
            }
        }
    }

    val columnWidthsDp = columnWidthsPx.map { widthPx ->
        (widthPx / density.density).dp
    }

    LaunchedEffect(columnWidthsDp) {
        onColumnWidthsCalculated(columnWidthsDp)
    }

    return ProfilerColumnWidthState(
        widthsPx = columnWidthsPx,
        widthsDp = columnWidthsDp,
        minWidthsPx = headerMinWidths,
        charWidthPx = charWidthPx,
        paddingPx = paddingPx,
    )
}
