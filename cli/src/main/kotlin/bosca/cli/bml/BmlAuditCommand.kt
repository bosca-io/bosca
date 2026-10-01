package bosca.cli.bml

import bosca.cli.BoscaCliCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.time.Duration

/**
 * ``bosca bml audit <base-url>`` — measure what a running site actually makes the
 * browser download. Fetches each route's HTML, discovers the assets it declares, fetches each
 * one, and reports real transfer bytes (compressed, as served), decoded bytes, request counts,
 * and timing. Framework-agnostic on purpose: point it at a BML deployment and at a site on any
 * other stack for a 1:1 comparison.
 */
class BmlAuditCommand : BoscaCliCommand(name = "audit") {
    override fun help(context: Context) =
        "Measure a running site's per-page download weight (any stack, not just BML)."

    private val base by argument("base-url", help = "Site origin, e.g. http://localhost:9092")

    private val routes by option("--route", "-r", help = "Route to audit (repeatable; default /).")
        .multiple(default = listOf("/"))

    private val jsonPath by option("--json", help = "Also write the full report as JSON to this file.").path()

    override fun run() {
        val http = OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(30))
            .build()
        try {
            val report = SiteAudit(http).audit(base, routes)
            renderReport(report).lines().forEach(::echo)
            jsonPath?.let { path ->
                path.parent?.toFile()?.mkdirs()
                path.toFile().writeText(Json.encodeToString(SiteAudit.AuditReport.serializer(), report))
                echo("JSON report: $path")
            }
            if (report.routes.any { routeAudit -> routeAudit.resources.any { it.status == 0 || it.status >= 400 } }) {
                echo("Some requests failed — see rows marked with status 0/4xx/5xx above.", err = true)
                throw ProgramResult(1)
            }
        } finally {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    private fun fmt(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }

    private fun renderReport(report: SiteAudit.AuditReport): String = buildString {
        appendLine("Page-weight audit of ${report.base} (transfer = bytes on the wire, as served)")
        for (route in report.routes) {
            appendLine()
            appendLine("${route.route} — ${route.requestCount} request(s), " +
                "${fmt(route.transferBytes)} transfer (${fmt(route.decodedBytes)} decoded), ${route.totalMillis} ms")
            val byCategory = SiteAudit.Category.entries
                .map { it to route.transferBytes(it) }
                .filter { it.second > 0 }
                .joinToString("  ") { (cat, bytes) -> "${cat.name.lowercase()}=${fmt(bytes)}" }
            appendLine("  by type: $byCategory")
            for (res in route.resources) {
                val status = if (res.status == 0) "FAILED" else res.status.toString()
                appendLine(
                    "  [$status] ${res.category.name.lowercase().padEnd(5)} " +
                        "${fmt(res.transferBytes).padStart(9)} (${fmt(res.decodedBytes)} decoded) " +
                        "${res.millis} ms  ${res.url}",
                )
            }
            if (route.cssUrlRefs > 0) {
                appendLine("  note: ${route.cssUrlRefs} url(...) reference(s) inside CSS (fonts/images) not fetched — browsers download only the subsets they need.")
            }
        }
    }
}
