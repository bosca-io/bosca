package bosca.content.metadata.graphql

import bosca.content.metadata.model.Transcription
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TranscriptionControllerCoverageTest {

    private val controller = TranscriptionController()

    @Test
    fun `resolvers return backing fields when all populated`() {
        val data = Transcription(
            id = "track-1",
            languageCode = "en",
            name = "English (auto)",
            status = "ready",
            textUrl = "https://example.com/track.txt",
            vttUrl = "https://example.com/track.vtt",
        )

        assertEquals("track-1", controller.id(data))
        assertEquals("en", controller.languageCode(data))
        assertEquals("English (auto)", controller.name(data))
        assertEquals("ready", controller.status(data))
        assertEquals("https://example.com/track.txt", controller.textUrl(data))
        assertEquals("https://example.com/track.vtt", controller.vttUrl(data))
    }

    @Test
    fun `nullable url resolvers return null when absent`() {
        val data = Transcription(
            id = "track-2",
            languageCode = "es",
            name = "Spanish",
            status = "preparing",
        )

        assertEquals("track-2", controller.id(data))
        assertEquals("es", controller.languageCode(data))
        assertEquals("Spanish", controller.name(data))
        assertEquals("preparing", controller.status(data))
        assertNull(controller.textUrl(data))
        assertNull(controller.vttUrl(data))
    }
}
