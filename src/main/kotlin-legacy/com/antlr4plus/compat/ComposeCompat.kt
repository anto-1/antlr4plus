@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.antlr4plus.compat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Modifier

/** Legacy modifiers create a fresh interaction source on each recomposition. */
@Suppress("DEPRECATION")
fun Modifier.compatClickable(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier =
    this.clickable(
        interactionSource = MutableInteractionSource(),
        indication = null,
        enabled = enabled,
        onClickLabel = null,
        role = null,
        onClick = onClick,
    )

@Suppress("DEPRECATION")
fun Modifier.compatCombinedClickable(
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier =
    this.combinedClickable(
        interactionSource = MutableInteractionSource(),
        indication = null,
        enabled = enabled,
        onClickLabel = null,
        role = null,
        onLongClickLabel = null,
        onLongClick = onLongClick,
        onDoubleClick = onDoubleClick,
        onClick = onClick,
    )
