package bosca.pipelines.service

import bosca.pipelines.model.PipelineSecret
import bosca.pipelines.repository.PipelineSecretRepository
import bosca.service.annotation.ServiceImplementation
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES/GCM-encrypted node-secret store, mirroring the git-ci pipeline-secret
 * scheme. The encryption key is read once from `PIPELINE_SECRET_KEY` (base64-encoded AES key); a
 * dev-only default lets local stacks run without configuration (override in production). Each value gets
 * a fresh 12-byte IV, prepended to the ciphertext and base64-encoded for storage.
 */
@ServiceImplementation
class PipelineSecretServiceImpl(
    private val repository: PipelineSecretRepository,
) : PipelineSecretService {

    private val encryptionKey: SecretKey by lazy {
        val encoded = System.getenv("PIPELINE_SECRET_KEY") ?: DEV_DEFAULT_KEY
        SecretKeySpec(Base64.getDecoder().decode(encoded), "AES")
    }

    override suspend fun setSecret(name: String, value: String): PipelineSecret =
        repository.upsert(PipelineSecret(name = name, encryptedValue = encrypt(value)))

    override suspend fun listSecrets(): List<PipelineSecret> = repository.findAll()

    override suspend fun deleteSecret(name: String) = repository.delete(name)

    override suspend fun resolve(name: String): String? =
        repository.findByName(name)?.let { decrypt(it.encryptedValue) }

    private fun encrypt(plaintext: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray())
        return Base64.getEncoder().encodeToString(iv + ciphertext)
    }

    private fun decrypt(encrypted: String): String {
        val combined = Base64.getDecoder().decode(encrypted)
        val iv = combined.copyOfRange(0, 12)
        val ciphertext = combined.copyOfRange(12, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext))
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128

        /** Dev-only fallback AES key (base64) so a local stack runs unconfigured; override in production. */
        const val DEV_DEFAULT_KEY = "dGhpcy1pcy1hLWRldi1vbmx5LXNlY3JldC1rZXktMzI="
    }
}
