package io.talevia.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * "Define new" form under the source-node list — single Name +
 * Description/hex-colors pair feeds three "+ character / + style /
 * + palette" buttons. Split out of `SourcePanel.kt` as part of
 * `debt-split-desktop-source-panel` (2026-04-23).
 */
@Composable
internal fun AddSourceControls(
    onAddCharacter: (name: String, description: String) -> Unit,
    onAddStyle: (name: String, description: String) -> Unit,
    onAddPalette: (name: String, hexCsv: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Define new",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            label = { Text("Description / hex colors (CSV for palette)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                enabled = name.isNotBlank() && description.isNotBlank(),
                onClick = {
                    onAddCharacter(name.trim(), description.trim())
                    name = ""; description = ""
                },
            ) { Text("+ character") }
            Button(
                enabled = name.isNotBlank() && description.isNotBlank(),
                onClick = {
                    onAddStyle(name.trim(), description.trim())
                    name = ""; description = ""
                },
            ) { Text("+ style") }
            Button(
                enabled = name.isNotBlank() && description.isNotBlank(),
                onClick = {
                    onAddPalette(name.trim(), description.trim())
                    name = ""; description = ""
                },
            ) { Text("+ palette") }
        }
    }
}
