package bosca.meilisearch.client

import bosca.meilisearch.client.model.MeilisearchError

/**
 * Thrown when Meilisearch returns an error response (4xx/5xx).
 * Carries the structured error details from the API response body
 * so callers can match on the error [code] (e.g. "index_not_found").
 */
class MeilisearchApiException(
    val statusCode: Int,
    val error: MeilisearchError,
) : Exception("Meilisearch error ${error.code}: ${error.message}") {

    /** The error type category for programmatic matching. */
    val type: String get() = error.type

    /** The specific error code. */
    val code: String get() = error.code
}
