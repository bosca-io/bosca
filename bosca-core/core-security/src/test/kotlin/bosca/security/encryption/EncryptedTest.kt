package bosca.security.encryption

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class EncryptedTest {

    @Test
    fun `Encrypted stores nonce and data`() {
        val nonce = byteArrayOf(1, 2, 3, 4)
        val data = byteArrayOf(10, 20, 30)
        val encrypted = EncryptionService.Encrypted(nonce = nonce, data = data)
        assertContentEquals(nonce, encrypted.nonce)
        assertContentEquals(data, encrypted.data)
    }

    @Test
    fun `Encrypted with empty arrays`() {
        val encrypted = EncryptionService.Encrypted(nonce = byteArrayOf(), data = byteArrayOf())
        assertEquals(0, encrypted.nonce.size)
        assertEquals(0, encrypted.data.size)
    }
}
