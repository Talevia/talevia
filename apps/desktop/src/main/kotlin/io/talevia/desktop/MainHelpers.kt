package io.talevia.desktop

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.talevia.core.AssetId
import io.talevia.core.ProjectId
import io.talevia.core.platform.BundleMediaPathResolver
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.awt.Desktop
import java.io.File

/**
 * Right-side tab selector shared by [AppRoot]. Adding a tab = one enum
 * entry + one `when` arm in the render switch.
 */
internal enum class RightTab(val label: String) {
    Chat("Chat"),
    Source("Source"),
    Snapshots("Snapshots"),
    Lockfile("Lockfile"),
}

/**
 * Shared small section heading — bold + bottom pad. Pulled out so both
 * the left/centre panels in [AppRoot] and the Chat panel render the same
 * style without each file re-declaring it.
 */
@Composable
internal fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
}

/**
 * Best-effort extraction of an openable filesystem path from a tool's result
 * JSON. Looks first for `outputPath` (ExportTool produces this), then for
 * asset-id fields (generate_image / _video / _music / extract_frame /
 * upscale_asset / synthesize_speech) and resolves them through the project
 * bundle. Returns null when the tool output has no natural file artefact
 * (e.g. `apply_filter`, `add_clip`, `add_source_node`).
 */
internal suspend fun resolveOpenablePath(
    container: AppContainer,
    data: JsonElement,
): String? {
    val obj = (data as? JsonObject) ?: return null
    obj["outputPath"]?.let { (it as? JsonPrimitive)?.contentOrNull()?.let { p -> return p } }
    val assetKeys = listOf("upscaledAssetId", "frameAssetId", "assetId", "newAssetId")
    val assetId = assetKeys.firstNotNullOfOrNull { key ->
        (obj[key] as? JsonPrimitive)?.contentOrNull()?.let { AssetId(it) }
    } ?: return null
    // Prefer the project referenced in the tool output; fall back to scanning
    // the recents registry so tools that don't echo their projectId still
    // resolve (ExtractFrameTool on a cross-project asset, etc.).
    val hintedPid = (obj["projectId"] as? JsonPrimitive)?.contentOrNull()?.let { ProjectId(it) }
    val candidates = buildList<ProjectId> {
        if (hintedPid != null) add(hintedPid)
        addAll(container.projects.listSummaries().map { ProjectId(it.id) })
    }.distinct()
    for (pid in candidates) {
        val project = runCatching { container.projects.get(pid) }.getOrNull() ?: continue
        if (project.assets.none { it.id == assetId }) continue
        val bundleRoot = container.projects.pathOf(pid) ?: continue
        return runCatching {
            BundleMediaPathResolver(project, bundleRoot).resolve(assetId)
        }.getOrNull()
    }
    return null
}

internal fun JsonPrimitive.contentOrNull(): String? =
    if (isString || !content.contains('"')) content.takeIf { it.isNotBlank() } else null

internal fun openExternallyIfExists(path: String) {
    runCatching {
        val file = File(path)
        if (file.exists() && Desktop.isDesktopSupported()) {
            Desktop.getDesktop().open(file)
        }
    }
}

/**
 * Real desktop env, plus defaults for anything the user didn't configure.
 *
 * - `TALEVIA_DB_PATH` defaults to `~/.talevia/talevia.db` so projects /
 *   sessions / source DAGs / snapshots survive app restarts out-of-box.
 *   Set `TALEVIA_DB_PATH=:memory:` to opt back into ephemeral mode.
 * - `TALEVIA_PROJECTS_HOME` defaults to `~/.talevia/projects` — newly-created
 *   project bundles land here when the user doesn't pick a path.
 * - `TALEVIA_RECENTS_PATH` defaults to `~/.talevia/recents.json` — per-machine
 *   catalog of which bundles this user has opened. Required by the file-bundle
 *   ProjectStore.
 *
 * Only fills in defaults the user didn't already set. Anything the user
 * passed via the environment wins.
 */
internal fun desktopEnvWithDefaults(): Map<String, String> {
    val env = System.getenv().toMutableMap()
    val home = System.getProperty("user.home")
    val defaultRoot = File(home, ".talevia")
    if (env["TALEVIA_DB_PATH"].isNullOrBlank()) {
        env["TALEVIA_DB_PATH"] = File(defaultRoot, "talevia.db").absolutePath
    }
    if (env["TALEVIA_PROJECTS_HOME"].isNullOrBlank()) {
        env["TALEVIA_PROJECTS_HOME"] = File(defaultRoot, "projects").absolutePath
    }
    if (env["TALEVIA_RECENTS_PATH"].isNullOrBlank()) {
        env["TALEVIA_RECENTS_PATH"] = File(defaultRoot, "recents.json").absolutePath
    }
    return env
}
