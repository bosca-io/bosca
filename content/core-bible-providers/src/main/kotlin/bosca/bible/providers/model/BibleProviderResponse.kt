package bosca.bible.providers.model

import bosca.bible.BibleAttribution
import bosca.bible.IBibleAttribution
import kotlinx.serialization.Serializable

/** A provider response whose content and attribution may still be projected domain objects. */
interface IBibleProviderResponse<out T> {

    val content: T
    val attribution: IBibleAttribution?
    val verseCount: Int?
    val fumsToken: String?

    /**
     * Captures the response using the content model's explicit [serializeContent] conversion.
     * Callers convert Bible interfaces with their asSerializable methods before serialization.
     */
    fun <R> asSerializable(serializeContent: (T) -> R): BibleProviderResponse<R> = BibleProviderResponse(
        serializeContent(content),
        attribution?.asSerializable(),
        verseCount,
        fumsToken,
    )
}

/**
 * Existing content models with metadata from their exact provider response. Missing response
 * attribution falls back to the source's attribution. [verseCount] counts every verse in a
 * text response, including grouped spans, and is absent for responses without Scripture text.
 * [fumsToken] remains server-side beside the cached content; this wrapper must not be exposed
 * through GraphQL or reader routes.
 */
@Serializable
data class BibleProviderResponse<T>(
    override val content: T,
    override val attribution: BibleAttribution? = null,
    override val verseCount: Int? = null,
    override val fumsToken: String? = null,
) : IBibleProviderResponse<T> {
    override fun toString(): String =
        "BibleProviderResponse(verseCount=$verseCount, fumsToken=[redacted])"
}
