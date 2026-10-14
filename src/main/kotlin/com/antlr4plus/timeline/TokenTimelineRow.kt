package com.antlr4plus.timeline

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.antlr4plus.color.isIntelliJDarkTheme
import java.awt.Cursor
import java.awt.event.InputEvent
import org.antlr.v4.runtime.Token
import org.jetbrains.jewel.ui.component.Text

internal data class TokenTimelineColumnWidths(
    val indexWidth: androidx.compose.ui.unit.Dp,
    val tokenTypeWidth: androidx.compose.ui.unit.Dp,
)

internal fun timelineTextColorWithAlpha(baseColor: Color, alpha: Float): Color =
    if (!isIntelliJDarkTheme()) {
        Color.Black
    } else {
        baseColor.copy(alpha = alpha)
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TokenRow(
    tokenWithMode: TokenWithMode,
    tokenTypeName: String,
    index: Int,
    isSelected: Boolean,
    modeColor: Color,
    tokenTypeColor: Color,
    modeChanged: Boolean,
    textColorCompose: Color,
    highlightBackgroundColor: Color,
    columnWidths: TokenTimelineColumnWidths,
    onTokenClick: (index: Int, isShift: Boolean, isCtrl: Boolean) -> Unit,
) {
    val displayText = tokenWithMode.text.replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r")

    // Key popup state by token so replacing the timeline dismisses it.
    var showPopup by remember(tokenWithMode) { mutableStateOf(false) }
    var popupPosition by remember(tokenWithMode) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    Column {
        if (modeChanged) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.width(2.dp).height(12.dp).background(modeColor),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "→ ${tokenWithMode.modeName}",
                    color = modeColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        Box {
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            when {
                                isSelected -> highlightBackgroundColor
                                tokenWithMode.channel == Token.HIDDEN_CHANNEL -> Color.Black.copy(alpha = 0.25f)
                                else -> Color.Transparent
                            },
                        )
                        .pointerInput(index) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type == PointerEventType.Press) {
                                        val position = event.changes.first().position
                                        val awtEvent = java.awt.EventQueue.getCurrentEvent()
                                        val mouseEvent = awtEvent as? java.awt.event.MouseEvent
                                        val modifiers = (awtEvent as? InputEvent)?.modifiersEx ?: 0
                                        val isShift = (modifiers and InputEvent.SHIFT_DOWN_MASK) != 0
                                        val isCtrl =
                                            (modifiers and InputEvent.CTRL_DOWN_MASK) != 0 ||
                                                (modifiers and InputEvent.META_DOWN_MASK) != 0

                                        if (
                                            mouseEvent != null &&
                                                javax.swing.SwingUtilities.isRightMouseButton(
                                                    mouseEvent,
                                                )
                                        ) {
                                            popupPosition = position
                                            showPopup = true
                                        } else {
                                            onTokenClick(index, isShift, isCtrl)
                                        }
                                    }
                                }
                            }
                        }
                        .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.width(4.dp).height(20.dp).background(modeColor, RoundedCornerShape(2.dp)),
                )

                Spacer(modifier = Modifier.width(6.dp))

                Text(
                    text =
                        "$index: lvl:${tokenWithMode.modeDepth} ${tokenWithMode.line}:${tokenWithMode.charPositionInLine}",
                    color = timelineTextColorWithAlpha(textColorCompose, 0.6f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(columnWidths.indexWidth),
                    maxLines = 1,
                    softWrap = false,
                )

                Spacer(modifier = Modifier.width(10.dp))

                Text(
                    text = tokenTypeName,
                    color = tokenTypeColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(columnWidths.tokenTypeWidth),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.width(4.dp))

                Text(
                    text = "\"$displayText\"",
                    color = timelineTextColorWithAlpha(textColorCompose, 0.8f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            if (showPopup) {
                Popup(
                    onDismissRequest = { showPopup = false },
                    offset = IntOffset(popupPosition.x.toInt() + 8, popupPosition.y.toInt()),
                    properties = PopupProperties(focusable = true),
                ) {
                    val channelName =
                        when (tokenWithMode.channel) {
                            Token.DEFAULT_CHANNEL -> "DEFAULT"
                            Token.HIDDEN_CHANNEL -> "HIDDEN"
                            else -> "Channel ${tokenWithMode.channel}"
                        }
                    Box(
                        modifier =
                            Modifier.background(Color(50, 52, 55), RoundedCornerShape(6.dp))
                                .border(1.dp, Color(70, 72, 75), RoundedCornerShape(6.dp))
                                .padding(8.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = tokenTypeName,
                                color = tokenTypeColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                text = "\"${tokenWithMode.text.replace("\n", "\\n").replace("\t", "\\t").take(80)}\"",
                                color = Color(180, 180, 180),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                text =
                                    "Index: $index  |  Line ${tokenWithMode.line}:${tokenWithMode.charPositionInLine}",
                                color = Color(140, 140, 140),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                text = "Mode: ${tokenWithMode.modeName}  |  Level: ${tokenWithMode.modeDepth}",
                                color = modeColor,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                text = "Channel: $channelName",
                                color = Color(140, 140, 140),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }
}
