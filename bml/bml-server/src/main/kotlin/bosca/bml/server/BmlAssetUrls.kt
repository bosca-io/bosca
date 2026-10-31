package bosca.bml.server

import java.net.URLEncoder

/** Builds generated asset URLs and resolves bundled CSS dependencies. */
internal object BmlAssetUrls {

    private val libraryCssUrl = Regex("""url\(\s*(["']?)(/_bml/(?:libraries|global)/[^\s)"']+)\1\s*\)""")

    /** Resolves CSS dependencies to the same immutable URLs used by the asset inventory. */
    fun libraryCss(css: String, urls: Map<String, String>): String = libraryCssUrl.replace(css) { match ->
        val url = match.groupValues[2]
        val replacement = urls[url.substringBefore('?').substringBefore('#')] ?: return@replace match.value
        val suffix = url.substringAfter('#', "").let { if ('#' in url) "#$it" else "" }
        "url(${match.groupValues[1]}$replacement$suffix${match.groupValues[1]})"
    }

    fun content(url: String, digest: String): String {
        val hash = digest.removePrefix("sha256:")
        val dot = url.lastIndexOf('.')
        return if (dot > url.lastIndexOf('/')) "${url.take(dot)}-$hash${url.substring(dot)}" else "$url-$hash"
    }

    fun versioned(url: String, cacheToken: String?): String {
        if (cacheToken == null) return url
        val separator = if ('?' in url) '&' else '?'
        val encoded = URLEncoder.encode(cacheToken, Charsets.UTF_8).replace("+", "%20")
        return "$url${separator}_ts=$encoded"
    }
}
