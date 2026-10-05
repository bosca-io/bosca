package bosca.bml.server

import java.net.URLEncoder

/** Adds the deployment cache token to generated asset URLs without changing their route. */
internal object BmlAssetUrls {

    fun versioned(url: String, cacheToken: String?): String {
        if (cacheToken == null) return url
        val separator = if ('?' in url) '&' else '?'
        val encoded = URLEncoder.encode(cacheToken, Charsets.UTF_8).replace("+", "%20")
        return "$url${separator}_ts=$encoded"
    }
}
