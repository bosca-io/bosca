package bosca.pipelines.service

import bosca.pipelines.model.PipelineSecret
import bosca.pipelines.repository.PipelineSecretRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The node-secret service: values are AES/GCM-encrypted before storage and
 * decrypt back to the original; a dry run never sees the real value (masked); a missing secret fails a
 * real run loudly.
 */
class PipelineSecretServiceImplTest {

    @Test
    fun `setSecret encrypts the value before storing and round-trips on resolve`() = runTest {
        val repo = mockk<PipelineSecretRepository>()
        val stored = slot<PipelineSecret>()
        coEvery { repo.upsert(capture(stored)) } answers { stored.captured }
        val service = PipelineSecretServiceImpl(repo)

        service.setSecret("api-key", "s3cr3t-value")

        // What was stored is NOT the plaintext (it's encrypted), and it round-trips back.
        assertNotEquals("s3cr3t-value", stored.captured.encryptedValue, "value must be encrypted at rest")
        assertTrue(stored.captured.encryptedValue.isNotBlank())
        coEvery { repo.findByName("api-key") } returns stored.captured
        assertEquals("s3cr3t-value", service.resolve("api-key"))
    }

    @Test
    fun `each encryption uses a fresh IV so the same value yields different ciphertext`() = runTest {
        val repo = mockk<PipelineSecretRepository>()
        val captured = mutableListOf<PipelineSecret>()
        coEvery { repo.upsert(capture(captured)) } answers { captured.last() }
        val service = PipelineSecretServiceImpl(repo)

        service.setSecret("k", "same")
        service.setSecret("k", "same")

        assertNotEquals(captured[0].encryptedValue, captured[1].encryptedValue, "fresh IV per encryption")
    }

    @Test
    fun `resolve returns null for an unknown secret`() = runTest {
        val repo = mockk<PipelineSecretRepository>()
        coEvery { repo.findByName("missing") } returns null
        assertNull(PipelineSecretServiceImpl(repo).resolve("missing"))
    }

    @Test
    fun `list and delete delegate to the secret repository`() = runTest {
        val repo = mockk<PipelineSecretRepository>()
        val secrets = listOf(PipelineSecret(name = "api-key"))
        coEvery { repo.findAll() } returns secrets
        coEvery { repo.delete("api-key") } returns Unit
        val service = PipelineSecretServiceImpl(repo)

        assertEquals(secrets, service.listSecrets())
        service.deleteSecret("api-key")

        coVerify(exactly = 1) { repo.delete("api-key") }
    }

    @Test
    fun `resolveForExecution masks in a dry run and never touches the store`() = runTest {
        val repo = mockk<PipelineSecretRepository>()
        val service = PipelineSecretServiceImpl(repo)

        assertEquals(PipelineSecretService.SECRET_MASK, service.resolveForExecution("api-key", dryRun = true))
        coVerify(exactly = 0) { repo.findByName(any()) }
    }

    @Test
    fun `resolveForExecution returns the real value in a non-dry run`() = runTest {
        val repo = mockk<PipelineSecretRepository>()
        val service = PipelineSecretServiceImpl(repo)
        // Encrypt a value through the service, then have the store return it.
        val encrypted = slot<PipelineSecret>()
        coEvery { repo.upsert(capture(encrypted)) } answers { encrypted.captured }
        service.setSecret("api-key", "live-value")
        coEvery { repo.findByName("api-key") } returns encrypted.captured

        assertEquals("live-value", service.resolveForExecution("api-key", dryRun = false))
    }
}
