package bosca.nats

import io.nats.client.NKey
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NKeyProviderCompatibilityTest {
    @Test
    fun `platform crypto provider supports NATS key signing and restoration`() {
        val key = NKey.createUser(SecureRandom())
        val message = "bosca-nkey-provider-check".toByteArray()
        val signature = key.sign(message)
        assertTrue(key.verify(message, signature))
        val restored = NKey.fromSeed(key.seed)
        assertContentEquals(signature, restored.sign(message))
        val publicKey = NKey.fromPublicKey(key.publicKey)
        assertTrue(publicKey.verify(message, signature))
        message[0] = (message[0].toInt() xor 1).toByte()
        assertFalse(publicKey.verify(message, signature))
    }
}
