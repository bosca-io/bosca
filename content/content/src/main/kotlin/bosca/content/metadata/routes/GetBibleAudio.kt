package bosca.content.metadata.routes

import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.db.connection
import bosca.routes.Route
import bosca.routes.annotations.RouteController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.server.ContentType
import bosca.server.HttpHeaders
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import bosca.storage.service.ObjectNotFoundException

/** Serves chapter media through Bosca with content authorization and playback permission on every request. */
@RouteController("/api/v1/content/metadata/bible/audio")
class GetBibleAudio(
    private val metadata: MetadataService,
    private val permissions: MetadataPermissionEvaluator,
    private val bibles: BibleService,
) : Route<Unit>() {
    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        call.response.header(HttpHeaders.CacheControl, "private, no-store")
        val query = call.request.queryParameters
        val id = UUID.parse(requireNotNull(query["id"]) { "Bible metadata is required" })
        val version = requireNotNull(query["version"]?.toIntOrNull()) { "Bible version is required" }
        val variant = requireNotNull(query["variant"]) { "Bible variant is required" }
        val audioId = UUID.parse(requireNotNull(query["audioId"]) { "Recording is required" })
        val recordingId = query["recordingId"]?.let { UUID.parse(it) }
        val usfm = requireNotNull(query["usfm"]) { "Chapter reference is required" }
        val item = metadata.getById(id)?.takeUnless { it.deleted }
        if (item == null) { call.respond(HttpStatusCode.NotFound); return }
        permissions.verifyContentAllowed(authenticationContext, item, PermissionAction.VIEW)
        val bible = bibles.getBible(id, version, variant)
        if (bible == null) { call.respond(HttpStatusCode.NotFound); return }
        val audio = try {
            bibles.getAudioPlayback(bible, audioId, usfm, recordingId)
        } catch (_: ObjectNotFoundException) {
            call.respond(HttpStatusCode.NotFound)
            return
        }
        val length = audio.content.contentLength
        val rangeHeader = call.request.header(HttpHeaders.Range)
        val parsed = Download.parseRangeHeader(rangeHeader, length)
        // Malformed ranges are ignored and the whole recording is served; only valid but unsatisfiable ranges fail.
        val range = parsed?.takeUnless { it.isEmpty() }
        if (parsed?.isEmpty() == true || parsed == null && rangeHeader != null && startsAfter(rangeHeader, length)) {
            call.response.header(HttpHeaders.ContentRange, "bytes */$length")
            call.respond(HttpStatusCode(416, "Range Not Satisfiable"))
            return
        }
        val stream = try {
            audio.openStream(range)
        } catch (_: ObjectNotFoundException) {
            call.respond(HttpStatusCode.NotFound)
            return
        }
        stream.use {
            connection().release()
            call.response.header(HttpHeaders.AcceptRanges, "bytes")
            range?.let { call.response.header(HttpHeaders.ContentRange, "bytes ${it.first}-${it.last}/$length") }
            call.response.header(HttpHeaders.ContentLength, (range?.let { it.last - it.first + 1 } ?: length).toString())
            call.respondStreaming(contentType = ContentType.parse(audio.content.contentType),
                status = if (range == null) HttpStatusCode.OK else HttpStatusCode.PartialContent,
                timeLimit = null) { it.copyFrom(stream) }
        }
    }

    private fun startsAfter(header: String, length: Long): Boolean =
        rangeStart.matchEntire(header.trim())?.groupValues?.get(1)?.toLongOrNull()?.let { it >= length } == true

    private companion object {
        val rangeStart = Regex("""bytes=\s*([0-9]+)-[0-9]*\s*(,.*)?""")
    }
}
