package io.talevia.core.provider

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Minimal SSE parser. Yields one [SseEvent] per `event:`/`data:` block separated
 * by a blank line. Sufficient for OpenAI / Anthropic streaming endpoints which
 * use only the `event:` and `data:` fields.
 */
data class SseEvent(val event: String?, val data: String)

fun HttpResponse.sseEvents(): Flow<SseEvent> = flow {
    val channel = bodyAsChannel()
    var event: String? = null
    val data = StringBuilder()
    while (true) {
        val line = channel.readUTF8Line() ?: break
        when {
            line.isEmpty() -> {
                if (data.isNotEmpty() || event != null) {
                    emit(SseEvent(event, data.toString()))
                }
                event = null
                data.clear()
            }
            line.startsWith("event:") -> event = line.removePrefix("event:").trim()
            line.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.removePrefix("data:").trimStart())
            }
            line.startsWith(":") -> { /* SSE comment line, ignore */ }
            else -> { /* unknown, ignore */ }
        }
    }
    if (data.isNotEmpty() || event != null) {
        emit(SseEvent(event, data.toString()))
    }
}
