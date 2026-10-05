package bosca.feeds.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** The wire/transport protocol of a feed source. */
enum class SourceType { RSS, ATOM, JSON_FEED, API }

/** Who owns a feed source: Bosca-managed (global) or a specific user (private). */
enum class Ownership { MANAGED, USER }

/**
 * The authoritative, declarative spec of a feed source. Serialized into the content
 * `Source.configuration` (a `JsonElement`) — this is the source of truth. The indexed/queryable
 * operational subset (enabled, owner, canonical url) is projected into the `feeds.feed_sources`
 * sidecar for the scheduler and the overlap rule.
 *
 * `url` is the normalized form of `endpoint` used as the cross-source uniqueness key; the
 * service fills it in if left blank. (formalizes the normalization.)
 */
@Serializable
data class FeedConfiguration(
    val type: SourceType,
    val endpoint: String,
    val cronInterval: String,
    val ownership: Ownership = Ownership.MANAGED,
    @Contextual val ownerProfileId: UUID? = null,
    val enabled: Boolean = true,
    val url: String = "",
    val auth: FeedAuth = FeedAuth.None,
)
