package bosca.bml.email

/**
 * The email-channel output of a BML message template: the resolved [subject] line, the
 * email-safe [html] body (scripts stripped / CSS inlined via [bosca.bml.render.EmailRenderer]),
 * and the plain-text [text] alternative (via [bosca.bml.render.PlainTextRenderer]).
 */
data class RenderedEmail(
    val subject: String,
    val html: String,
    val text: String,
    /** The `bml-inline` images this render actually used — one attachment per [EmailImage.cid]. */
    val images: List<EmailImage> = emptyList(),
)
