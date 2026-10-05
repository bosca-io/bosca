package bosca.storage.service

import bosca.service.Service
import java.net.URI

/**
 * Service for creating and verifying time-limited signed URLs.
 *
 * Signed URLs embed a cryptographic signature and expiration timestamp, allowing
 * the platform to grant temporary access to resources without requiring authentication
 * headers. This is typically used for content download links that can be shared
 * or embedded in contexts where bearer tokens are not practical.
 */
interface UrlSigner : Service {

    /**
     * Appends a cryptographic signature and expiration to the given URL, producing
     * a signed URL that is valid for the specified duration.
     *
     * @param url the original URL to sign
     * @param durationSecs the number of seconds the signed URL should remain valid
     * @return the signed URL string with embedded signature and expiration parameters
     */
    fun sign(url: String, durationSecs: Long): String

    /**
     * Verifies that a signed URL has a valid signature and has not expired.
     *
     * @param url the signed URI to verify
     * @return `true` if the signature is valid and the URL has not expired, `false` otherwise
     */
    fun verify(url: URI): Boolean
}