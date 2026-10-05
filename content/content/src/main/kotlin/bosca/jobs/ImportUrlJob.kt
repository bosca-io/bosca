package bosca.jobs

import bosca.content.configuration.JobQueueNames
import bosca.content.metadata.events.ImportProgress
import bosca.content.metadata.events.METADATA_IMPORT_PROGRESS_CHANNEL
import bosca.content.metadata.model.SourceStatus
import bosca.content.metadata.service.MetadataService
import bosca.jobs.ImportUrlJobExecutor.Companion.PROGRESS_INTERVAL
import bosca.pubsub.PubSubService
import bosca.queue.annotations.IJobDefinition
import bosca.security.service.SecurityService
import bosca.security.service.impersonate
import bosca.queue.annotations.JobDefinition
import bosca.serialization.UUID
import bosca.server.http.await
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.sharedqueue.jobs.FailException
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.StorageDispatcher
import com.fleeksoft.ksoup.Ksoup
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.closeQuietly
import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.URI
import java.net.URLConnection
import java.net.URLDecoder
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Job payload for importing content from an external URL into Bosca storage.
 *
 * When enqueued, triggers [ImportUrlJobExecutor] which downloads the content
 * from the given URL and streams it directly into the storage backend. This
 * is intended for large files such as videos where the client should not
 * proxy the content through its own connection.
 */
@Serializable
data class ImportUrlJob(
    val id: UUID,
    val url: String,
    val contentType: String? = null,
    val headers: Map<String, String>? = null,
    val ready: Boolean = false,
    val principalId: UUID? = null,
) : IJobDefinition

/**
 * Executor that downloads content from an external URL and stores it
 * as the primary content for a metadata item.
 *
 * The download is streamed directly into object storage to avoid buffering
 * large files in memory or on disk. Progress events are published to
 * [METADATA_IMPORT_PROGRESS_CHANNEL] every 512KB so that clients can
 * display real-time download progress.
 *
 * On successful completion the metadata record is marked as uploaded with
 * the resolved content type and byte count.
 */
@JobDefinition(ImportUrlJob::class, JobQueueNames.contentJobQueue, "import-url")
class ImportUrlJobExecutor(
    private val metadataService: MetadataService,
    private val objectStorageService: ObjectStorageService,
    private val pubSubService: PubSubService,
    private val securityService: SecurityService,
) : AbstractJobExecutor<ImportUrlJob>(ImportUrlJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        val metadata = metadataService.getById(job.id)
            ?: throw FailException("Metadata ${job.id} not found")

        metadataService.setSourceStatus(metadata.id, SourceStatus.IMPORTING)

        val path = objectStorageService.getPath(metadata)

        val downloadUrl = resolveDownloadUrl(job.url)
        val requestBuilder = Request.Builder()
            .url(downloadUrl)
            .addHeader("User-Agent", BROWSER_USER_AGENT)
        job.headers?.forEach { (name, value) ->
            requestBuilder.addHeader(name, value)
        }

        var response = http.newCall(requestBuilder.build()).await()
        try {
            if (!response.isSuccessful) {
                val code = response.code
                if (code in 400..499) {
                    throw FailException("URL returned $code for ${job.url}")
                }
                error("URL returned $code for ${job.url}")
            }

            // Some hosts (notably Google Drive) return an HTML page instead of
            // the file — either a virus-scan confirmation form, a page with a
            // download link, or an error page. Parse and handle accordingly.
            var contentType = response.header("Content-Type") ?: ""
            // Prefer the caller-provided content type when the response header is generic
            if (isGenericBinaryType(contentType) && job.contentType != null && !isGenericBinaryType(job.contentType)) {
                log.info("Using caller-provided content type '{}' over response header '{}' for {}", job.contentType, contentType, job.url)
                contentType = job.contentType
            }
            if (contentType.contains("text/html")) {
                val html = response.body.string()
                val requestUrl = response.request.url
                response.closeQuietly()
                checkForHtmlErrors(html, job.url)
                response = resolveDownloadFromHtml(html, requestUrl)
                    ?: throw FailException("URL returned HTML instead of downloadable content for ${job.url}")
                if (!response.isSuccessful) {
                    val code = response.code
                    if (code in 400..499) {
                        throw FailException("HTML follow-up returned $code for ${job.url}")
                    }
                    error("HTML follow-up returned $code for ${job.url}")
                }
                contentType = response.header("Content-Type") ?: contentType
            }

            if (isGenericBinaryType(contentType)) {
                val resolved = guessContentTypeFromDisposition(response.header("Content-Disposition"))
                    ?: guessContentTypeFromUrl(job.url)
                    ?: guessContentTypeFromUrl(downloadUrl)
                if (resolved != null) {
                    log.info("Resolved generic content type '{}' to '{}' for {}", contentType, resolved, job.url)
                    contentType = resolved
                }
            }

            val body = response.body
            val totalBytes = body.contentLength().let { if (it == -1L) 0L else it }

            val written = withContext(StorageDispatcher) {
                val channel = Channel<Long>()
                val progressJob = launch {
                    channel.consumeAsFlow().collect { bytesRead ->
                        pubSubService.publish(
                            METADATA_IMPORT_PROGRESS_CHANNEL,
                            ImportProgress.serializer(),
                            ImportProgress(metadata.id, bytesRead, totalBytes)
                        )
                    }
                }
                try {
                    body.byteStream().use { rawStream ->
                        val buffered = BufferedInputStream(rawStream, SNIFF_BUFFER_SIZE)
                        if (isGenericBinaryType(contentType)) {
                            val sniffed = guessContentTypeFromStream(buffered)
                            if (sniffed != null) {
                                log.info("Sniffed content type '{}' from stream bytes for {}", sniffed, job.url)
                                contentType = sniffed
                            }
                        }
                        val progress = ProgressReportingInputStream(buffered) {
                            channel.trySend(it)
                        }
                        val contentLength = if (totalBytes > 0L) totalBytes else null
                        objectStorageService.setInputStream(path, progress, contentLength)
                    }
                } finally {
                    progressJob.cancel()
                    channel.close()
                }
            }

            metadataService.setUploaded(metadata.id, contentType, written)
            metadataService.setSourceStatus(metadata.id, SourceStatus.IMPORTED)
            if (job.ready) {
                val updated = metadataService.getById(job.id) ?: error("Metadata ${job.id} not found after upload")
                val auth = if (job.principalId != null) {
                    securityService.impersonate(job.principalId)
                } else {
                    securityService.impersonate("sa")
                }
                metadataService.setReady(updated, auth.principal().asPrincipal())
            }
            log.info("Imported {} bytes from URL for metadata {}", written, metadata.id)
        } catch (e: Exception) {
            metadataService.setSourceStatus(metadata.id, SourceStatus.FAILED)
            throw e
        } finally {
            response.closeQuietly()
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ImportUrlJobExecutor::class.java)

        private const val PROGRESS_INTERVAL = 512L * 1024L
        private const val SNIFF_BUFFER_SIZE = 8192

        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        private const val FILE_D_PREFIX = "/file/d/"

        /**
         * Attempts to extract a download URL from an HTML response page.
         *
         * Tries multiple strategies in order:
         * 1. POST form submission (Google Drive virus-scan confirmation)
         * 2. Download link extraction (anchor tags pointing to download URLs)
         *
         * @return the download [Response], or null if no download could be resolved
         */
        private suspend fun resolveDownloadFromHtml(html: String, requestUrl: HttpUrl): Response? {
            val document = Ksoup.parse(html)

            // Strategy 1: POST form (virus-scan confirmation)
            val form = document.selectFirst("form[method=post]")
            if (form != null) {
                val rawAction = form.attr("action")
                if (rawAction.isNotEmpty()) {
                    val decodedAction = rawAction.replace("&amp;", "&")
                    val actionUrl = if (decodedAction.startsWith("http")) decodedAction
                    else "${requestUrl.scheme}://${requestUrl.host}$decodedAction"

                    val formBody = FormBody.Builder()
                    for (input in form.select("input[type=hidden]")) {
                        val name = input.attr("name")
                        val value = input.attr("value")
                        if (name.isNotEmpty()) {
                            formBody.add(name, value)
                        }
                    }

                    log.info("Submitting confirmation form to {}", actionUrl)
                    val request = Request.Builder()
                        .url(actionUrl)
                        .post(formBody.build())
                        .addHeader("User-Agent", BROWSER_USER_AGENT)
                        .build()
                    return http.newCall(request).await()
                }
            }

            // Strategy 2: look for a direct download link
            val downloadLink = document.selectFirst("a[href*=download], a[href*=export], a#uc-download-link")
                ?: document.selectFirst("a[href*=usercontent]")
            if (downloadLink != null) {
                val href = downloadLink.attr("href")
                if (href.isNotEmpty()) {
                    val downloadUrl = if (href.startsWith("http")) href
                    else "${requestUrl.scheme}://${requestUrl.host}$href"
                    log.info("Following download link to {}", downloadUrl)
                    val request = Request.Builder()
                        .url(downloadUrl)
                        .addHeader("User-Agent", BROWSER_USER_AGENT)
                        .build()
                    return http.newCall(request).await()
                }
            }

            log.warn(
                "No form or download link found in HTML response from {}, HTML:\n{}", requestUrl,
                html.take(2000)
            )
            return null
        }

        private val GOOGLE_HOSTS = setOf("drive.google.com", "drive.usercontent.google.com", "docs.google.com")

        /**
         * Checks the HTML response for known error conditions and throws
         * an appropriate exception. Transient errors (like quota exceeded)
         * throw a retryable [IllegalStateException]; permanent errors throw
         * [FailException].
         *
         * Access-related checks are scoped to known Google hosts to avoid
         * false positives on pages that incidentally contain matching text.
         */
        private fun checkForHtmlErrors(html: String, url: String) {
            val lowerHtml = html.lowercase()
            val host = try {
                URI(url).host?.lowercase()
            } catch (_: Exception) {
                null
            }
            val isGoogleHost = host != null && GOOGLE_HOSTS.any { host.endsWith(it) }
            if (isGoogleHost) {
                if (lowerHtml.contains("quota exceeded") || lowerHtml.contains("too many users")) {
                    error("Download quota exceeded for $url — the file has had too many recent downloads, will retry later")
                }
                if (lowerHtml.contains("you need access") || lowerHtml.contains("request access")) {
                    throw FailException("Access denied — the file at $url is not publicly shared")
                }
                if (lowerHtml.contains("sign in") && lowerHtml.contains("accounts.google.com")) {
                    throw FailException("Authentication required — the file at $url requires sign-in and is not publicly shared")
                }
            }
        }

        private val GENERIC_BINARY_TYPES = setOf(
            "application/binary",
            "application/octet-stream",
            "binary/octet-stream",
        )

        /**
         * Determines whether the given content type is a generic binary
         * placeholder that does not convey the actual media format.
         */
        private fun isGenericBinaryType(contentType: String): Boolean {
            val normalized = contentType.substringBefore(";").trim().lowercase()
            return normalized.isEmpty() || normalized in GENERIC_BINARY_TYPES
        }

        /**
         * Attempts to guess a content type from the file extension in a URL
         * path using the JDK's built-in MIME table. Returns null when the
         * extension is absent or unrecognised.
         */
        private fun guessContentTypeFromUrl(url: String): String? {
            val path = try {
                URI(url).path
            } catch (_: Exception) {
                return null
            }
            if (path.isNullOrEmpty()) return null
            return URLConnection.guessContentTypeFromName(path)
        }

        /**
         * Extracts the filename from a Content-Disposition header and guesses
         * the content type from its extension. Returns null when the header
         * is absent, has no filename, or the extension is unrecognised.
         */
        private fun guessContentTypeFromDisposition(header: String?): String? {
            if (header.isNullOrEmpty()) return null
            val match = Regex("""filename\*?=(?:UTF-8''|")?([^";]+)"?""", RegexOption.IGNORE_CASE).find(header)
                ?: return null
            val filename = URLDecoder.decode(match.groupValues[1].trim(), Charsets.UTF_8)
            return URLConnection.guessContentTypeFromName(filename)
        }

        /**
         * Probes the leading bytes of a [BufferedInputStream] to detect the
         * content type via magic-byte signatures. The stream is marked and
         * reset so that no data is consumed. Falls back to a custom signature
         * table when the JDK built-in detection returns null.
         */
        private fun guessContentTypeFromStream(stream: BufferedInputStream): String? {
            stream.mark(SNIFF_BUFFER_SIZE)
            try {
                val jdkResult = URLConnection.guessContentTypeFromStream(stream)
                if (jdkResult != null) return jdkResult
            } finally {
                stream.reset()
            }
            stream.mark(SNIFF_BUFFER_SIZE)
            try {
                val header = ByteArray(SNIFF_BUFFER_SIZE)
                val bytesRead = stream.read(header)
                if (bytesRead < 4) return null
                return matchMagicBytes(header, bytesRead)
            } finally {
                stream.reset()
            }
        }

        /**
         * Matches the leading bytes of a file against known magic-byte
         * signatures to determine the content type. Covers common formats
         * that the JDK's built-in detection misses.
         */
        private fun matchMagicBytes(header: ByteArray, length: Int): String? {
            if (length < 4) return null

            // PDF: %PDF
            if (header[0] == 0x25.toByte() && header[1] == 0x50.toByte() &&
                header[2] == 0x44.toByte() && header[3] == 0x46.toByte()
            ) return "application/pdf"

            // ZIP (also DOCX, XLSX, PPTX, JAR, etc.)
            if (header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()) {
                if (length > 30) {
                    val inner = String(header, 30, minOf(length - 30, 100), Charsets.US_ASCII)
                    if (inner.contains("word/")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    if (inner.contains("xl/")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    if (inner.contains("ppt/")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                }
                return "application/zip"
            }

            // MP4 / MOV (ftyp box)
            if (length >= 8) {
                val ftyp = String(header, 4, 4, Charsets.US_ASCII)
                if (ftyp == "ftyp") {
                    val brand = String(header, 8, minOf(4, length - 8), Charsets.US_ASCII)
                    if (brand.startsWith("qt")) return "video/quicktime"
                    return "video/mp4"
                }
            }

            // WebM / MKV (EBML header)
            if (header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() &&
                header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte()
            ) {
                val asString = String(header, 0, minOf(length, 64), Charsets.ISO_8859_1)
                if (asString.contains("webm")) return "video/webm"
                return "video/x-matroska"
            }

            // MP3 (ID3 tag or sync word)
            if (header[0] == 0x49.toByte() && header[1] == 0x44.toByte() && header[2] == 0x33.toByte()) {
                return "audio/mpeg"
            }
            if (header[0] == 0xFF.toByte() && (header[1].toInt() and 0xE0) == 0xE0) {
                return "audio/mpeg"
            }

            // OGG
            if (header[0] == 0x4F.toByte() && header[1] == 0x67.toByte() &&
                header[2] == 0x67.toByte() && header[3] == 0x53.toByte()
            ) return "audio/ogg"

            // FLAC
            if (header[0] == 0x66.toByte() && header[1] == 0x4C.toByte() &&
                header[2] == 0x61.toByte() && header[3] == 0x43.toByte()
            ) return "audio/flac"

            // WAV (RIFF....WAVE)
            if (length >= 12 &&
                header[0] == 0x52.toByte() && header[1] == 0x49.toByte() &&
                header[2] == 0x46.toByte() && header[3] == 0x46.toByte() &&
                header[8] == 0x57.toByte() && header[9] == 0x41.toByte() &&
                header[10] == 0x56.toByte() && header[11] == 0x45.toByte()
            ) return "audio/wav"

            // WebP (RIFF....WEBP)
            if (length >= 12 &&
                header[0] == 0x52.toByte() && header[1] == 0x49.toByte() &&
                header[2] == 0x46.toByte() && header[3] == 0x46.toByte() &&
                header[8] == 0x57.toByte() && header[9] == 0x45.toByte() &&
                header[10] == 0x42.toByte() && header[11] == 0x50.toByte()
            ) return "image/webp"

            return null
        }

        private fun resolveDownloadUrl(url: String): String {
            val uri = try {
                URI(url)
            } catch (_: Exception) {
                return url
            }
            val host = uri.host ?: return url
            // Google Drive URLs — rewrite to the standard direct-download endpoint
            // which handles the redirect chain (including virus-scan tokens) properly
            if (host == "drive.usercontent.google.com" || host.contains("drive.google.com")) {
                val fileId = extractGoogleDriveFileId(uri)
                    ?: uri.query?.split("&")
                        ?.map { it.split("=", limit = 2) }
                        ?.firstOrNull { it.size == 2 && it[0] == "id" }
                        ?.get(1)
                    ?: return url
                return "https://drive.google.com/uc?export=download&id=$fileId"
            }
            // Dropbox sharing links — ensure dl=1 for direct file download
            if (host.contains("dropbox.com")) {
                val httpUrl = url.toHttpUrl()
                return httpUrl.newBuilder()
                    .removeAllQueryParameters("dl")
                    .addQueryParameter("dl", "1")
                    .build()
                    .toString()
            }
            return url
        }

        private fun extractGoogleDriveFileId(uri: URI): String? {
            val path = uri.path ?: ""
            val fileIdIndex = path.indexOf(FILE_D_PREFIX)
            if (fileIdIndex != -1) {
                val start = fileIdIndex + FILE_D_PREFIX.length
                val end = path.indexOf('/', start).let { if (it == -1) path.length else it }
                return path.substring(start, end)
            }
            val query = uri.query ?: return null
            return query.split("&")
                .map { it.split("=", limit = 2) }
                .firstOrNull { it.size == 2 && URLDecoder.decode(it[0], Charsets.UTF_8) == "id" }
                ?.let { URLDecoder.decode(it[1], Charsets.UTF_8) }
        }

        private val http = OkHttpClient.Builder()
            .callTimeout(Duration.ofMinutes(60))
            .readTimeout(Duration.ofMinutes(30))
            .writeTimeout(Duration.ofMinutes(30))
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(true)
            .followSslRedirects(true)
            .connectionPool(
                ConnectionPool(
                    maxIdleConnections = 5,
                    keepAliveDuration = 1,
                    timeUnit = TimeUnit.MINUTES
                )
            )
            .build()

        /**
         * An [InputStream] wrapper that invokes [onProgress] with the cumulative
         * byte count every [PROGRESS_INTERVAL] bytes read through the stream.
         */
        private class ProgressReportingInputStream(
            delegate: InputStream,
            private val onProgress: (bytesRead: Long) -> Unit,
        ) : FilterInputStream(delegate) {

            private var total = 0L
            private var lastReported = 0L

            override fun read(): Int {
                val b = super.read()
                if (b != -1) {
                    total++
                    maybeReport()
                }
                return b
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len)
                if (n > 0) {
                    total += n
                    maybeReport()
                }
                return n
            }

            private fun maybeReport() {
                if (total - lastReported >= PROGRESS_INTERVAL) {
                    lastReported = total
                    onProgress(total)
                }
            }
        }
    }
}
