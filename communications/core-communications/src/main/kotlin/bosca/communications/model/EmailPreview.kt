package bosca.communications.model

import kotlinx.serialization.Serializable

/**
 * A rendered email template preview: the resolved reference, the artifact
 * [version] that actually rendered, and every authored detail — [subject], browser-viewable
 * [html] (`bml-inline` images as `data:` URIs), and the plain-text alternative [text].
 */
@Serializable
data class EmailPreview(
    val project: String,
    val templateKey: String,
    /** The published artifact version that rendered (pin / override / active). */
    val version: String,
    val subject: String,
    val html: String,
    val text: String,
)
