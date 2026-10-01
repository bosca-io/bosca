package bosca.mux.client

import bosca.mux.client.models.AssetMeta
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MuxClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: MuxClient
    private val config = MuxClientConfig(
        tokenId = "test-token-id",
        tokenSecret = "test-token-secret",
        playbackPolicy = "public",
        defaultSubtitleLanguage = "en",
        mp4Support = true,
    )

    @BeforeTest
    fun setup() {
        server = MockWebServer()
        server.start()
        client = MuxClient(
            httpClient = OkHttpClient(),
            baseUrl = server.url("/").toString().trimEnd('/'),
            json = Json { ignoreUnknownKeys = true },
        )
    }

    @AfterTest
    fun tearDown() {
        server.close()
    }

    @Test
    fun `createDirectUpload returns id and signed url on success`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(201)
                .addHeader("Content-Type", "application/json")
                .body(
                    """{"data":{"id":"upload-abc123","url":"https://storage.googleapis.com/mux-signed/put","status":"waiting"}}"""
                )
                .build()
        )

        val result = client.createDirectUpload(config)

        assertEquals("upload-abc123", result.id)
        assertEquals("https://storage.googleapis.com/mux-signed/put", result.url)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.url.encodedPath.endsWith("/video/v1/uploads"))

        val body = request.body!!.utf8()
        assertTrue(body.contains("\"cors_origin\":\"*\""))
        assertTrue(body.contains("\"new_asset_settings\""))
        assertTrue(body.contains("\"playback_policies\":[\"public\"]"))
        assertTrue(body.contains("\"mp4_support\":\"capped-1080p\""))
        assertTrue(body.contains("\"generated_subtitles\""))
        assertTrue(body.contains("\"language_code\":\"en\""))
    }

    @Test
    fun `createDirectUpload includes meta with title and external_id`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(201)
                .addHeader("Content-Type", "application/json")
                .body("""{"data":{"id":"upload-with-meta","url":"https://put.example","status":"waiting"}}""")
                .build()
        )

        val meta = AssetMeta(externalId = "ext-123", title = "My Video Title")
        val result = client.createDirectUpload(config, passthrough = "pass-123", meta = meta)

        assertEquals("upload-with-meta", result.id)

        val body = server.takeRequest().body!!.utf8()
        assertTrue(body.contains("\"passthrough\":\"pass-123\""))
        assertTrue(body.contains("\"meta\""))
        assertTrue(body.contains("\"external_id\":\"ext-123\""))
        assertTrue(body.contains("\"title\":\"My Video Title\""))
        assertFalse(body.contains("asset_title"))
    }

    @Test
    fun `createDirectUpload omits subtitles when language is null`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(201)
                .addHeader("Content-Type", "application/json")
                .body("""{"data":{"id":"upload-no-subs","url":"https://put.example","status":"waiting"}}""")
                .build()
        )

        val noSubsConfig = config.copy(defaultSubtitleLanguage = null)
        client.createDirectUpload(noSubsConfig)

        val body = server.takeRequest().body!!.utf8()
        assertTrue(!body.contains("generated_subtitles") || body.contains("\"generated_subtitles\":null"))
    }

    @Test
    fun `createDirectUpload omits mp4Support when disabled`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(201)
                .addHeader("Content-Type", "application/json")
                .body("""{"data":{"id":"upload-no-mp4","url":"https://put.example","status":"waiting"}}""")
                .build()
        )

        val noMp4Config = config.copy(mp4Support = false)
        client.createDirectUpload(noMp4Config)

        val body = server.takeRequest().body!!.utf8()
        assertTrue(!body.contains("mp4_support") || body.contains("\"mp4_support\":null"))
    }

    @Test
    fun `createDirectUpload throws on API error`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(422)
                .addHeader("Content-Type", "application/json")
                .body("""{"error":{"type":"invalid_parameters","messages":["Invalid settings"]}}""")
                .build()
        )

        val ex = assertFailsWith<MuxApiException> {
            client.createDirectUpload(config)
        }
        assertTrue(ex.message!!.contains("422"))
    }

    @Test
    fun `getUpload maps status and asset id`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(
                    """{"data":{"id":"upload-xyz","status":"asset_created","asset_id":"asset-789"}}"""
                )
                .build()
        )

        val status = client.getUpload(config, "upload-xyz")

        assertEquals("asset_created", status.status)
        assertEquals("asset-789", status.assetId)
        assertNull(status.errorMessage)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertTrue(request.url.encodedPath.endsWith("/video/v1/uploads/upload-xyz"))
    }

    @Test
    fun `getUpload returns null asset id while waiting`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("""{"data":{"id":"upload-xyz","status":"waiting"}}""")
                .build()
        )

        val status = client.getUpload(config, "upload-xyz")

        assertEquals("waiting", status.status)
        assertNull(status.assetId)
    }

    @Test
    fun `getUpload surfaces error message on failure status`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(
                    """{"data":{"id":"upload-xyz","status":"errored","error":{"type":"bad_input","message":"not a video"}}}"""
                )
                .build()
        )

        val status = client.getUpload(config, "upload-xyz")

        assertEquals("errored", status.status)
        assertEquals("not a video", status.errorMessage)
    }

    @Test
    fun `getUpload throws on API error`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(404)
                .addHeader("Content-Type", "application/json")
                .body("""{"error":{"type":"not_found"}}""")
                .build()
        )

        assertFailsWith<MuxApiException> {
            client.getUpload(config, "missing-id")
        }
    }

    @Test
    fun `getResumableUploadOffset returns next byte after Range header`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(308)
                .addHeader("Range", "bytes=0-1048575")
                .build()
        )

        val offset = client.getResumableUploadOffset(
            uploadUrl = server.url("/gcs-resumable").toString(),
            totalLength = 10_000_000L,
        )

        assertEquals(1_048_576L, offset)

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertTrue(request.url.encodedPath.endsWith("/gcs-resumable"))
        assertEquals("bytes */10000000", request.headers["Content-Range"])
        assertEquals("0", request.headers["Content-Length"])
    }

    @Test
    fun `getResumableUploadOffset returns zero when Range header is absent`() = runTest {
        server.enqueue(MockResponse.Builder().code(308).build())

        val offset = client.getResumableUploadOffset(
            uploadUrl = server.url("/gcs-resumable").toString(),
            totalLength = 10_000_000L,
        )

        assertEquals(0L, offset)
    }

    @Test
    fun `getResumableUploadOffset returns total when server reports complete`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).build())

        val offset = client.getResumableUploadOffset(
            uploadUrl = server.url("/gcs-resumable").toString(),
            totalLength = 10_000_000L,
        )

        assertEquals(10_000_000L, offset)
    }

    @Test
    fun `getResumableUploadOffset throws on unexpected status`() = runTest {
        server.enqueue(MockResponse.Builder().code(410).body("Gone").build())

        assertFailsWith<MuxApiException> {
            client.getResumableUploadOffset(
                uploadUrl = server.url("/gcs-resumable").toString(),
                totalLength = 10_000_000L,
            )
        }
    }

    @Test
    fun `putResumableUploadChunk streams bytes and returns new offset on 308`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(308)
                .addHeader("Range", "bytes=0-262143")
                .build()
        )

        // 256 KiB to satisfy GCS chunk-alignment for non-final chunks.
        val payload = ByteArray(262_144) { (it % 256).toByte() }
        val newOffset = client.putResumableUploadChunk(
            uploadUrl = server.url("/gcs-resumable").toString(),
            offset = 0L,
            chunkLength = payload.size.toLong(),
            totalLength = 10_000_000L,
            inputStream = payload.inputStream(),
        )

        assertEquals(262_144L, newOffset)

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertTrue(request.url.encodedPath.endsWith("/gcs-resumable"))
        assertEquals("application/octet-stream", request.headers["Content-Type"])
        assertEquals("bytes 0-262143/10000000", request.headers["Content-Range"])
        assertEquals(payload.size.toLong().toString(), request.headers["Content-Length"])
    }

    @Test
    fun `putResumableUploadChunk returns total length on 200 final chunk`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).build())

        val payload = "tail".toByteArray()
        val newOffset = client.putResumableUploadChunk(
            uploadUrl = server.url("/gcs-resumable").toString(),
            offset = 9_999_996L,
            chunkLength = payload.size.toLong(),
            totalLength = 10_000_000L,
            inputStream = payload.inputStream(),
        )

        assertEquals(10_000_000L, newOffset)

        val request = server.takeRequest()
        assertEquals("bytes 9999996-9999999/10000000", request.headers["Content-Range"])
    }

    @Test
    fun `putResumableUploadChunk throws on 4xx error`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(400)
                .body("invalid range")
                .build()
        )

        val payload = "x".toByteArray()
        val ex = assertFailsWith<MuxApiException> {
            client.putResumableUploadChunk(
                uploadUrl = server.url("/gcs-resumable").toString(),
                offset = 0L,
                chunkLength = payload.size.toLong(),
                totalLength = 10_000_000L,
                inputStream = payload.inputStream(),
            )
        }
        assertTrue(ex.message!!.contains("400"))
    }

    @Test
    fun `getAssetStatus maps all fields correctly`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(
                    """
                    {
                      "data": {
                        "id": "asset-ready",
                        "status": "ready",
                        "duration": 120.5,
                        "resolution_tier": "1080p",
                        "max_stored_resolution": "HD",
                        "max_stored_frame_rate": 29.97,
                        "aspect_ratio": "16:9",
                        "encoding_tier": "smart",
                        "video_quality": "plus",
                        "playback_ids": [
                          {"id": "playback-001", "policy": "public"}
                        ],
                        "tracks": [
                          {"id": "video-track", "type": "video", "max_width": 1920, "max_height": 1080, "max_frame_rate": 29.97, "duration": 120.5, "status": "ready"},
                          {"id": "text-track", "type": "text", "language_code": "en", "name": "English (auto)", "status": "ready"}
                        ],
                        "static_renditions": {
                          "status": "ready",
                          "files": [
                            {"name": "high.mp4", "status": "ready"}
                          ]
                        }
                      }
                    }
                    """.trimIndent()
                )
                .build()
        )

        val status = client.getAssetStatus(config, "asset-ready")

        assertEquals("ready", status.status)
        assertEquals("asset-ready", status.assetId)
        assertEquals(120.5, status.duration)
        assertEquals("1080p", status.resolutionTier)
        assertEquals("HD", status.maxStoredResolution)
        assertEquals(29.97, status.maxStoredFrameRate)
        assertEquals("16:9", status.aspectRatio)
        assertEquals("smart", status.encodingTier)
        assertEquals("plus", status.videoQuality)
        assertEquals(1, status.playbackIds.size)
        assertEquals("playback-001", status.playbackIds[0].id)
        assertEquals("public", status.playbackIds[0].policy)
        assertEquals(2, status.tracks.size)
        assertEquals("video", status.tracks[0].type)
        assertEquals(1920, status.tracks[0].maxWidth)
        assertEquals(1080, status.tracks[0].maxHeight)
        assertEquals(29.97, status.tracks[0].maxFrameRate)
        assertEquals(120.5, status.tracks[0].duration)
        assertEquals("text", status.tracks[1].type)
        assertEquals("en", status.tracks[1].languageCode)
        assertEquals("English (auto)", status.tracks[1].name)
        assertEquals(1, status.staticRenditions.size)
        assertEquals("high.mp4", status.staticRenditions[0].name)
        assertEquals("ready", status.staticRenditions[0].status)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertTrue(request.url.encodedPath.endsWith("/video/v1/assets/asset-ready"))
    }

    @Test
    fun `getAssetStatus handles null optional fields`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("""{"data":{"id":"asset-preparing","status":"preparing"}}""")
                .build()
        )

        val status = client.getAssetStatus(config, "asset-preparing")

        assertEquals("preparing", status.status)
        assertNull(status.duration)
        assertNull(status.resolutionTier)
        assertNull(status.maxStoredResolution)
        assertNull(status.maxStoredFrameRate)
        assertNull(status.aspectRatio)
        assertNull(status.encodingTier)
        assertNull(status.videoQuality)
        assertTrue(status.playbackIds.isEmpty())
        assertTrue(status.tracks.isEmpty())
        assertTrue(status.staticRenditions.isEmpty())
    }

    @Test
    fun `getAssetStatus throws on API error`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(404)
                .addHeader("Content-Type", "application/json")
                .body("""{"error":{"type":"not_found","messages":["Asset not found"]}}""")
                .build()
        )

        assertFailsWith<MuxApiException> {
            client.getAssetStatus(config, "nonexistent-id")
        }
    }

    @Test
    fun `deleteAsset succeeds on 204`() = runTest {
        server.enqueue(MockResponse.Builder().code(204).build())

        client.deleteAsset(config, "asset-to-delete")

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertTrue(request.url.encodedPath.endsWith("/video/v1/assets/asset-to-delete"))
    }

    @Test
    fun `deleteAsset ignores 404`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(404)
                .addHeader("Content-Type", "application/json")
                .body("""{"error":{"type":"not_found"}}""")
                .build()
        )

        // Should not throw
        client.deleteAsset(config, "already-deleted")
    }

    @Test
    fun `deleteAsset throws on non-404 error`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(500)
                .addHeader("Content-Type", "application/json")
                .body("""{"error":{"type":"server_error"}}""")
                .build()
        )

        assertFailsWith<MuxApiException> {
            client.deleteAsset(config, "failing-asset")
        }
    }

    @Test
    fun `requests include basic auth header`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("""{"data":{"id":"auth-test","status":"preparing"}}""")
                .build()
        )

        client.getAssetStatus(config, "auth-test")

        val request = server.takeRequest()
        val authHeader = request.headers["Authorization"]
        assertTrue(authHeader != null && authHeader.startsWith("Basic "))
    }
}
