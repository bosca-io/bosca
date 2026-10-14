package bosca.bible

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class BibleAudioSerializationTest {

    @Test
    fun materializationCapturesNestedAudioProjections() {
        var copyright = "Original copyright"
        var end = 4.5
        val attribution = object : IBibleAttribution {
            override val copyright: String get() = copyright
            override val citation = "Citation"
            override val informationUrl: String? = null
        }
        val timecodes = mutableListOf<IBibleAudioTimecode>(object : IBibleAudioTimecode {
            override val usfm = "GEN.1.1"
            override val start = 0.0
            override val end: Double get() = end
        })
        val content = object : IBibleAudioContent {
            override val contentType = "audio/mpeg"
            override val contentLength = 100L
            override val timecodes = timecodes
        }
        val audio = object : IBibleAudio {
            override val id = Uuid.parse("c4b81276-d7b0-4270-9834-5d26edaf0022")
            override val name = "Example recording"
            override val nameLocal = name
            override val abbreviation = "EX"
            override val attribution = attribution
        }
        val chapter = object : IBibleAudioChapter {
            override val audio = audio
            override val usfm = "GEN.1"
            override val url = "/bible/audio/GEN.1"
            override val content = content
            override val attribution = attribution
        }

        val snapshot = chapter.asSerializable()
        copyright = "Changed copyright"
        end = 9.0
        timecodes.clear()
        val restored = bibleJson.decodeFromString(
            BibleAudioChapter.serializer(),
            bibleJson.encodeToString(BibleAudioChapter.serializer(), snapshot),
        )

        assertEquals(snapshot, restored)
        assertEquals("Original copyright", restored.audio.attribution.copyright)
        assertEquals("Original copyright", restored.attribution.copyright)
        assertEquals(listOf(BibleAudioTimecode("GEN.1.1", 0.0, 4.5)), restored.content.timecodes)
        assertEquals("Changed copyright", chapter.attribution.copyright)
    }
}
