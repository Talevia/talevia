package io.talevia.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.talevia.core.bus.BusEvent
import io.talevia.core.permission.PermissionAction
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.permission.PermissionRule
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Listens for [BusEvent.PermissionAsked] from the agent and renders an Allow once /
 * Always / Reject modal. "Always" appends a matching ALLOW rule to the
 * `mutableRules` list so subsequent same-permission requests resolve silently.
 */
@Composable
fun PermissionDialog(
    container: AppContainer,
    onLog: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<BusEvent.PermissionAsked?>(null) }

    LaunchedEffect(container) {
        container.bus.subscribe<BusEvent.PermissionAsked>().collect { ev ->
            pending = ev
        }
    }

    val request = pending ?: return
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Permission required") },
        text = {
            Column {
                Text("The agent wants to perform: ", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Text(request.permission, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
                if (request.patterns.isNotEmpty() && request.patterns != listOf("*")) {
                    Spacer(Modifier.height(8.dp))
                    Text("Targets:", style = MaterialTheme.typography.bodySmall)
                    request.patterns.forEach { p ->
                        Text("  • $p", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = {
                    val r = request
                    pending = null
                    scope.launch {
                        container.permissions.reply(r.requestId, PermissionDecision.Once)
                        onLog("permission · once · ${r.permission}")
                    }
                }) { Text("Allow once") }
                OutlinedButton(onClick = {
                    val r = request
                    pending = null
                    scope.launch {
                        container.permissionRules.add(PermissionRule(r.permission, "*", PermissionAction.ALLOW))
                        container.permissions.reply(r.requestId, PermissionDecision.Always)
                        onLog("permission · always · ${r.permission}")
                    }
                }) { Text("Always") }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                val r = request
                pending = null
                scope.launch {
                    container.permissions.reply(r.requestId, PermissionDecision.Reject)
                    onLog("permission · reject · ${r.permission}")
                }
            }) { Text("Reject") }
        },
    )
}
