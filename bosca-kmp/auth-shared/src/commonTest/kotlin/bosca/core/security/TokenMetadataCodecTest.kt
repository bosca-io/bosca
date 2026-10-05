package bosca.core.security

import bosca.core.security.model.TokenMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TokenMetadataCodecTest {

    @Test
    fun encode_thenDecode_roundTrips() {
        val metadata = TokenMetadata(expiresAt = 1_700_000_000, issuedAt = 1_699_996_400)
        val decoded = TokenMetadataCodec.decode(TokenMetadataCodec.encode(metadata))
        assertEquals(metadata, decoded)
    }

    @Test
    fun decode_whenNull_returnsNull() {
        assertNull(TokenMetadataCodec.decode(null))
    }

    @Test
    fun decode_whenBlank_returnsNull() {
        assertNull(TokenMetadataCodec.decode("   "))
    }

    @Test
    fun decode_whenMalformedJson_returnsNull() {
        assertNull(TokenMetadataCodec.decode("{not valid json"))
    }

    @Test
    fun decode_whenRequiredFieldsMissing_returnsNull() {
        // Unknown keys are ignored, but the required Int fields are absent.
        assertNull(TokenMetadataCodec.decode("""{"foo":1}"""))
    }
}
