package bosca.content.metadata.graphql

import bosca.content.metadata.model.MediaHls
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaHlsControllerCoverageTest {

    private val controller = MediaHlsController()

    @Test
    fun `url returns the manifest url`() {
        val data = MediaHls(url = "https://cdn.example.com/master.m3u8")

        assertEquals("https://cdn.example.com/master.m3u8", controller.url(data))
    }

    @Test
    fun `audioOnlyUrl returns the audio-only manifest url when present`() {
        val data = MediaHls(
            url = "https://cdn.example.com/master.m3u8",
            audioOnlyUrl = "https://cdn.example.com/audio.m3u8"
        )

        assertEquals("https://cdn.example.com/audio.m3u8", controller.audioOnlyUrl(data))
    }

    @Test
    fun `audioOnlyUrl returns null when absent`() {
        val data = MediaHls(url = "https://cdn.example.com/master.m3u8")

        assertNull(controller.audioOnlyUrl(data))
    }
}
