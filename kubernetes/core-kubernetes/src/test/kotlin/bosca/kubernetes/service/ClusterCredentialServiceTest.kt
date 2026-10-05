package bosca.kubernetes.service

import bosca.kubernetes.repository.ClusterCredentialRepository
import bosca.kubernetes.repository.ClusterCredential
import bosca.security.encryption.EncryptionService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Tests the kubeconfig encrypt/store/load round-trip — the only credential
 * surface that touches plaintext kubeconfigs. The contract pinned here
 * underpins K8S-17's "kubeconfigs never on disk" invariant: the service
 * is the only path through which decrypted bytes flow, and it
 * deliberately returns them as a Java String, not a file path.
 *
 * The encryption service itself is mocked — kubeconfig material round-
 * trips through a fake encrypt/decrypt that XORs against the cluster id
 * so the test pins:
 *   * `store` calls encrypt with the kubeconfig bytes and cluster id, then
 *     upserts a row carrying the resulting nonce + ciphertext.
 *   * `load` reads the row, decrypts with the same cluster id, and
 *     returns the decoded String.
 *   * Missing row → load returns null without calling decrypt.
 */
@OptIn(ExperimentalUuidApi::class)
class ClusterCredentialServiceTest {

    private val repository = mockk<ClusterCredentialRepository>(relaxed = true)
    private val encryption = mockk<EncryptionService>()

    private val service = ClusterCredentialServiceImpl(repository, encryption)
    private val clusterId = UUID.random()

    @AfterTest
    fun teardown() { io.mockk.unmockkAll() }

    @Test
    fun `store encrypts the kubeconfig bytes with the cluster id and upserts the row`() = runTest {
        val plaintext = "apiVersion: v1\nkind: Config\n"
        val nonce = byteArrayOf(1, 2, 3)
        val ciphertext = byteArrayOf(4, 5, 6, 7)
        val encryptedSlot = slot<ByteArray>()
        coEvery { encryption.encrypt(capture(encryptedSlot), clusterId) } returns
            EncryptionService.Encrypted(nonce, ciphertext)

        service.store(clusterId, plaintext)

        assertEquals(plaintext, encryptedSlot.captured.decodeToString(),
            "store must pass the kubeconfig bytes verbatim to encryption.encrypt")

        val rowSlot = slot<ClusterCredential>()
        coVerify { repository.upsert(capture(rowSlot)) }
        assertEquals(clusterId, rowSlot.captured.clusterId)
        assertTrue(nonce.contentEquals(rowSlot.captured.nonce))
        assertTrue(ciphertext.contentEquals(rowSlot.captured.data))
    }

    @Test
    fun `load returns the decoded kubeconfig string when a row exists`() = runTest {
        val row = ClusterCredential(clusterId, byteArrayOf(1), byteArrayOf(2, 3))
        coEvery { repository.get(clusterId) } returns row
        coEvery {
            encryption.decrypt(match { it.nonce.contentEquals(row.nonce) && it.data.contentEquals(row.data) }, clusterId)
        } returns "decrypted-kubeconfig".encodeToByteArray()

        assertEquals("decrypted-kubeconfig", service.load(clusterId))
    }

    @Test
    fun `load returns null when no row exists and does NOT touch encryption`() = runTest {
        coEvery { repository.get(clusterId) } returns null

        assertNull(service.load(clusterId))

        coVerify(exactly = 0) { encryption.decrypt(any(), any()) }
    }
}
