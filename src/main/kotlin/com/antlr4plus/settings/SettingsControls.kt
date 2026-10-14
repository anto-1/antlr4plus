package com.antlr4plus.settings

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antlr4plus.color.darken
import com.antlr4plus.color.rememberIsLightTheme
import com.antlr4plus.color.rememberThemeBackgroundColor
import com.antlr4plus.color.rememberThemeIconColor
import com.antlr4plus.color.rememberThemeTextColor
import com.antlr4plus.compat.compatClickable
import com.antlr4plus.compose.AWTLikeTextField
import com.antlr4plus.compose.SimpleCheckbox
import com.antlr4plus.icons.ComposeIconUtils
import com.antlr4plus.icons.rememberIntelliJIconPainter
import org.jetbrains.jewel.ui.component.Text

internal object SettingsTheme {
    val SECTION_SPACING = 8.dp
    val ITEM_SPACING = 8.dp
    val CORNER_RADIUS = 6.dp
    val BORDER_WIDTH = 1.dp
    val HORIZONTAL_PADDING = 12.dp
    val ICON_SIZE = 20.dp
    val FONT_SIZE_LABEL = 12.sp
    val FONT_SIZE_VALUE = 12.sp
    val FONT_SIZE_HEADER = 13.sp
    val FONT_SIZE_TOOLTIP = 11.sp
}

@Composable
internal fun HeaderSection(
    fileName: String,
    filePath: String,
    backgroundColor: Color,
    textSecondary: Color,
    textAccent: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Settings per .g4p: ",
            color = textSecondary,
            fontSize = SettingsTheme.FONT_SIZE_HEADER,
            maxLines = 1,
            softWrap = false,
        )
        Text(
            color = textAccent,
            text = fileName,
            fontSize = SettingsTheme.FONT_SIZE_HEADER,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(end = 8.dp),
            maxLines = 1,
            softWrap = false,
        )
        FadingText(
            text = filePath,
            color = textSecondary,
            fontSize = SettingsTheme.FONT_SIZE_LABEL,
            modifier = Modifier.weight(1f),
            backgroundColor = backgroundColor,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SettingItem(
    label: String,
    description: String,
    roundCornerShape: RoundedCornerShape,
    activate: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val isLightTheme = rememberIsLightTheme()
    val themeTextColor = rememberThemeTextColor()
    val textColor = if (!isLightTheme) themeTextColor.copy(alpha = 0.8f) else themeTextColor
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val labelModifier =
            if (activate != null) {
                Modifier.compatClickable { activate() }
            } else {
                Modifier
            }
        Text(
            text = label,
            color = textColor,
            fontSize = SettingsTheme.FONT_SIZE_LABEL,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = labelModifier,
        )
        Spacer(modifier = Modifier.width(SettingsTheme.ITEM_SPACING))
        Box(modifier = Modifier.weight(1f)) {
            content()
        }
        HelpButton(description, roundCornerShape)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HelpButton(description: String, roundCornerShape: RoundedCornerShape) {
    val isLightTheme = rememberIsLightTheme()
    var isHovered by remember { mutableStateOf(false) }
    val themeTextColor = rememberThemeTextColor()
    val textColor = if (!isLightTheme) themeTextColor.copy(alpha = 0.8f) else themeTextColor

    Box(
        modifier =
            Modifier.width(28.dp)
                .height(28.dp)
                .hoverable(
                    interactionSource =
                        remember { MutableInteractionSource() }
                            .also { source ->
                                LaunchedEffect(source) {
                                    source.interactions.collect { interaction ->
                                        when (interaction) {
                                            is androidx.compose.foundation.interaction.HoverInteraction.Enter -> {
                                                isHovered = true
                                            }

                                            is androidx.compose.foundation.interaction.HoverInteraction.Exit -> {
                                                isHovered = false
                                            }
                                        }
                                    }
                                }
                            },
                ),
    ) {
        val backgroundColor: Color = rememberThemeBackgroundColor()
        val darkerSurfaceColor = remember(backgroundColor) { backgroundColor.darken(.3f) }
        TooltipArea(
            tooltip = {
                Box(
                    modifier =
                        Modifier.background(darkerSurfaceColor, roundCornerShape)
                            .border(1.dp, darkerSurfaceColor.copy(alpha = 0.3f), roundCornerShape),
                ) {
                    Text(
                        text = description,
                        color = textColor,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(6.dp).widthIn(max = 400.dp),
                    )
                }
            },
            delayMillis = if (isHovered) 0 else 300,
        ) {
            Box(
                modifier =
                    Modifier.size(28.dp).align(Alignment.Center).semantics {
                        contentDescription = "Help: $description"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "?",
                    color = textColor.copy(alpha = 0.5f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
internal fun TextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    focusRequester: FocusRequester?,
    onFocusLost: () -> Unit,
    surfaceColor: Color,
    borderColor: Color,
    textSecondary: Color,
    textUserValue: Color,
    roundCornerShape: RoundedCornerShape,
    modifier: Modifier = Modifier.fillMaxWidth(),
    inputDescription: String = "Text input",
) {
    Box(
        modifier =
            modifier
                .height(28.dp)
                .clip(roundCornerShape)
                .background(surfaceColor)
                .border(SettingsTheme.BORDER_WIDTH, borderColor, roundCornerShape)
                .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        var textFieldModifier =
            Modifier.fillMaxWidth()
                .semantics { contentDescription = inputDescription }
                .onFocusChanged { if (!it.isFocused) onFocusLost() }
        if (focusRequester != null) {
            textFieldModifier = textFieldModifier.focusRequester(focusRequester)
        }
        AWTLikeTextField(
            value = value,
            backgroundColor = surfaceColor,
            onValueChange = onValueChange,
            placeholder = placeholder,
            placeholderColor = textSecondary,
            textColor = if (value.isNotEmpty()) textUserValue else null,
            modifier = textFieldModifier,
        )
    }
}

@Composable
internal fun PathField(
    value: String,
    onValueChange: (String) -> Unit,
    onBrowse: () -> Unit,
    placeholder: String,
    focusRequester: FocusRequester?,
    onFocusLost: () -> Unit,
    surfaceColor: Color,
    borderColor: Color,
    textSecondary: Color,
    textUserValue: Color,
    roundCornerShape: RoundedCornerShape,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            focusRequester = focusRequester,
            onFocusLost = onFocusLost,
            surfaceColor = surfaceColor,
            borderColor = borderColor,
            textSecondary = textSecondary,
            textUserValue = textUserValue,
            roundCornerShape = roundCornerShape,
            modifier = Modifier.weight(1f),
            inputDescription = "Path input",
        )
        Spacer(modifier = Modifier.width(4.dp))
        Box(
            modifier =
                Modifier.size(28.dp)
                    .clip(roundCornerShape)
                    .background(surfaceColor)
                    .border(SettingsTheme.BORDER_WIDTH, borderColor, roundCornerShape)
                    .compatClickable { onBrowse() }
                    .semantics { contentDescription = "Browse directory" },
            contentAlignment = Alignment.Center,
        ) {
            val iconColor = rememberThemeIconColor()
            Image(
                painter =
                    rememberIntelliJIconPainter(
                        path = "/icons/com/antlr4plus/editFolder/editFolder_dark.svg",
                        classLoader = ComposeIconUtils::class.java.classLoader,
                    ),
                contentDescription = "Browse",
                colorFilter = ColorFilter.tint(iconColor),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CheckboxField(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    description: String,
    textPrimary: Color,
    roundCornerShape: RoundedCornerShape,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SimpleCheckbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.size(SettingsTheme.ICON_SIZE).semantics { contentDescription = label },
        )
        Spacer(modifier = Modifier.width(SettingsTheme.ITEM_SPACING))
        Text(
            text = label,
            color = textPrimary,
            fontSize = SettingsTheme.FONT_SIZE_LABEL,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.weight(1f).compatClickable { onCheckedChange(!checked) },
        )
        Spacer(modifier = Modifier.width(SettingsTheme.ITEM_SPACING))
        HelpButton(description, roundCornerShape)
    }
}

@Composable
private fun FadingText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    backgroundColor: Color,
    fadeWidth: Dp = 32.dp,
) {
    val textStyle =
        TextStyle(
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
        )
    Box(modifier = modifier) {
        Text(
            text = text,
            style = textStyle,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.align(Alignment.CenterStart).fillMaxWidth(),
        )
        Box(
            modifier =
                Modifier.align(Alignment.CenterEnd)
                    .width(fadeWidth)
                    .fillMaxHeight()
                    .background(
                        Brush.horizontalGradient(
                            0f to backgroundColor.copy(alpha = 0f),
                            1f to backgroundColor,
                        ),
                    ),
        )
    }
}

@Composable
internal fun SectionHeader(
    title: String,
    textPrimary: Color,
) {
    Text(
        text = title,
        color = textPrimary,
        fontSize = SettingsTheme.FONT_SIZE_HEADER,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}
