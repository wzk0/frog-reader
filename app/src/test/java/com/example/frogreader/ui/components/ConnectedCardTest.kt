package com.example.frogreader.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectedCardTest {
    @Test
    fun `single rows retain rounded corners on both ends`() {
        assertEquals(RoundedCornerShape(24.dp), connectedCardShape())
    }

    @Test
    fun `only the outer corners of a group use the larger radius`() {
        assertEquals(
            RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 6.dp, bottomEnd = 6.dp),
            connectedCardShape(last = false),
        )
        assertEquals(RoundedCornerShape(6.dp), connectedCardShape(first = false, last = false))
        assertEquals(
            RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 24.dp, bottomEnd = 24.dp),
            connectedCardShape(first = false),
        )
    }
}
