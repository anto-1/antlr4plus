package com.antlr4plus.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antlr4plus.color.rememberThemeTextColor

@Composable
fun AWTLikeTextField(
    value: String,
    backgroundColor: Color,
    onValueChange: (String) -> Unit,
    placeholder: String? = null,
    placeholderColor: Color = Color.LightGray,
    textColor: Color? = null, // Null selects the theme text color.
    modifier: Modifier = Modifier,
) {
    val themeTextColor = rememberThemeTextColor()
    val effectiveTextColor = textColor ?: themeTextColor
    val textStyle =
        TextStyle(
            color = effectiveTextColor,
            fontSize = 14.sp,
            fontFamily = null,
        )
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        modifier =
            modifier.background(backgroundColor).padding(horizontal = 5.dp, vertical = 2.dp).onKeyEvent { keyEvent ->
                keyEvent.key == Key.Escape && keyEvent.type == KeyEventType.KeyDown
            },
        textStyle = textStyle,
        cursorBrush = SolidColor(effectiveTextColor),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty() && placeholder != null) {
                    org.jetbrains.jewel.ui.component.Text(
                        text = placeholder,
                        style = textStyle.copy(color = placeholderColor),
                        modifier = Modifier.align(Alignment.CenterStart),
                        maxLines = 1,
                        softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
                    )
                }
                innerTextField()
            }
        },
    )
}
