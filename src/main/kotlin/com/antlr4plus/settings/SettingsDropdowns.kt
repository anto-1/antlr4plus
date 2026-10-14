package com.antlr4plus.settings

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.antlr4plus.compat.compatClickable
import com.antlr4plus.parsing.CaseChangingStrategy
import org.jetbrains.jewel.ui.component.Text

@Composable
internal fun DropdownField(
    value: String,
    items: List<String>,
    onItemSelected: (String) -> Unit,
    expandedState: MutableState<Boolean>,
    surfaceColor: Color,
    borderColor: Color,
    textPrimary: Color,
    textSecondary: Color,
    roundCornerShape: RoundedCornerShape,
) {
    val expanded = expandedState.value
    val isDefault = value.endsWith("(default)")

    val displayMap = CaseChangingStrategy.entries.associate { it.name to it.toString() }

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .height(28.dp)
                    .background(surfaceColor, roundCornerShape)
                    .border(SettingsTheme.BORDER_WIDTH, borderColor, roundCornerShape)
                    .compatClickable { expandedState.value = true }
                    .padding(horizontal = 8.dp)
                    .semantics { contentDescription = "Select option" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value,
                color = if (isDefault) textSecondary else textPrimary,
                fontSize = SettingsTheme.FONT_SIZE_VALUE,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
            Text(
                text = if (expanded) "▲" else "▼",
                color = textPrimary.copy(alpha = 0.5f),
                fontSize = 10.sp,
                modifier = Modifier.size(SettingsTheme.ICON_SIZE),
                textAlign = TextAlign.Center,
            )
        }

        if (expanded) {
            Popup(
                onDismissRequest = { expandedState.value = false },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    modifier =
                        Modifier.background(surfaceColor, roundCornerShape)
                            .border(1.dp, borderColor, roundCornerShape)
                            .widthIn(min = 200.dp)
                            .padding(vertical = 4.dp),
                ) {
                    items.forEach { itemName ->
                        Box(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .compatClickable {
                                        onItemSelected(itemName)
                                        expandedState.value = false
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(
                                displayMap[itemName] ?: itemName,
                                color = textPrimary,
                                fontSize = SettingsTheme.FONT_SIZE_VALUE,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SearchableDropdownField(
    value: String,
    items: List<String>,
    onItemSelected: (String) -> Unit,
    expandedState: MutableState<Boolean>,
    searchFocusRequester: FocusRequester?,
    surfaceColor: Color,
    borderColor: Color,
    textPrimary: Color,
    textSecondary: Color,
    roundCornerShape: RoundedCornerShape,
) {
    val expanded = expandedState.value
    var searchText by remember { mutableStateOf("") }

    fun isFuzzyMatch(query: String, target: String): Boolean {
        val q = query.lowercase()
        val t = target.lowercase()
        var i = 0
        for (c in t) {
            if (i < q.length && c == q[i]) i++
        }
        return i == q.length
    }

    val filteredItems by
        remember(searchText) {
            derivedStateOf {
                items.filter { isFuzzyMatch(searchText, it) }.take(50)
            }
        }

    val isDefault = value.endsWith("(default)")

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .height(28.dp)
                    .background(surfaceColor, roundCornerShape)
                    .border(SettingsTheme.BORDER_WIDTH, borderColor, roundCornerShape)
                    .compatClickable { expandedState.value = true }
                    .padding(horizontal = 8.dp)
                    .semantics { contentDescription = "Select encoding" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value,
                color = if (isDefault) textSecondary else textPrimary,
                fontSize = SettingsTheme.FONT_SIZE_VALUE,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
            Text(
                text = if (expanded) "▲" else "▼",
                color = textPrimary.copy(alpha = 0.5f),
                fontSize = 10.sp,
                modifier = Modifier.size(SettingsTheme.ICON_SIZE),
                textAlign = TextAlign.Center,
            )
        }

        if (expanded) {
            Popup(
                onDismissRequest = {
                    expandedState.value = false
                    searchText = ""
                },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    modifier =
                        Modifier.background(surfaceColor, roundCornerShape)
                            .border(1.dp, borderColor, roundCornerShape)
                            .width(IntrinsicSize.Max),
                ) {
                    var searchModifier =
                        Modifier.fillMaxWidth().padding(8.dp).semantics {
                            contentDescription = "Search encodings"
                        }
                    if (searchFocusRequester != null) {
                        searchModifier = searchModifier.focusRequester(searchFocusRequester)
                    }

                    Box(
                        modifier =
                            Modifier.fillMaxWidth()
                                .padding(8.dp)
                                .background(surfaceColor.copy(alpha = 0.5f), roundCornerShape)
                                .border(1.dp, borderColor, roundCornerShape)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        if (searchText.isEmpty()) {
                            Text(
                                "Search...",
                                color = textSecondary,
                                fontSize = SettingsTheme.FONT_SIZE_VALUE,
                            )
                        }
                        BasicTextField(
                            value = searchText,
                            onValueChange = { searchText = it },
                            textStyle =
                                TextStyle(
                                    color = textPrimary,
                                    fontSize = SettingsTheme.FONT_SIZE_VALUE,
                                ),
                            singleLine = true,
                            modifier = searchModifier,
                        )
                    }

                    if (searchFocusRequester != null) {
                        LaunchedEffect(Unit) {
                            searchFocusRequester.requestFocus()
                        }
                    }

                    Column(
                        modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                    ) {
                        if (filteredItems.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    "No matches found",
                                    color = textSecondary,
                                    fontSize = SettingsTheme.FONT_SIZE_VALUE,
                                )
                            }
                        } else {
                            filteredItems.forEach { item ->
                                Box(
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .compatClickable {
                                                onItemSelected(item)
                                                expandedState.value = false
                                                searchText = ""
                                            }
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                ) {
                                    Text(
                                        item,
                                        color = textPrimary,
                                        fontSize = SettingsTheme.FONT_SIZE_VALUE,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
