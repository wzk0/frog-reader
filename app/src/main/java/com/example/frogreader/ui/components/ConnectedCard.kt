package com.example.frogreader.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

internal val ConnectedCardGap = 2.dp

internal val ColorScheme.cardSurface
    get() = surfaceContainer

internal fun connectedCardShape(first: Boolean = true, last: Boolean = true): RoundedCornerShape {
    val top = if (first) 24.dp else 6.dp
    val bottom = if (last) 24.dp else 6.dp
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

internal fun Modifier.connectedCard(scheme: ColorScheme, shape: RoundedCornerShape): Modifier =
    connectedCard(scheme.cardSurface, shape)

internal fun Modifier.connectedCard(color: Color, shape: RoundedCornerShape): Modifier =
    clip(shape).background(color)

internal fun connectedCardShape(index: Int, count: Int): RoundedCornerShape =
    connectedCardShape(first = index == 0, last = index == count - 1)
