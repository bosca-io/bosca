package bosca.bml.project

/**
 * Generates SEO discovery files (sitemap.xml, robots.txt, llms.txt) from a
 * project's routes. Parameterized routes (those containing `{…}`)
 * are excluded from the sitemap/llms listings.
 */
object DiscoveryGenerator {

    fun sitemap(baseUrl: String, routes: List<String>): String {
        val base = baseUrl.trimEnd('/')
        val urls = staticRoutes(routes)
        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">""")
            for (route in urls) {
                appendLine("  <url><loc>${escapeXml(base + normalize(route))}</loc></url>")
            }
            append("</urlset>")
        }
    }

    fun robots(baseUrl: String, disallow: List<String> = emptyList()): String = buildString {
        appendLine("User-agent: *")
        if (disallow.isEmpty()) appendLine("Disallow:") else disallow.forEach { appendLine("Disallow: $it") }
        append("Sitemap: ${baseUrl.trimEnd('/')}/sitemap.xml")
    }

    fun llms(siteName: String, summary: String, routes: List<String>): String = buildString {
        appendLine("# $siteName")
        appendLine()
        appendLine("> $summary")
        appendLine()
        appendLine("## Pages")
        staticRoutes(routes).forEach { appendLine("- $it") }
    }

    private fun staticRoutes(routes: List<String>): List<String> =
        routes.filterNot { it.contains('{') }.distinct().sorted()

    private fun normalize(route: String): String = if (route.startsWith("/")) route else "/$route"

    private fun escapeXml(s: String): String = buildString {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }
}
