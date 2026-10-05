package bosca.communications.service

/** The rendered subject, HTML body, and plaintext alternative for an email template. */
data class RenderedEmail(
    val subject: String,
    val html: String,
    val text: String,
    /** Inline (`cid:`-referenced) images the render used; the mailer attaches each. */
    val images: List<RenderedEmailImage> = emptyList(),
    /** The published artifact version used for rendering. */
    val version: String = "",
)

/** An inline image attachment: the HTML references `cid:[cid]`; [contentBase64] is the bytes. */
data class RenderedEmailImage(
    val cid: String,
    val mediaType: String,
    val filename: String,
    val contentBase64: String,
)
