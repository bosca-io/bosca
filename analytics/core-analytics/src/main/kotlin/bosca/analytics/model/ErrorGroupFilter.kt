package bosca.analytics.model

import kotlinx.serialization.Serializable

/**
 * Optional filters used by the error tracking list view. All fields are
 * combined with AND semantics; null fields are ignored. The [search]
 * field is matched case-insensitively against both the error type and
 * the latest representative message.
 */
@Serializable
data class ErrorGroupFilter(
    val appId: String? = null,
    val status: ErrorGroupStatus? = null,
    val fatal: Boolean? = null,
    val search: String? = null,
)
