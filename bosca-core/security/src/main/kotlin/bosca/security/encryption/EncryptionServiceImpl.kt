package bosca.security.encryption

import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.server.BoscaApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@ServiceImplementation
class EncryptionServiceImpl(application: BoscaApplication) : EncryptionService {

    private val random = SecureRandom()

    private val key by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(application.environment.config.property("security.encryption.key").getString().toByteArray(Charsets.UTF_8))
        SecretKeySpec(hash.copyOf(32), "AES")
    }

    private fun newCypher(nonce: ByteArray, id: UUID, mode: Int): Cipher {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(128, nonce)
        cipher.init(mode, key, gcmSpec)
        // TODO: create an update that will decrypt/reencrypt the data
        // cipher.updateAAD(id.idAsBytes)
        return cipher
    }

    override suspend fun encrypt(plaintext: ByteArray, id: UUID) = withContext(Dispatchers.IO) {
        val nonce = ByteArray(12)
        random.nextBytes(nonce)
        val cipher = newCypher(nonce, id, Cipher.ENCRYPT_MODE)
        val data = cipher.doFinal(plaintext)
        EncryptionService.Encrypted(nonce = nonce, data = data)
    }

    override suspend fun decrypt(encrypted: EncryptionService.Encrypted, id: UUID): ByteArray = withContext(Dispatchers.IO) {
        val cipher = newCypher(encrypted.nonce, id, Cipher.DECRYPT_MODE)
        cipher.doFinal(encrypted.data)
    }
}
