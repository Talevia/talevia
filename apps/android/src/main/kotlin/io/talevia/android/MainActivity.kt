package io.talevia.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.agent.RunInput
import io.talevia.core.bus.BusEvent
import io.talevia.core.domain.Project
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class MainActivity : ComponentActivity() {
    private val container by lazy { AndroidAppContainer(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(container)
                }
            }
        }
    }
}

@OptIn(ExperimentalUuidApi::class)
@Composable
private fun AppRoot(container: AndroidAppContainer) {
    val scope = rememberCoroutineScope()

    // Single "main" project, created on first launch.
    val projectId = remember { mutableStateOf<ProjectId?>(null) }
    LaunchedEffect(Unit) {
        val existing = runCatching { container.projects.listSummaries() }.getOrElse { emptyList() }
        val pid = if (existing.isNotEmpty()) {
            ProjectId(existing.first().id)
        } else {
            val p = Project(id = ProjectId(Uuid.random().toString()), timeline = Timeline())
            container.projects.upsert("My Project", p)
            p.id
        }
        projectId.value = pid
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Chat", "Timeline", "Source")

    val pid = projectId.value
    Column(modifier = Modifier.fillMaxSize()) {
        if (pid == null) {
            Text("Loading…", modifier = Modifier.padding(16.dp))
        } else {
            when (selectedTab) {
                0 -> ChatPanel(container = container, projectId = pid, modifier = Modifier.weight(1f))
                1 -> TimelinePanel(container = container, projectId = pid, modifier = Modifier.weight(1f))
                2 -> SourcePanel(container = container, projectId = pid, modifier = Modifier.weight(1f))
            }
        }
        NavigationBar {
            tabs.forEachIndexed { idx, label ->
                NavigationBarItem(
                    selected = selectedTab == idx,
                    onClick = { selectedTab = idx },
                    label = { Text(label) },
                    icon = {},
                )
            }
        }
    }
}

@OptIn(ExperimentalUuidApi::class)
@Composable
private fun ChatPanel(container: AndroidAppContainer, projectId: ProjectId, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val hasProvider = remember { container.providers.default != null }

    val sessionId = remember { mutableStateOf(SessionId(Uuid.random().toString())) }
    val messages = remember { mutableStateListOf<String>() }
    var prompt by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    // Bootstrap session
    LaunchedEffect(projectId) {
        val existing = runCatching { container.sessions.listSessions(projectId) }
            .getOrElse { emptyList() }
            .filter { !it.archived }
            .maxByOrNull { it.updatedAt }
        if (existing != null) {
            sessionId.value = existing.id
            runCatching {
                container.sessions.listSessionParts(existing.id, includeCompacted = false)
                    .forEach { p ->
                        when (p) {
                            is Part.Text -> messages += "ai: ${p.text.take(200)}"
                            is Part.Tool -> messages += "tool[${p.toolId}]: ${p.state::class.simpleName}"
                            else -> {}
                        }
                    }
            }
        } else {
            val sid = sessionId.value
            container.sessions.createSession(
                Session(
                    id = sid,
                    projectId = projectId,
                    title = "Chat",
                    createdAt = Clock.System.now(),
                    updatedAt = Clock.System.now(),
                ),
            )
        }
    }

    // Live updates from bus
    LaunchedEffect(Unit) {
        container.bus.subscribe<BusEvent.PartUpdated>().collect { ev ->
            if (ev.sessionId != sessionId.value) return@collect
            when (val p = ev.part) {
                is Part.Text -> { messages += "ai: ${p.text.take(200)}" }
                is Part.Tool -> { messages += "tool[${p.toolId}]: ${p.state::class.simpleName}" }
                else -> {}
            }
        }
    }

    Column(modifier = modifier.padding(12.dp)) {
        Text("Chat", style = MaterialTheme.typography.titleMedium)
        if (!hasProvider) {
            Spacer(Modifier.height(8.dp))
            Text(
                "No provider API key configured. Set ANTHROPIC_API_KEY or OPENAI_API_KEY system property.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(4.dp))
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(messages) { line ->
                Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("Prompt") },
            enabled = hasProvider && !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = {
                val text = prompt.trim()
                if (text.isEmpty() || busy) return@Button
                prompt = ""
                messages += "you: $text"
                busy = true
                val providerId = container.providers.default?.id ?: return@Button
                val agent = container.agentFor(providerId) ?: return@Button
                scope.launch {
                    runCatching {
                        agent.run(
                            RunInput(
                                sessionId = sessionId.value,
                                text = text,
                                model = ModelRef(providerId, "claude-opus-4-7"),
                                permissionRules = container.permissionRules,
                            ),
                        )
                    }
                    busy = false
                }
            },
            enabled = hasProvider && !busy && prompt.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "Thinking…" else "Send") }
    }
}

@Composable
private fun TimelinePanel(container: AndroidAppContainer, projectId: ProjectId, modifier: Modifier = Modifier) {
    val tracks = remember { mutableStateListOf<Track>() }

    LaunchedEffect(projectId) {
        suspend fun reload() {
            val proj = runCatching { container.projects.get(projectId) }.getOrNull()
            tracks.clear()
            proj?.timeline?.tracks?.let { tracks.addAll(it) }
        }
        reload()
        container.bus.events.filterIsInstance<BusEvent.PartUpdated>().collect { reload() }
    }

    Column(modifier = modifier.padding(12.dp)) {
        Text("Timeline", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        if (tracks.isEmpty()) {
            Text("No tracks yet. Ask the agent to add clips.", style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                tracks.forEach { track ->
                    item {
                        Text(
                            when (track) {
                                is Track.Video -> "▶ Video track (${track.clips.size} clips)"
                                is Track.Audio -> "♪ Audio track (${track.clips.size} clips)"
                                is Track.Subtitle -> "𝐓 Subtitle track (${track.clips.size} clips)"
                                is Track.Effect -> "✦ Effect track (${track.clips.size} clips)"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                        track.clips.forEach { clip ->
                            Text(
                                "  clip ${clip.id.value.take(8)} · ${clip.timeRange.start}–${clip.timeRange.end}",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 12.dp, bottom = 2.dp),
                            )
                        }
                        Divider()
                    }
                }
            }
        }
    }
}

@Composable
private fun SourcePanel(container: AndroidAppContainer, projectId: ProjectId, modifier: Modifier = Modifier) {
    val nodes = remember { mutableStateListOf<SourceNode>() }

    LaunchedEffect(projectId) {
        suspend fun reload() {
            val proj = runCatching { container.projects.get(projectId) }.getOrNull()
            nodes.clear()
            proj?.source?.nodes?.let { nodes.addAll(it) }
        }
        reload()
        container.bus.events.filterIsInstance<BusEvent.PartUpdated>().collect { reload() }
    }

    Column(modifier = modifier.padding(12.dp)) {
        Text("Source", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        if (nodes.isEmpty()) {
            Text("No source nodes yet. Ask the agent to define characters or style.", style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(nodes) { node ->
                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(
                            "[${node.kind}]  ${node.id.value.take(8)}",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
