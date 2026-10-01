package bosca.mux.client

/**
 * Thrown when the Mux Video API returns an unexpected or error response.
 *
 * Wraps the HTTP status code and raw error body so callers can inspect
 * the API-level failure without parsing JSON themselves.
 */
class MuxApiException(val statusCode: Int, message: String) : Exception(message)
