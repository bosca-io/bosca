package bosca.content.metadata.routes

import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.db.connection
import bosca.routes.Route
import bosca.routes.annotations.RouteController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.storage.service.ObjectNotFoundException
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.UrlSigner
import bosca.storage.service.download
import bosca.server.ContentType
import bosca.server.HttpHeaders
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import kotlinx.serialization.KSerializer
import java.net.URI

@RouteController("/api/v1/content/metadata/download")
open class Download(
    private val metadataService: MetadataService,
    private val metadataPermissionEvaluator: MetadataPermissionEvaluator,
    private val objectService: ObjectStorageService,
    private val urlSigner: UrlSigner
) : Route<Unit>() {

    companion object {
        /** Maximum bytes to serve per Range request chunk (200 MB). */
        private const val MAX_RANGE_CHUNK_SIZE = 200L * 1024 * 1024

        /**
         * Parses an HTTP Range header value (e.g. "bytes=0-499") and resolves it
         * against the given [contentLength] into a single [LongRange].
         *
         * Supports a single byte range specification:
         * - "bytes=start-end" returns start..end clamped to contentLength
         * - "bytes=start-" returns start until end of content
         * - "bytes=-suffixLength" returns the last suffixLength bytes
         *
         * Returns null if the header is absent, malformed, or contains no valid ranges.
         */
        fun parseRangeHeader(header: String?, contentLength: Long): LongRange? {
            if (header == null) return null
            val trimmed = header.trim()
            if (!trimmed.startsWith("bytes=")) return null
            val spec = trimmed.removePrefix("bytes=").trim()
            // Take only the first range if multiple are specified
            val part = spec.split(",").firstOrNull()?.trim() ?: return null
            val dashIndex = part.indexOf('-')
            if (dashIndex < 0) return null
            val startStr = part.substring(0, dashIndex).trim()
            val endStr = part.substring(dashIndex + 1).trim()
            return when {
                startStr.isEmpty() && endStr.isNotEmpty() -> {
                    // Suffix range: "bytes=-500" means last 500 bytes
                    val suffix = endStr.toLongOrNull() ?: return null
                    val start = (contentLength - suffix).coerceAtLeast(0)
                    start..<contentLength
                }
                startStr.isNotEmpty() && endStr.isEmpty() -> {
                    // Open-ended: "bytes=500-"
                    val start = startStr.toLongOrNull() ?: return null
                    if (start >= contentLength) return null
                    start..<contentLength
                }
                startStr.isNotEmpty() && endStr.isNotEmpty() -> {
                    // Explicit range: "bytes=0-499"
                    val start = startStr.toLongOrNull() ?: return null
                    val end = endStr.toLongOrNull() ?: return null
                    if (start > end || start >= contentLength) return null
                    start..end.coerceAtMost(contentLength - 1)
                }
                else -> null
            }
        }
    }

    override fun serializer(): KSerializer<Unit>? = null

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val id = UUID.parse(call.request.queryParameters["id"] ?: error("missing id"))
        val metadata = metadataService.getById(id) ?: error("Metadata not found")
        var urlSigned = false
        if (!metadataPermissionEvaluator.isContentAllowed(authenticationContext, metadata, PermissionAction.VIEW)) {
            if (!urlSigner.verify(URI.create(call.request.uri))) {
                call.respond(HttpStatusCode.Forbidden)
                return
            } else {
                urlSigned = true
            }
        }
        try {
            val supplementary = if (call.request.queryParameters.contains("supplementaryId")) {
                val supplementaryId = call.request.queryParameters["supplementaryId"] ?: error("missing supplementaryId")
                val supplementary = metadataService.getSupplementaryById(UUID.parse(supplementaryId)) ?: throw NoSuchElementException("Supplementary not found")
                if (!urlSigned) {
                    metadataPermissionEvaluator.verifySupplementaryAllowed(authenticationContext, metadata, PermissionAction.VIEW)
                }
                supplementary
            } else {
                null
            }

            connection().release()

            if (call.respondNotModifiedIfMatches(metadata.etag)) return

            val range = (supplementary?.contentLength ?: metadata.contentLength)?.let { contentLength ->
                parseRangeHeader(call.request.header(HttpHeaders.Range), contentLength)
            }?.let { resolved ->
                val size = resolved.last - resolved.first + 1
                if (size > MAX_RANGE_CHUNK_SIZE) {
                    resolved.first..<resolved.first + MAX_RANGE_CHUNK_SIZE
                } else {
                    resolved
                }
            }
            val rangeLength = range?.let { it.last - it.first + 1 }

            // Open the object before describing it: a 404 must carry none of this object's headers (a
            // Content-Length would leave the client waiting for bytes never sent).
            objectService.download(metadata, supplementary?.id, range).use { inputStream ->
                if (call.request.queryParameters["filename"] == "true" || call.request.queryParameters["download"] == "true") {
                    val name = supplementary?.name ?: metadata.name
                    call.response.header(
                        HttpHeaders.ContentDisposition,
                        "attachment; filename=\"${name.replace("\"", "\\\"").replace("\n", " ").replace("\r", " ")}\""
                    )
                }
                call.setEntityTagHeaders(metadata.etag)
                call.response.header(HttpHeaders.AcceptRanges, "bytes")
                range?.let {
                    call.response.header(
                        HttpHeaders.ContentRange,
                        "bytes ${it.first}-${it.last}/${supplementary?.contentLength ?: metadata.contentLength ?: 0}"
                    )
                }
                (rangeLength ?: supplementary?.contentLength ?: metadata.contentLength)?.let {
                    call.response.header(HttpHeaders.ContentLength, it.toString())
                }
                // No time limit: a large download on a slow link may take long; a client that stops
                // reading altogether is ended by the streaming response's stall timeout.
                call.respondStreaming(
                    contentType = ContentType.parse(supplementary?.contentType ?: metadata.contentType),
                    status = rangeLength?.let { HttpStatusCode.PartialContent } ?: HttpStatusCode.OK,
                    timeLimit = null,
                ) { stream ->
                    stream.copyFrom(inputStream)
                }
            }
        } catch (e: ObjectNotFoundException) {
            // A missing object is the client's 404. A lazy backend (GCS) can report it mid-stream,
            // once the response is committed; then it can only fail the response.
            if (call.response.isCommitted) throw e
            call.respond(HttpStatusCode.NotFound, "File not found")
        }
    }
}

/**
 * Answers 304 Not Modified, with the entity's tag headers, when [etag] matches the request's
 * If-None-Match; returns whether it did.
 */
internal suspend fun ServerCall.respondNotModifiedIfMatches(etag: String?): Boolean {
    if (etag == null) return false
    val ifNoneMatch = request.header(HttpHeaders.IfNoneMatch)?.trim() ?: return false
    val matches = ifNoneMatch == "*" || ifNoneMatch.split(",").any { candidate ->
        val tag = candidate.trim()
        tag == "W/\"$etag\"" || tag == "\"$etag\""
    }
    if (!matches) return false
    setEntityTagHeaders(etag)
    respond(HttpStatusCode.NotModified)
    return true
}

/** Sets the weak ETag for [etag] and requires revalidation; nothing when there is no tag. */
internal fun ServerCall.setEntityTagHeaders(etag: String?) {
    if (etag == null) return
    response.header(HttpHeaders.ETag, "W/\"$etag\"")
    response.header(HttpHeaders.CacheControl, "no-cache")
}
