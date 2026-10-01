package bosca.kubernetes.service

import bosca.kubernetes.model.HelmRepoCredentials
import bosca.kubernetes.repository.HelmRepoCredentialRepository
import bosca.kubernetes.repository.HelmRepoCredential
import bosca.security.encryption.EncryptionService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class HelmRepoCredentialServiceTest {

    private val repository = mockk<HelmRepoCredentialRepository>()
    private val encryption = mockk<EncryptionService>()
    private val service = HelmRepoCredentialServiceImpl(repository, encryption, Json)

    @AfterTest
    fun teardown() {
        io.mockk.unmockkAll()
    }

    @Test
    fun `store encrypts both fields and preserves the existing encryption id`() = runTest {
        val id = UUID.random()
        val plaintext = slot<ByteArray>()
        val stored = slot<HelmRepoCredential>()
        val nonce = byteArrayOf(1, 2, 3)
        val ciphertext = byteArrayOf(4, 5, 6)
        coEvery { repository.get("private") } returns HelmRepoCredential("private", id, nonce, ciphertext)
        coEvery { encryption.encrypt(capture(plaintext), id) } returns
            EncryptionService.Encrypted(nonce, ciphertext)
        coEvery { repository.upsert(capture(stored)) } returns Unit

        service.store("private", HelmRepoCredentials("api_token", "bsk_secret"))

        assertEquals(id, stored.captured.id)
        assertContentEquals(nonce, stored.captured.nonce)
        assertContentEquals(ciphertext, stored.captured.data)
        assertEquals("""{"username":"api_token","password":"bsk_secret"}""", plaintext.captured.decodeToString())
    }

    @Test
    fun `load restores the encrypted username and password`() = runTest {
        val id = UUID.random()
        val nonce = byteArrayOf(7, 8)
        val ciphertext = byteArrayOf(9, 10)
        coEvery { repository.get("private") } returns HelmRepoCredential("private", id, nonce, ciphertext)
        coEvery {
            encryption.decrypt(
                match { it.nonce.contentEquals(nonce) && it.data.contentEquals(ciphertext) },
                id,
            )
        } returns """{"username":"api_token","password":"bsk_secret"}""".encodeToByteArray()

        val credentials = service.load("private")

        assertEquals("api_token", credentials?.username)
        assertEquals("bsk_secret", credentials?.password)
    }

    @Test
    fun `load returns null without touching encryption for a public repository`() = runTest {
        coEvery { repository.get("public") } returns null

        assertNull(service.load("public"))
        coVerify(exactly = 0) { encryption.decrypt(any(), any()) }
    }

    @Test
    fun `store generates a new encryption id for a new private repository`() = runTest {
        val returned = EncryptionService.Encrypted(byteArrayOf(1), byteArrayOf(2))
        val stored = slot<HelmRepoCredential>()
        coEvery { repository.get("new") } returns null
        coEvery { encryption.encrypt(any(), any()) } returns returned
        coEvery { repository.upsert(capture(stored)) } returns Unit

        service.store("new", HelmRepoCredentials("user", "password"))

        coVerify { encryption.encrypt(any(), stored.captured.id) }
    }
}
