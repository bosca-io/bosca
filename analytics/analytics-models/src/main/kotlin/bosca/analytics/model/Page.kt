package bosca.analytics.model

import kotlinx.serialization.Serializable

/**
 * The page a user was on when an analytics event was emitted.
 *
 * Carried as a top-level field on [Event] (rather than packed into
 * `element.extras` as it historically was for page-view events) so that
 * server-side queries can filter by page without parsing JSON. The
 * experimentation aggregation job uses this to scope a conversion goal to
 * a specific [path], answering questions like "did variant B increase
 * interactions on /checkout?" — a question the older event schema could
 * not express because interaction events did not carry any page context at
 * all.
 *
 * All fields are nullable so that legacy clients (which do not yet emit
 * `page`) and non-browser SDKs (which have no `window` to read from)
 * continue to deserialize successfully.
 */
@Serializable
data class Page(
    /** `window.location.pathname` at emit time, e.g. `"/checkout"`. */
    val path: String? = null,
    /** `window.location.href` at emit time. */
    val url: String? = null,
    /** `document.title` at emit time. */
    val title: String? = null,
)
