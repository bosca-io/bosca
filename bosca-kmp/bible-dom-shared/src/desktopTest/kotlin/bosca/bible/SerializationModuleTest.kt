package bosca.bible

import bosca.bible.components.IComponent
import bosca.bible.components.Text
import bosca.bible.components.VerseEnd
import bosca.bible.components.VerseStart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SerializationModuleTest {

    @Test
    fun `bibleJson is configured with class discriminator underscore`() {
        // Verify it can serialize and deserialize with the "_" discriminator
        val text = Text("Hello", null)
        val json = bibleJson.encodeToString(kotlinx.serialization.serializer<IComponent>(), text)
        assertTrue(json.contains("\"_\""))
    }

    @Test
    fun `serialize and deserialize Text component`() {
        val original = Text("Test verse", null)
        val json = bibleJson.encodeToString(kotlinx.serialization.serializer<IComponent>(), original)
        val deserialized = bibleJson.decodeFromString(kotlinx.serialization.serializer<IComponent>(), json)
        assertIs<Text>(deserialized)
        assertEquals("Test verse", deserialized.text)
    }

    @Test
    fun `serialize and deserialize VerseStart component`() {
        val original = VerseStart(Reference("GEN.1.1"))
        val json = bibleJson.encodeToString(kotlinx.serialization.serializer<IComponent>(), original)
        val deserialized = bibleJson.decodeFromString(kotlinx.serialization.serializer<IComponent>(), json)
        assertIs<VerseStart>(deserialized)
        assertEquals("GEN.1.1", deserialized.reference.usfm)
    }

    @Test
    fun `serialize and deserialize VerseEnd component`() {
        val original = VerseEnd()
        val json = bibleJson.encodeToString(kotlinx.serialization.serializer<IComponent>(), original)
        val deserialized = bibleJson.decodeFromString(kotlinx.serialization.serializer<IComponent>(), json)
        assertIs<VerseEnd>(deserialized)
    }

    @Test
    fun `BibleSerializers module is not null`() {
        assertNotNull(BibleSerializers)
    }

    @Test
    fun `bibleJson instance is not null`() {
        assertNotNull(bibleJson)
    }

    @Test
    fun `round-trip serialization preserves text content`() {
        val original = Text("In the beginning God created the heavens and the earth.", null)
        val json = bibleJson.encodeToString(kotlinx.serialization.serializer<IComponent>(), original)
        val restored = bibleJson.decodeFromString(kotlinx.serialization.serializer<IComponent>(), json)
        assertIs<Text>(restored)
        assertEquals(original.text, restored.text)
    }
}
