package io.talevia.server

import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

/**
 * Prometheus-style `/metrics` scrape endpoint.
 *
 * Split out of `ServerModule.kt` as part of `debt-split-server-module-kt`
 * (2026-04-23). Invoked from `ServerModule.serverModule(...)` inside
 * `routing {}`.
 */
internal fun Routing.metricsRoute(container: ServerContainer) {
    /**
     * GET /metrics — prometheus-style text dump of counters and latency
     * histograms.
     *
     * Counters:   `talevia_<name_with_underscores> <value>`
     * Histograms: `talevia_<name>_p50/p95/p99 <ms>` (agent.run.ms, tool.*.ms)
     */
    get("/metrics") {
        val counters = container.metrics.snapshot().toSortedMap()
        val histograms = container.metrics.histogramSnapshot().toSortedMap()
        val body = buildString {
            counters.forEach { (k, v) ->
                append("talevia_"); append(k.replace('.', '_')); append(' '); append(v); append('\n')
            }
            histograms.forEach { (k, stats) ->
                val base = "talevia_${k.replace('.', '_')}"
                append("${base}_count ${stats.count}\n")
                append("${base}_p50 ${stats.p50}\n")
                append("${base}_p95 ${stats.p95}\n")
                append("${base}_p99 ${stats.p99}\n")
            }
        }
        call.respondText(body, ContentType.Text.Plain)
    }
}
