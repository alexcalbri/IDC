package com.ideasdeveloper.idc.app.features.company.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
// Renderiza los campos editables de identidad visual de una empresa.
fun CompanyProfileFields(
    name: String,
    logoUrl: String,
    primaryColor: String,
    secondaryColor: String,
    accentColor: String,
    enabled: Boolean,
    onNameChange: (String) -> Unit,
    onLogoUrlChange: (String) -> Unit,
    onPrimaryColorChange: (String) -> Unit,
    onSecondaryColorChange: (String) -> Unit,
    onAccentColorChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text("Nombre") },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = logoUrl,
        onValueChange = onLogoUrlChange,
        label = { Text("Logo URL") },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = primaryColor,
            onValueChange = onPrimaryColorChange,
            label = { Text("Color primario") },
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = secondaryColor,
            onValueChange = onSecondaryColorChange,
            label = { Text("Color secundario") },
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
    }
    OutlinedTextField(
        value = accentColor,
        onValueChange = onAccentColorChange,
        label = { Text("Color acento") },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}
