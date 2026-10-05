package bosca.security.encryption

import io.mockk.every
import io.mockk.mockk
import org.bouncycastle.crypto.params.Argon2Parameters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Argon2EncodingUtilsTest {

    @Test
    fun `encode produces argon2id prefix for ARGON2_id type`() {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt("saltsaltsaltsalt".toByteArray())
            .withParallelism(1)
            .withMemoryAsKB(65536)
            .withIterations(3)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .build()
        val hash = ByteArray(32) { it.toByte() }
        val encoded = Argon2EncodingUtils.encode(hash, params)
        assertTrue(encoded.startsWith("\$argon2id"))
    }

    @Test
    fun `encode produces argon2i prefix for ARGON2_i type`() {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_i)
            .withSalt("saltsaltsaltsalt".toByteArray())
            .withParallelism(1)
            .withMemoryAsKB(1024)
            .withIterations(2)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .build()
        val hash = ByteArray(32) { 0 }
        val encoded = Argon2EncodingUtils.encode(hash, params)
        assertTrue(encoded.startsWith("\$argon2i"))
    }

    @Test
    fun `encode produces argon2d prefix for ARGON2_d type`() {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_d)
            .withSalt("saltsaltsaltsalt".toByteArray())
            .withParallelism(2)
            .withMemoryAsKB(2048)
            .withIterations(1)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .build()
        val hash = ByteArray(16) { 0xFF.toByte() }
        val encoded = Argon2EncodingUtils.encode(hash, params)
        assertTrue(encoded.startsWith("\$argon2d"))
    }

    @Test
    fun `encode rejects unknown algorithms and supports parameters without salt`() {
        val unknown = mockk<Argon2Parameters> {
            every { type } returns Int.MAX_VALUE
        }
        assertFailsWith<IllegalArgumentException> {
            Argon2EncodingUtils.encode(byteArrayOf(1), unknown)
        }

        val withoutSalt = mockk<Argon2Parameters> {
            every { type } returns Argon2Parameters.ARGON2_id
            every { version } returns Argon2Parameters.ARGON2_VERSION_13
            every { memory } returns 1024
            every { iterations } returns 2
            every { lanes } returns 1
            every { salt } returns null
        }
        val encoded = Argon2EncodingUtils.encode(byteArrayOf(1), withoutSalt)
        assertEquals(5, encoded.split("\$").size)
    }

    @Test
    fun `encode contains version, memory, iterations, and parallelism`() {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt("saltsaltsaltsalt".toByteArray())
            .withParallelism(4)
            .withMemoryAsKB(8192)
            .withIterations(5)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .build()
        val hash = ByteArray(32) { it.toByte() }
        val encoded = Argon2EncodingUtils.encode(hash, params)
        assertTrue(encoded.contains("v=${Argon2Parameters.ARGON2_VERSION_13}"))
        assertTrue(encoded.contains("m=8192"))
        assertTrue(encoded.contains("t=5"))
        assertTrue(encoded.contains("p=4"))
    }

    @Test
    fun `decode roundtrips with encode for argon2id`() {
        val salt = "abcdefghijklmnop".toByteArray()
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt)
            .withParallelism(2)
            .withMemoryAsKB(4096)
            .withIterations(3)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .build()
        val originalHash = ByteArray(32) { (it * 7).toByte() }
        val encoded = Argon2EncodingUtils.encode(originalHash, params)
        val decoded = Argon2EncodingUtils.decode(encoded)
        assertNotNull(decoded)
        assertEquals(originalHash.size, decoded.hash.size)
        assertTrue(originalHash.contentEquals(decoded.hash))
        assertEquals(Argon2Parameters.ARGON2_id, decoded.parameters.type)
        assertEquals(4096, decoded.parameters.memory)
        assertEquals(3, decoded.parameters.iterations)
        assertEquals(2, decoded.parameters.lanes)
    }

    @Test
    fun `decode supports argon2d argon2i and hashes without an explicit version`() {
        val salt = "c2FsdA"
        val hash = "aGFzaA"

        val decodedD = Argon2EncodingUtils.decode("\$argon2d\$m=1024,t=2,p=1\$$salt\$$hash")
        val decodedI = Argon2EncodingUtils.decode("\$argon2i\$m=1024,t=2,p=1\$$salt\$$hash")

        assertEquals(Argon2Parameters.ARGON2_d, decodedD.parameters.type)
        assertEquals(Argon2Parameters.ARGON2_i, decodedI.parameters.type)
    }

    @Test
    fun `decode throws for too few parts`() {
        assertFailsWith<IllegalArgumentException> {
            Argon2EncodingUtils.decode("invalid")
        }
    }

    @Test
    fun `decode throws for unknown algorithm type`() {
        assertFailsWith<IllegalArgumentException> {
            Argon2EncodingUtils.decode("\$argon2x\$v=19\$m=65536,t=3,p=1\$c2FsdA\$aGFzaA")
        }
    }

    @Test
    fun `decode throws for invalid performance parameters count`() {
        assertFailsWith<IllegalArgumentException> {
            Argon2EncodingUtils.decode("\$argon2id\$v=19\$m=65536,t=3\$c2FsdA\$aGFzaA")
        }
    }

    @Test
    fun `decode throws for invalid memory parameter`() {
        assertFailsWith<IllegalArgumentException> {
            Argon2EncodingUtils.decode("\$argon2id\$v=19\$x=65536,t=3,p=1\$c2FsdA\$aGFzaA")
        }
    }

    @Test
    fun `decode throws for invalid iterations parameter`() {
        assertFailsWith<IllegalArgumentException> {
            Argon2EncodingUtils.decode("\$argon2id\$v=19\$m=65536,x=3,p=1\$c2FsdA\$aGFzaA")
        }
    }

    @Test
    fun `decode throws for invalid parallelism parameter`() {
        assertFailsWith<IllegalArgumentException> {
            Argon2EncodingUtils.decode("\$argon2id\$v=19\$m=65536,t=3,x=1\$c2FsdA\$aGFzaA")
        }
    }

    @Test
    fun `Argon2Hash stores hash bytes`() {
        val hashBytes = byteArrayOf(1, 2, 3, 4, 5)
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).build()
        val argon2Hash = Argon2EncodingUtils.Argon2Hash(hashBytes, params)
        assertTrue(hashBytes.contentEquals(argon2Hash.hash))
    }

    @Test
    fun `Argon2Hash clones hash array so original mutation does not affect stored value`() {
        val hashBytes = byteArrayOf(1, 2, 3)
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).build()
        val argon2Hash = Argon2EncodingUtils.Argon2Hash(hashBytes, params)
        hashBytes[0] = 99
        assertEquals(1, argon2Hash.hash[0])
    }
}
