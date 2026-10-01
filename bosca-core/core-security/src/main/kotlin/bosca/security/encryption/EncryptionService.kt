package bosca.security.encryption

import bosca.serialization.UUID
import bosca.service.Service

/**
 * Service for symmetric encryption and decryption of binary data.
 *
 * Implementations use authenticated encryption with a nonce to ensure both confidentiality
 * and integrity. The [id] parameter allows implementations to derive or select
 * encryption keys on a per-entity basis.
 */
interface EncryptionService : Service {

    /**
     * Encrypts the given plaintext bytes, producing an [Encrypted] result containing
     * the ciphertext and the nonce used during encryption.
     *
     * @param plaintext the raw bytes to encrypt
     * @param id a UUID used to derive or look up the encryption key for this operation
     * @return an [Encrypted] object containing the nonce and encrypted data
     */
    suspend fun encrypt(plaintext: ByteArray, id: UUID): Encrypted

    /**
     * Decrypts a previously encrypted payload back to its original plaintext bytes.
     *
     * @param encrypted the [Encrypted] object containing the nonce and ciphertext
     * @param id the same UUID that was used during encryption, to derive or look up the decryption key
     * @return the original plaintext bytes
     */
    suspend fun decrypt(encrypted: Encrypted, id: UUID): ByteArray

    /**
     * Holds the result of an encryption operation, pairing the generated nonce
     * with the encrypted ciphertext.
     *
     * @property nonce the unique nonce (initialization vector) used during encryption
     * @property data the encrypted ciphertext bytes
     */
    class Encrypted(val nonce: ByteArray, val data: ByteArray)
}