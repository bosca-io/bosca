package bosca.content.metadata.graphql

import bosca.content.metadata.model.Media
import bosca.content.metadata.model.MediaConstants
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaControllerCoverageTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val controller = MediaController(json)

    @Test
    fun `status returns media status`() {
        val media = Media(status = "ready")

        assertEquals("ready", controller.status(media))
    }

    @Test
    fun `hls returns null when hlsUrl is null`() {
        val media = Media(status = "preparing", hlsUrl = null)

        assertNull(controller.hls(media))
    }

    @Test
    fun `hls returns MediaHls with audio only when hlsUrl present`() {
        val media = Media(
            status = "ready",
            hlsUrl = "https://example.com/video.m3u8",
            hlsAudioOnlyUrl = "https://example.com/audio.m3u8",
        )

        val result = controller.hls(media)

        assertEquals("https://example.com/video.m3u8", result?.url)
        assertEquals("https://example.com/audio.m3u8", result?.audioOnlyUrl)
    }

    @Test
    fun `hls returns MediaHls with null audio only when absent`() {
        val media = Media(
            status = "ready",
            hlsUrl = "https://example.com/video.m3u8",
            hlsAudioOnlyUrl = null,
        )

        val result = controller.hls(media)

        assertEquals("https://example.com/video.m3u8", result?.url)
        assertNull(result?.audioOnlyUrl)
    }

    @Test
    fun `downloadUrl returns value and null`() {
        assertEquals("https://d.example/file.mp4", controller.downloadUrl(Media(status = "ready", downloadUrl = "https://d.example/file.mp4")))
        assertNull(controller.downloadUrl(Media(status = "ready", downloadUrl = null)))
    }

    @Test
    fun `thumbnailUrl returns value and null`() {
        assertEquals("https://t.example/thumb.png", controller.thumbnailUrl(Media(status = "ready", thumbnailUrl = "https://t.example/thumb.png")))
        assertNull(controller.thumbnailUrl(Media(status = "ready", thumbnailUrl = null)))
    }

    @Test
    fun `animatedPreviewUrl returns value and null`() {
        assertEquals("https://a.example/preview.gif", controller.animatedPreviewUrl(Media(status = "ready", animatedPreviewUrl = "https://a.example/preview.gif")))
        assertNull(controller.animatedPreviewUrl(Media(status = "ready", animatedPreviewUrl = null)))
    }

    @Test
    fun `durationSeconds returns value and null`() {
        assertEquals(12.5, controller.durationSeconds(Media(status = "ready", durationSeconds = 12.5)))
        assertNull(controller.durationSeconds(Media(status = "ready", durationSeconds = null)))
    }

    @Test
    fun `maxResolution returns value and null`() {
        assertEquals("1080p", controller.maxResolution(Media(status = "ready", maxResolution = "1080p")))
        assertNull(controller.maxResolution(Media(status = "ready", maxResolution = null)))
    }

    @Test
    fun `aspectRatio returns value and null`() {
        assertEquals("16:9", controller.aspectRatio(Media(status = "ready", aspectRatio = "16:9")))
        assertNull(controller.aspectRatio(Media(status = "ready", aspectRatio = null)))
    }

    @Test
    fun `actualVideoQuality returns value and null`() {
        assertEquals("premium", controller.actualVideoQuality(Media(status = "ready", actualVideoQuality = "premium")))
        assertNull(controller.actualVideoQuality(Media(status = "ready", actualVideoQuality = null)))
    }

    @Test
    fun `videoQuality returns content when providerAttributes is JsonObject with key`() {
        val media = Media(
            status = "ready",
            providerAttributes = buildJsonObject {
                put(MediaConstants.ATTR_VIDEO_QUALITY, "plus")
            },
        )

        assertEquals("plus", controller.videoQuality(media))
    }

    @Test
    fun `videoQuality returns null when key missing from JsonObject`() {
        val media = Media(
            status = "ready",
            providerAttributes = buildJsonObject {
                put("unrelated", "value")
            },
        )

        assertNull(controller.videoQuality(media))
    }

    @Test
    fun `videoQuality returns null when providerAttributes is not a JsonObject`() {
        val media = Media(
            status = "ready",
            providerAttributes = JsonArray(listOf(JsonPrimitive("x"))),
        )

        assertNull(controller.videoQuality(media))
    }

    @Test
    fun `maxResolutionTier returns content when providerAttributes is JsonObject with key`() {
        val media = Media(
            status = "ready",
            providerAttributes = buildJsonObject {
                put(MediaConstants.ATTR_MAX_RESOLUTION_TIER, "1080p")
            },
        )

        assertEquals("1080p", controller.maxResolutionTier(media))
    }

    @Test
    fun `maxResolutionTier returns null when key missing from JsonObject`() {
        val media = Media(
            status = "ready",
            providerAttributes = JsonObject(emptyMap()),
        )

        assertNull(controller.maxResolutionTier(media))
    }

    @Test
    fun `maxResolutionTier returns null when providerAttributes is not a JsonObject`() {
        val media = Media(
            status = "ready",
            providerAttributes = JsonPrimitive("not-an-object"),
        )

        assertNull(controller.maxResolutionTier(media))
    }

    @Test
    fun `transcriptions decodes a valid list`() {
        val media = Media(
            status = "ready",
            transcriptions = buildJsonArray {
                add(
                    buildJsonObject {
                        put("id", "t1")
                        put("languageCode", "en")
                        put("name", "English (auto)")
                        put("status", "ready")
                        put("textUrl", "https://example/text.txt")
                        put("vttUrl", "https://example/subs.vtt")
                    },
                )
            },
        )

        val result = controller.transcriptions(media)

        assertEquals(1, result.size)
        val track = result.first()
        assertEquals("t1", track.id)
        assertEquals("en", track.languageCode)
        assertEquals("English (auto)", track.name)
        assertEquals("ready", track.status)
        assertEquals("https://example/text.txt", track.textUrl)
        assertEquals("https://example/subs.vtt", track.vttUrl)
    }

    @Test
    fun `transcriptions returns empty for the default empty array`() {
        val media = Media(status = "ready")

        assertTrue(controller.transcriptions(media).isEmpty())
    }

    @Test
    fun `transcriptions returns empty list when decode throws`() {
        // A JsonObject (not the expected List) forces decodeFromJsonElement to throw,
        // exercising the catch arm.
        val media = Media(
            status = "ready",
            transcriptions = buildJsonObject {
                put("not", "a-list")
            },
        )

        assertTrue(controller.transcriptions(media).isEmpty())
    }
}
