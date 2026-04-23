package io.talevia.server

import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import io.talevia.core.AssetId
import io.talevia.core.ProjectId
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaSource
import kotlinx.io.readByteArray
import java.io.File
import java.util.UUID

/**
 * Media upload route — `POST /media?projectId=<id>`.
 *
 * Split out of `ServerModule.kt` as part of `debt-split-server-module-kt`
 * (2026-04-23). Invoked from `ServerModule.serverModule(...)` inside
 * `routing {}`.
 */
internal fun Routing.mediaRoutes(container: ServerContainer) {
    /**
     * POST /media?projectId=<id> — accept a `multipart/form-data` body with a
     * single file part, probe its metadata, write the bytes into the target
     * project bundle's `media/<assetId>.<ext>`, append the
     * [MediaAsset] to `Project.assets`, and return
     * `{ "assetId": "<uuid>" }`. 400 when `projectId` is missing or the
     * project is not registered.
     */
    post("/media") {
        val projectIdParam = call.parameters["projectId"]?.ifBlank { null }
            ?: call.request.queryParameters["projectId"]?.ifBlank { null }
        if (projectIdParam == null) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "missing projectId query parameter"),
            )
            return@post
        }
        val pid = ProjectId(projectIdParam)
        requireReasonableId(pid.value, "projectId")
        if (container.projects.get(pid) == null) {
            call.respond(
                HttpStatusCode.NotFound,
                mapOf("error" to "project ${pid.value} not found"),
            )
            return@post
        }

        val multipart = call.receiveMultipart()
        var assetId: String? = null
        multipart.forEachPart { part ->
            if (part is PartData.FileItem && assetId == null) {
                val fileName = part.originalFileName?.ifBlank { null } ?: "upload"
                val bytes = part.provider().readRemaining().readByteArray()
                val tmp = File.createTempFile("talevia-upload-", "-$fileName")
                val newAssetId = AssetId(UUID.randomUUID().toString())
                val ext = fileName.substringAfterLast('.', missingDelimiterValue = "bin")
                    .ifBlank { "bin" }
                try {
                    tmp.writeBytes(bytes)
                    val metadata = container.engine.probe(MediaSource.File(tmp.absolutePath))
                    val bundleSource = container.bundleBlobWriter.writeBlob(
                        projectId = pid,
                        assetId = newAssetId,
                        bytes = bytes,
                        format = ext,
                    )
                    val asset = MediaAsset(
                        id = newAssetId,
                        source = bundleSource,
                        metadata = metadata,
                    )
                    container.projects.mutate(pid) { p ->
                        p.copy(assets = p.assets + asset)
                    }
                    assetId = newAssetId.value
                } finally {
                    tmp.delete()
                }
            }
            part.dispose()
        }
        if (assetId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "no file part found in multipart body"))
        } else {
            call.respond(HttpStatusCode.Created, mapOf("assetId" to assetId!!))
        }
    }
}
