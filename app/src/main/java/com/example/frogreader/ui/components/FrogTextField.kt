package com.example.frogreader.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal val FrogTextFieldShape = RoundedCornerShape(16.dp)
internal val FrogDialogTextFieldShape = RoundedCornerShape(14.dp)

/** The outline for compact inline editors with their own text layout. */
internal fun Modifier.frogTextFieldOutline(color: Color, active: Boolean = false): Modifier =
    border(if (active) 2.dp else 1.dp, color, FrogTextFieldShape)
