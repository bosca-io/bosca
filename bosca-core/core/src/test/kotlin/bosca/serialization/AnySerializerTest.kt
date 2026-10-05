@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package bosca.serialization

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AnySerializerTest {

    @Test
    fun `non-null Any serializer exposes descriptor and unsupported operations loudly`() {
        val serializer = AnySerializer()
        serializer.descriptor
        assertFailsWith<NotImplementedError> { serializer.serialize(mockk(relaxed = true), "value") }
        assertFailsWith<NotImplementedError> { serializer.deserialize(mockk(relaxed = true)) }
    }

    @Test
    fun `nullable Any serializer handles nulls and rejects unsupported non-null values`() {
        val serializer = AnyNullableSerializer()
        serializer.descriptor
        val encoder = mockk<Encoder>(relaxed = true)
        serializer.serialize(encoder, null)
        verify { encoder.encodeNull() }

        assertFailsWith<NotImplementedError> { serializer.serialize(encoder, "value") }
        verify { encoder.encodeNotNullMark() }

        val nullDecoder = mockk<Decoder> { every { decodeNotNullMark() } returns false }
        assertNull(serializer.deserialize(nullDecoder))
        val valueDecoder = mockk<Decoder> { every { decodeNotNullMark() } returns true }
        assertFailsWith<NotImplementedError> { serializer.deserialize(valueDecoder) }
    }
}
