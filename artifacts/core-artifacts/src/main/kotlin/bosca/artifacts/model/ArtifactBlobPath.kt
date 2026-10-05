package bosca.artifacts.model

import bosca.storage.service.StringObjectPath

/**
 * Derives a content-addressable storage path from a blob digest.
 *
 * Blobs are stored under a two-level directory structure derived from the digest
 * to avoid hot directories in object stores:
 * ```
 * artifacts/blobs/sha256/ab/abcdef1234567890...
 * ```
 *
 * @param digest the blob digest in `algorithm:hex` format (e.g., `sha256:abcdef...`)
 */
class ArtifactBlobPath(digest: String) : StringObjectPath(digestToPath(digest)) {

    companion object {
        private fun digestToPath(digest: String): String {
            val parts = digest.split(":", limit = 2)
            require(parts.size == 2) { "Invalid digest format: $digest" }
            val algorithm = parts[0]
            require(algorithm.isNotBlank()) { "Digest algorithm must not be blank: $digest" }
            val hex = parts[1]
            require(hex.length >= 2) { "Digest hex too short: $digest" }
            require(hex.all { it in '0'..'9' || it in 'a'..'f' }) { "Digest hex contains non-hexadecimal characters: $digest" }
            return "artifacts/blobs/$algorithm/${hex.substring(0, 2)}/$hex"
        }
    }
}

/**
 * Storage path for temporary chunk data during a Docker chunked blob upload.
 *
 * @param sessionId the upload session UUID
 * @param chunkIndex zero-based index of the chunk within the session
 */
class UploadChunkPath(sessionId: String, chunkIndex: Int) : StringObjectPath(
    "artifacts/uploads/$sessionId/$chunkIndex"
)

/** Storage path where an object-store multipart upload is created and completed in place. */
class UploadSessionPath(sessionId: String) : StringObjectPath(
    "artifacts/uploads/$sessionId"
)
