package bosca.mux

import bosca.mux.configuration.MuxConfiguration
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MuxConfigurationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `deserializes configuration with all fields`() {
        val raw = """
            {
                "tokenId": "tok-id",
                "tokenSecret": "tok-secret",
                "playbackPolicy": "signed",
                "maxWaitSeconds": 1800,
                "defaultSubtitleLanguage": "es",
                "mp4Support": false
            }
        """.trimIndent()
        val config = json.decodeFromString<MuxConfiguration>(raw)
        assertEquals("tok-id", config.tokenId)
        assertEquals("tok-secret", config.tokenSecret)
        assertEquals("signed", config.playbackPolicy)
        assertEquals(1800L, config.maxWaitSeconds)
        assertEquals("es", config.defaultSubtitleLanguage)
        assertEquals(false, config.mp4Support)
    }

    @Test
    fun `uses defaults when optional fields omitted`() {
        val raw = """
            {
                "tokenId": "tok-id",
                "tokenSecret": "tok-secret"
            }
        """.trimIndent()
        val config = json.decodeFromString<MuxConfiguration>(raw)
        assertEquals("public", config.playbackPolicy)
        assertEquals(3600L, config.maxWaitSeconds)
        assertEquals("en", config.defaultSubtitleLanguage)
        assertTrue(config.mp4Support)
    }

    @Test
    fun `allows null subtitle language to disable transcription`() {
        val raw = """
            {
                "tokenId": "tok-id",
                "tokenSecret": "tok-secret",
                "defaultSubtitleLanguage": null
            }
        """.trimIndent()
        val config = json.decodeFromString<MuxConfiguration>(raw)
        assertNull(config.defaultSubtitleLanguage)
    }
}
