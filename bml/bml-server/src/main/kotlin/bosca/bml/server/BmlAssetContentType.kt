package bosca.bml.server

import bosca.server.ContentType

/** Content types for whitelisted packaged resources, including stylesheet images and fonts. */
internal fun bmlAssetContentType(path: String): ContentType {
    val mime = when (path.substringAfterLast('.', "").lowercase()) {
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "json", "map" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "ico" -> "image/x-icon"
        "webp" -> "image/webp"
        "avif" -> "image/avif"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "txt" -> "text/plain"
        "html" -> "text/html"
        "xml" -> "application/xml"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }
    return ContentType(mime.substringBefore('/'), mime.substringAfter('/'))
}
