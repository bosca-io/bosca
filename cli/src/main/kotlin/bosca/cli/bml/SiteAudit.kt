package bosca.cli.bml

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.zip.GZIPInputStream

/**
 * Framework-agnostic page-weight audit: fetches a route's HTML from a running site,
 * discovers the assets that page declares (stylesheets, scripts, preload links, images), fetches
 * each one, and records real transfer bytes (as served, compressed), decoded bytes, and timing.
 * Works against any HTTP site, so a BML deployment can be measured 1:1 against sites built on
 * other stacks.
 *
 * Deliberately not fetched: assets referenced from *inside* CSS (fonts, background images) — a
 * browser downloads only the subsets/branches it actually needs (e.g. `unicode-range` font
 * splits), so counting them all would overstate the payload. Their reference count is reported
 * instead.
 */
class SiteAudit(private val http: OkHttpClient) {

    enum class Category { HTML, CSS, JS, FONT, IMAGE, OTHER }

    @Serializable
    data class Resource(
        val url: String,
        val category: Category,
        val status: Int,
        /** Bytes on the wire as the server sent them (compressed when the server compresses). */
        val transferBytes: Long,
        /** Bytes after decoding the content encoding — what the browser parses. */
        val decodedBytes: Long,
        val millis: Long,
    )

    @Serializable
    data class RouteAudit(
        val route: String,
        val resources: List<Resource>,
        /** `url(...)` references inside fetched CSS — declared but not fetched (see class KDoc). */
        val cssUrlRefs: Int,
    ) {
        val requestCount: Int get() = resources.size
        val transferBytes: Long get() = resources.sumOf { it.transferBytes }
        val decodedBytes: Long get() = resources.sumOf { it.decodedBytes }
        val totalMillis: Long get() = resources.sumOf { it.millis }
        fun transferBytes(category: Category): Long =
            resources.filter { it.category == category }.sumOf { it.transferBytes }
    }

    @Serializable
    data class AuditReport(val base: String, val routes: List<RouteAudit>)

    fun audit(base: String, routes: List<String>): AuditReport {
        val baseUrl = base.toHttpUrlOrNull() ?: error("Not a valid http(s) URL: $base")
        return AuditReport(base, routes.map { auditRoute(baseUrl, it) })
    }

    private fun auditRoute(baseUrl: HttpUrl, route: String): RouteAudit {
        val pageUrl = baseUrl.resolve(route) ?: error("Cannot resolve route '$route' against $baseUrl")
        val page = fetch(pageUrl.toString(), Category.HTML)
        val resources = mutableListOf(page.resource)
        var cssRefs = 0
        val html = page.body?.toString(Charsets.UTF_8).orEmpty()
        for (assetUrl in discoverAssets(html, pageUrl)) {
            val fetched = fetch(assetUrl, categorize(assetUrl, null))
            resources += fetched.resource
            if (fetched.resource.category == Category.CSS) {
                cssRefs += CSS_URL_REF.findAll(fetched.body?.toString(Charsets.UTF_8).orEmpty())
                    .count { !it.groupValues[1].startsWith("data:") }
            }
        }
        return RouteAudit(route, resources, cssRefs)
    }

    private class Fetched(val resource: Resource, val body: ByteArray?)

    /**
     * Fetches [url] with an explicit `Accept-Encoding: gzip` — setting the header ourselves makes
     * OkHttp hand back the raw (still-compressed) body, so [Resource.transferBytes] is the real
     * wire size; the decoded size comes from gunzipping it here.
     */
    private fun fetch(url: String, category: Category): Fetched {
        val started = System.nanoTime()
        return try {
            http.newCall(
                Request.Builder().url(url).header("Accept-Encoding", "gzip").build(),
            ).execute().use { response ->
                val raw = response.body.bytes()
                val decoded = if (response.header("Content-Encoding")?.contains("gzip") == true) {
                    GZIPInputStream(raw.inputStream()).readBytes()
                } else {
                    raw
                }
                val millis = (System.nanoTime() - started) / 1_000_000
                val resolved = categorize(url, response.header("Content-Type"), category)
                Fetched(
                    Resource(url, resolved, response.code, raw.size.toLong(), decoded.size.toLong(), millis),
                    decoded,
                )
            }
        } catch (_: Exception) {
            val millis = (System.nanoTime() - started) / 1_000_000
            // An unreachable asset is a finding, not a crash: recorded as status 0 with no bytes,
            // which the report renders as a failed request.
            Fetched(Resource(url, category, 0, 0, 0, millis), null)
        }
    }

    companion object {
        private val LINK_TAG = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
        private val SCRIPT_SRC = Regex("<script\\b[^>]*?\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        private val IMG_SRC = Regex("<img\\b[^>]*?\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        private val CSS_URL_REF = Regex("url\\(\\s*['\"]?([^'\")]+)['\"]?\\s*\\)")

        private fun attr(tag: String, name: String): String? =
            Regex("\\b$name\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
                .find(tag)?.groupValues?.get(1)

        /**
         * The asset URLs a page's HTML declares for download: stylesheets, preloads
         * (style/script/font/image + modulepreload), scripts, and images. Relative URLs resolve
         * against [pageUrl]; `data:` URIs and unresolvable hrefs are skipped; order-preserving
         * dedupe.
         */
        fun discoverAssets(html: String, pageUrl: HttpUrl): List<String> {
            val urls = linkedSetOf<String>()
            fun add(raw: String?) {
                if (raw.isNullOrBlank() || raw.startsWith("data:")) return
                pageUrl.resolve(raw)?.let { urls += it.toString() }
            }
            for (match in LINK_TAG.findAll(html)) {
                val tag = match.value
                val rel = attr(tag, "rel")?.lowercase() ?: continue
                val wanted = "stylesheet" in rel || "modulepreload" in rel ||
                    ("preload" in rel && attr(tag, "as")?.lowercase() in setOf("style", "script", "font", "image"))
                if (wanted) add(attr(tag, "href"))
            }
            SCRIPT_SRC.findAll(html).forEach { add(it.groupValues[1]) }
            IMG_SRC.findAll(html).forEach { add(it.groupValues[1]) }
            return urls.toList()
        }

        /** Categorizes by content type first, then by URL extension, else [fallback]. */
        fun categorize(url: String, contentType: String?, fallback: Category = Category.OTHER): Category {
            val type = contentType?.substringBefore(';')?.trim()?.lowercase()
            when {
                type == null -> Unit
                type.startsWith("text/html") -> return Category.HTML
                type.startsWith("text/css") -> return Category.CSS
                "javascript" in type || type == "text/ecmascript" -> return Category.JS
                type.startsWith("font/") || "font" in type -> return Category.FONT
                type.startsWith("image/") -> return Category.IMAGE
            }
            val path = url.substringBefore('?').substringBefore('#').lowercase()
            return when (path.substringAfterLast('.', "")) {
                "css" -> Category.CSS
                "js", "mjs" -> Category.JS
                "woff", "woff2", "ttf", "otf", "eot" -> Category.FONT
                "png", "jpg", "jpeg", "gif", "webp", "avif", "svg", "ico" -> Category.IMAGE
                "html", "htm" -> Category.HTML
                else -> fallback
            }
        }
    }
}
