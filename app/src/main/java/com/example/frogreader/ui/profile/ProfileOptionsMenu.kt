package com.example.frogreader.ui.profile

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Edit
import com.example.frogreader.ui.components.FlatDropdownMenu as DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.example.frogreader.R
import com.example.frogreader.ui.theme.floatingSurface

@Composable
internal fun ProfileOptionsMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onChangeName: () -> Unit,
    onChangeAvatar: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        tonalElevation = 0.dp,
        // Release focus as soon as a rename is selected, while the popup fades out.
        properties = PopupProperties(focusable = expanded),
    ) {
        ProfileOptionsMenuItems(onChangeName, onChangeAvatar)
    }
}

@Composable
internal fun ProfileOptionsMenuItems(onChangeName: () -> Unit, onChangeAvatar: () -> Unit) {
    ProfileOption(Icons.Rounded.Edit, stringResource(R.string.profile_edit_name), onChangeName)
    ProfileOption(Icons.Rounded.AddPhotoAlternate, stringResource(R.string.profile_photo_change), onChangeAvatar)
}

@Composable
private fun ProfileOption(icon: ImageVector, label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}
