package bosca.security.oauth2

import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LenientBooleanSerializerTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Holder(
        @Serializable(with = LenientBooleanSerializer::class)
        val flag: Boolean = false,
    )

    @Test
    fun `boolean true decodes to true`() {
        assertTrue(json.decodeFromString<Holder>("""{"flag": true}""").flag)
    }

    @Test
    fun `boolean false decodes to false`() {
        assertFalse(json.decodeFromString<Holder>("""{"flag": false}""").flag)
    }

    @Test
    fun `string true decodes to true`() {
        assertTrue(json.decodeFromString<Holder>("""{"flag": "true"}""").flag)
    }

    @Test
    fun `mixed-case string TRUE decodes to true`() {
        assertTrue(json.decodeFromString<Holder>("""{"flag": "TRUE"}""").flag)
    }

    @Test
    fun `string false decodes to false`() {
        assertFalse(json.decodeFromString<Holder>("""{"flag": "false"}""").flag)
    }

    @Test
    fun `arbitrary string decodes to false`() {
        assertFalse(json.decodeFromString<Holder>("""{"flag": "yes"}""").flag)
    }

    @Test
    fun `numeric value decodes to false`() {
        assertFalse(json.decodeFromString<Holder>("""{"flag": 1}""").flag)
    }

    @Test
    fun `object value and non-json decoder use safe alternatives`() {
        assertFalse(json.decodeFromString<Holder>("""{"flag": {}}""").flag)
        val decoder = mockk<Decoder> {
            every { decodeBoolean() } returns true
        }

        assertTrue(LenientBooleanSerializer.deserialize(decoder))
        assertEquals("LenientBoolean", LenientBooleanSerializer.descriptor.serialName)
    }

    @Test
    fun `absent value uses default false`() {
        assertFalse(json.decodeFromString<Holder>("""{}""").flag)
    }

    @Test
    fun `serializes back to a json boolean`() {
        assertEquals("""{"flag":true}""", json.encodeToString(Holder(flag = true)))
    }
}
