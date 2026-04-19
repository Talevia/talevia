package io.talevia.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.talevia.core.domain.MediaSource
import kotlinx.coroutines.launch

/**
 * Android entry point. Mirrors the desktop and iOS UIs at the level of
 *  - asset import (file path → MediaStorage)
 *  - log activity panel
 * Full chat / timeline visualisation lands when M2's Compose Desktop screens
 * get refactored into shared Compose-Multiplatform composables (M6 territory).
 */
class MainActivity : ComponentActivity() {
    private val container by lazy { AndroidAppContainer(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    AppRoot(container)
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun AppRoot(container: AndroidAppContainer) {
    val scope = rememberCoroutineScope()
    val log = remember { mutableStateListOf<String>("Talevia core attached") }
    var importPath by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Talevia (M5 Android scaffold)", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = importPath,
            onValueChange = { importPath = it },
            label = { Text("Import path (file://...)") },
            singleLine = true,
            modifier = Modifier.fillMaxSize(),
        )
        Button(onClick = {
            val path = importPath
            scope.launch {
                runCatching {
                    container.media.import(
                        source = MediaSource.File(path),
                        probe = { container.engine.probe(it) },
                    )
                }
                    .onSuccess { log += "imported ${it.id.value}" }
                    .onFailure { log += "import failed: ${it.message}" }
            }
        }) { Text("Import") }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(log) { entry ->
                Text(entry, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
