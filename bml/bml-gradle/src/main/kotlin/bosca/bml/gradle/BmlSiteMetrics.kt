package bosca.bml.gradle

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * Pure computation behind the `bmlMetrics` task: parses the compiler-written metrics
 * manifest (`manifest.tsv`, format `bml-metrics` v1), joins it with the on-disk asset files
 * (island JS bundles, per-component CSS chunks, the site's global tier), and renders the result
 * as a console table plus a JSON report. Kept free of Gradle types so it is unit-testable.
 */
internal object BmlSiteMetrics {

    /** One `P` line of the manifest: a page and its compile-resolved asset closure. */
    data class PageEntry(
        val objectName: String,
        val route: String,
        val pageJs: String?,
        val cssTags: List<String>,
        val componentJs: List<String>,
    )

    /** A measured asset: size on disk and its gzip (default level) transfer size. */
    data class AssetSize(val name: String, val rawBytes: Long, val gzipBytes: Long)

    data class PageReport(
        val objectName: String,
        val route: String,
        val js: List<AssetSize>,
        val css: List<AssetSize>,
    ) {
        val rawBytes: Long get() = js.sumOf { it.rawBytes } + css.sumOf { it.rawBytes }
        val gzipBytes: Long get() = js.sumOf { it.gzipBytes } + css.sumOf { it.gzipBytes }
    }

    data class SiteReport(
        val global: List<AssetSize>,
        val pages: List<PageReport>,
        /** Manifest-listed assets whose files were not found (e.g. bundling skipped). */
        val missing: List<String>,
    )

    /** Parses the manifest text; throws on an unknown format/version so drift fails loudly. */
    fun parseManifest(text: String): List<PageEntry> {
        val lines = text.lines().filter { it.isNotBlank() }
        val header = lines.firstOrNull()?.split('\t') ?: emptyList()
        require(header.size >= 2 && header[0] == "bml-metrics" && header[1] == "1") {
            "Unrecognized bml metrics manifest header: '${lines.firstOrNull()}' (expected 'bml-metrics\\t1')"
        }
        fun csv(field: String): List<String> = if (field == "-") emptyList() else field.split(',')
        return lines.drop(1).filter { it.startsWith("P\t") }.map { line ->
            val f = line.split('\t')
            require(f.size == 6) { "Malformed manifest page line: '$line'" }
            PageEntry(
                objectName = f[1],
                route = f[2],
                pageJs = f[3].takeIf { it != "-" },
                cssTags = csv(f[4]),
                componentJs = csv(f[5]),
            )
        }
    }

    fun gzipSize(bytes: ByteArray): Long {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.size().toLong()
    }

    private fun measure(name: String, file: File?, missing: MutableList<String>): AssetSize? {
        if (file == null || !file.isFile) {
            missing += name
            return null
        }
        val bytes = file.readBytes()
        return AssetSize(name, bytes.size.toLong(), gzipSize(bytes))
    }

    /**
     * Builds the report. [jsDir] holds the bundled island modules, [cssDir] the per-component
     * chunks (`<tag>.css`), [globalAssets] the site's every-page tier (e.g. `app.css`).
     *
     * When [prodJsDir] holds production bundles, the report prices the **production shape**:
     * one merged stylesheet per page (its closure minus the shared set, which counts
     * into the global tier) and one `<slug>.page.js` bundle per page — what a deployed site
     * actually serves. Without it, the dev chunk shape is priced.
     */
    fun report(
        entries: List<PageEntry>,
        jsDir: File?,
        cssDir: File?,
        globalAssets: List<File>,
        prodJsDir: File? = null,
    ): SiteReport {
        val missing = mutableListOf<String>()
        val global = globalAssets.mapNotNull { measure(it.name, it, missing) }.toMutableList()
        val production = prodJsDir?.takeIf { it.isDirectory }

        if (production == null) {
            val pages = entries.map { entry ->
                val jsNames = listOfNotNull(entry.pageJs) + entry.componentJs
                val js = jsNames.mapNotNull { measure(it, jsDir?.let { d -> File(d, it) }, missing) }
                val css = entry.cssTags.mapNotNull { tag ->
                    measure("$tag.css", cssDir?.let { d -> File(d, "$tag.css") }, missing)
                }
                PageReport(entry.objectName, entry.route, js, css)
            }
            return SiteReport(global, pages, missing.distinct())
        }

        // Production shape. Mirrors BmlProductionAssets in bml-server (not a dependency of this
        // plugin): shared = styled-closure intersection -> folds into app.css; page css = closure
        // minus shared, merged; page js = the one <slug>.page.js bundle.
        fun chunk(tag: String): ByteArray? {
            val file = cssDir?.let { File(it, "$tag.css") }
            if (file == null || !file.isFile) {
                missing += "$tag.css"
                return null
            }
            return file.readBytes()
        }
        fun mergedCss(tags: List<String>): ByteArray? {
            val chunks = tags.map(::chunk)
            if (chunks.any { it == null }) return null
            return chunks.filterNotNull().reduceOrNull { acc, bytes -> acc + '\n'.code.toByte() + bytes }
        }
        val shared = entries
            .map { it.cssTags.toSet() }
            .reduceOrNull { a, b -> a intersect b }
            .orEmpty()
            .sorted()
        if (shared.isNotEmpty()) {
            mergedCss(shared)?.let { bytes ->
                global += AssetSize("shared components (in app.css)", bytes.size.toLong(), gzipSize(bytes))
            }
        }
        val takenSlugs = mutableSetOf<String>()
        val pages = entries.sortedBy { it.route }.map { entry ->
            val ownTags = entry.cssTags.filterNot { it in shared }
            val needsJs = entry.pageJs != null || entry.componentJs.isNotEmpty()
            if (ownTags.isEmpty() && !needsJs) {
                return@map PageReport(entry.objectName, entry.route, emptyList(), emptyList())
            }
            var candidate = slug(entry.route)
            var n = 2
            while (!takenSlugs.add(candidate)) candidate = "${slug(entry.route)}-${n++}"
            val css = if (ownTags.isEmpty()) {
                emptyList()
            } else {
                mergedCss(ownTags)?.let { bytes ->
                    listOf(AssetSize("$candidate.page.css", bytes.size.toLong(), gzipSize(bytes)))
                }.orEmpty()
            }
            val js = if (!needsJs) {
                emptyList()
            } else {
                listOfNotNull(measure("$candidate.page.js", File(production, "$candidate.page.js"), missing))
            }
            PageReport(entry.objectName, entry.route, js, css)
        }
        return SiteReport(global, pages, missing.distinct())
    }

    /** Mirrors `BmlProductionAssets.slug` in bml-server: `/lists/{id}` -> `lists-id`, `/` -> `index`. */
    fun slug(route: String): String =
        route.trim('/').replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').lowercase().ifEmpty { "index" }

    fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }

    /** The human-readable table logged by the task. */
    fun renderTable(report: SiteReport): String = buildString {
        appendLine("BML site metrics — first-load payload per page (raw, gzip transfer)")
        val globalRaw = report.global.sumOf { it.rawBytes }
        val globalGzip = report.global.sumOf { it.gzipBytes }
        if (report.global.isEmpty()) {
            appendLine("Global tier: (none configured)")
        } else {
            val parts = report.global.joinToString(", ") {
                "${it.name} ${formatBytes(it.rawBytes)} (${formatBytes(it.gzipBytes)})"
            }
            appendLine("Global tier: $parts")
        }
        appendLine()
        val header = listOf("Page", "Route", "JS", "CSS chunks", "First load (incl. global)")
        val rows = report.pages.map { page ->
            listOf(
                page.objectName,
                page.route,
                cell(page.js.sumOf { it.rawBytes }, page.js.sumOf { it.gzipBytes }),
                cell(page.css.sumOf { it.rawBytes }, page.css.sumOf { it.gzipBytes }),
                cell(page.rawBytes + globalRaw, page.gzipBytes + globalGzip),
            )
        }
        val widths = (listOf(header) + rows).fold(IntArray(header.size)) { acc, row ->
            row.forEachIndexed { i, col -> if (col.length > acc[i]) acc[i] = col.length }
            acc
        }
        fun line(row: List<String>) =
            appendLine(row.mapIndexed { i, col -> col.padEnd(widths[i]) }.joinToString("  ").trimEnd())
        line(header)
        line(widths.map { "-".repeat(it) })
        rows.forEach { line(it) }
        if (report.missing.isNotEmpty()) {
            appendLine()
            appendLine("Missing asset files (not counted): ${report.missing.joinToString(", ")}")
        }
    }

    private fun cell(raw: Long, gzip: Long): String =
        if (raw == 0L) "-" else "${formatBytes(raw)} (${formatBytes(gzip)})"

    /** The machine-readable report written to `build/reports/bml/site-metrics.json`. */
    fun renderJson(report: SiteReport): String = buildString {
        fun esc(s: String): String = buildString {
            for (c in s) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        fun asset(a: AssetSize): String =
            """{"name":"${esc(a.name)}","rawBytes":${a.rawBytes},"gzipBytes":${a.gzipBytes}}"""
        append("{\"format\":\"bml-site-metrics\",\"version\":1,")
        append("\"global\":[").append(report.global.joinToString(",", transform = ::asset)).append("],")
        append("\"pages\":[")
        report.pages.forEachIndexed { i, page ->
            if (i > 0) append(',')
            append("{\"page\":\"${esc(page.objectName)}\",\"route\":\"${esc(page.route)}\",")
            append("\"js\":[").append(page.js.joinToString(",", transform = ::asset)).append("],")
            append("\"css\":[").append(page.css.joinToString(",", transform = ::asset)).append("],")
            val raw = page.rawBytes + report.global.sumOf { it.rawBytes }
            val gzip = page.gzipBytes + report.global.sumOf { it.gzipBytes }
            append("\"firstLoad\":{\"rawBytes\":$raw,\"gzipBytes\":$gzip}}")
        }
        append("],")
        append("\"missing\":[").append(report.missing.joinToString(",") { "\"${esc(it)}\"" }).append("]}")
    }
}
