package bosca.git.ci.service

import bosca.git.ci.repository.PipelineSecretRepository
import bosca.security.service.AuthenticationContext
import bosca.security.service.impersonate
import bosca.git.model.PipelineSecret
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PipelineSecretServiceImplTest {

    private val secretRepository = mockk<PipelineSecretRepository>(relaxed = true)
    private val permissionRepository =
        mockk<bosca.git.ci.repository.PipelineSecretPermissionRepository>(relaxed = true)
    private val securityService = mockk<bosca.security.service.SecurityService>(relaxed = true)
    private val evaluator = mockk<bosca.git.ci.security.PipelineSecretPermissionEvaluator>()
    private lateinit var service: PipelineSecretServiceImpl

    private val repoId = UUID.random()

    @kotlin.test.AfterTest
    fun teardown() {
        // mockkStatic is JVM-global: leaving it mocked breaks every later test that calls
        // SecurityService.impersonate in this test JVM.
        io.mockk.unmockkStatic("bosca.security.service.SecurityServiceKt")
    }

    @BeforeTest
    fun setup() {
        coEvery { secretRepository.upsert(any()) } answers { firstArg() }
        coEvery { permissionRepository.findBySecretId(any()) } returns emptyList()
        service = PipelineSecretServiceImpl(
            secretRepository,
            permissionRepository,
            mockk(relaxed = true),
            mockk(relaxed = true),
            securityService,
        )
        bosca.di.provides<bosca.git.ci.security.PipelineSecretPermissionEvaluator>(singleton = true) { evaluator }
        io.mockk.mockkStatic("bosca.security.service.SecurityServiceKt")
        coEvery { securityService.impersonate(any<UUID>()) } returns
            mockk<bosca.security.service.ImpersonatedAuthenticationContext>()
    }

    @Test
    fun `setSecret encrypts value before storing`() = runTest {
        val captured = slot<PipelineSecret>()
        coEvery { secretRepository.upsert(capture(captured)) } answers { captured.captured }

        service.setSecret(repoId, "API_KEY", "secret-value-123")

        assertEquals("API_KEY", captured.captured.name)
        assertEquals(repoId, captured.captured.repositoryId)
        assertNotEquals("secret-value-123", captured.captured.encryptedValue)
        assertTrue(captured.captured.encryptedValue.isNotBlank())
    }

    @Test
    fun `setSecret produces different ciphertext for same plaintext`() = runTest {
        val ciphertexts = mutableSetOf<String>()
        val captured = slot<PipelineSecret>()
        coEvery { secretRepository.upsert(capture(captured)) } answers { captured.captured }

        repeat(5) {
            service.setSecret(repoId, "KEY", "same-value")
            ciphertexts.add(captured.captured.encryptedValue)
        }

        assertTrue(ciphertexts.size > 1, "AES-GCM should produce different ciphertext each time due to random IV")
    }

    @Test
    fun `decryptSecrets roundtrips through encrypt-decrypt`() = runTest {
        val captured = slot<PipelineSecret>()
        coEvery { secretRepository.upsert(capture(captured)) } answers { captured.captured }

        service.setSecret(repoId, "TOKEN", "my-secret-token")

        val encryptedValue = captured.captured.encryptedValue
        coEvery { secretRepository.findAllByRepository(repoId) } returns listOf(
            PipelineSecret(repositoryId = repoId, name = "TOKEN", encryptedValue = encryptedValue)
        )

        val decrypted = service.decryptSecrets(repoId, listOf("TOKEN"))

        assertEquals(1, decrypted.size)
        assertEquals("my-secret-token", decrypted["TOKEN"])
    }

    @Test
    fun `decryptSecrets only returns requested names`() = runTest {
        val captured = slot<PipelineSecret>()
        coEvery { secretRepository.upsert(capture(captured)) } answers { captured.captured }

        service.setSecret(repoId, "KEY1", "value1")
        val enc1 = captured.captured.encryptedValue
        service.setSecret(repoId, "KEY2", "value2")
        val enc2 = captured.captured.encryptedValue

        coEvery { secretRepository.findAllByRepository(repoId) } returns listOf(
            PipelineSecret(repositoryId = repoId, name = "KEY1", encryptedValue = enc1),
            PipelineSecret(repositoryId = repoId, name = "KEY2", encryptedValue = enc2)
        )

        val decrypted = service.decryptSecrets(repoId, listOf("KEY1"))

        assertEquals(1, decrypted.size)
        assertEquals("value1", decrypted["KEY1"])
    }

    @Test
    fun `decryptSecrets returns empty map when no matching secrets`() = runTest {
        coEvery { secretRepository.findAllByRepository(repoId) } returns emptyList()

        val decrypted = service.decryptSecrets(repoId, listOf("NONEXISTENT"))
        assertTrue(decrypted.isEmpty())
    }

    @Test
    fun `deleteSecret delegates to repository`() = runTest {
        service.deleteSecret(repoId, "OLD_KEY")
        coVerify { secretRepository.delete(repoId, "OLD_KEY") }
    }

    @Test
    fun `listSecrets delegates to repository`() = runTest {
        val secrets = listOf(
            PipelineSecret(repositoryId = repoId, name = "KEY1", encryptedValue = "enc1"),
            PipelineSecret(repositoryId = repoId, name = "KEY2", encryptedValue = "enc2")
        )
        coEvery { secretRepository.findByRepository(repoId) } returns secrets

        val result = service.listSecrets(repoId)
        assertEquals(2, result.size)
    }

    @Test
    fun `encrypt handles empty string`() = runTest {
        val captured = slot<PipelineSecret>()
        coEvery { secretRepository.upsert(capture(captured)) } answers { captured.captured }

        service.setSecret(repoId, "EMPTY", "")

        coEvery { secretRepository.findAllByRepository(repoId) } returns listOf(
            PipelineSecret(repositoryId = repoId, name = "EMPTY", encryptedValue = captured.captured.encryptedValue)
        )

        val decrypted = service.decryptSecrets(repoId, listOf("EMPTY"))
        assertEquals("", decrypted["EMPTY"])
    }

    @Test
    fun `encrypt handles unicode content`() = runTest {
        val captured = slot<PipelineSecret>()
        coEvery { secretRepository.upsert(capture(captured)) } answers { captured.captured }

        val unicodeValue = "password-\u00e9\u00e8\u00ea-\u4e16\u754c"
        service.setSecret(repoId, "UNICODE", unicodeValue)

        coEvery { secretRepository.findAllByRepository(repoId) } returns listOf(
            PipelineSecret(repositoryId = repoId, name = "UNICODE", encryptedValue = captured.captured.encryptedValue)
        )

        val decrypted = service.decryptSecrets(repoId, listOf("UNICODE"))
        assertEquals(unicodeValue, decrypted["UNICODE"])
    }

    // ─── resolveJobSecrets ──────────────────────────────────────────

    private fun job(secretNames: List<String> = emptyList(), environment: String? = null) =
        bosca.git.model.PipelineJob(
            id = UUID.random(),
            pipelineRunId = UUID.random(),
            name = "deploy",
            status = bosca.git.model.PipelineRunStatus.RUNNING,
            runnerLabel = "linux",
            secretNames = secretNames,
            environment = environment,
        )

    private fun run(triggeredBy: UUID? = UUID.random()) = bosca.git.model.PipelineRun(
        id = UUID.random(),
        pipelineId = UUID.random(),
        repositoryId = repoId,
        commitSha = "sha",
        ref = "refs/tags/v1.0.0",
        triggerType = bosca.git.model.PipelineTriggerType.RELEASE,
        triggeredBy = triggeredBy,
        number = 7,
    )

    private suspend fun storedSecret(name: String, value: String, environmentKey: String? = null): PipelineSecret =
        service.setSecret(repoId, name, value, environmentKey)

    @Test
    fun `resolveJobSecrets decrypts a declared and permitted secret`() = runTest {
        val secret = storedSecret("PLAY_KEY", "play-material", environmentKey = "production")
        coEvery { secretRepository.findByName(repoId, "PLAY_KEY") } returns secret
        coEvery { evaluator.isAllowed(any<AuthenticationContext>(), any<PipelineSecret>(), any()) } returns true

        val resolved = service.resolveJobSecrets(job(secretNames = listOf("PLAY_KEY"), environment = "production"), run())

        assertEquals(mapOf("PLAY_KEY" to "play-material"), resolved)
    }

    @Test
    fun `a declared secret that does not exist fails naming it`() = runTest {
        coEvery { secretRepository.findByName(repoId, "GHOST") } returns null

        val e = kotlin.test.assertFailsWith<IllegalStateException> {
            service.resolveJobSecrets(job(secretNames = listOf("GHOST")), run())
        }
        assertTrue("GHOST" in (e.message ?: ""), e.message)
    }

    @Test
    fun `a declared environment-scoped secret fails for a job outside that environment`() = runTest {
        val secret = storedSecret("PLAY_KEY", "play-material", environmentKey = "production")
        coEvery { secretRepository.findByName(repoId, "PLAY_KEY") } returns secret

        val e = kotlin.test.assertFailsWith<IllegalStateException> {
            service.resolveJobSecrets(job(secretNames = listOf("PLAY_KEY"), environment = "staging"), run())
        }
        assertTrue("PLAY_KEY" in (e.message ?: "") && "production" in (e.message ?: ""), e.message)
    }

    @Test
    fun `a declared secret the initiating principal lacks permission on fails naming it`() = runTest {
        val secret = storedSecret("PLAY_KEY", "play-material")
        coEvery { secretRepository.findByName(repoId, "PLAY_KEY") } returns secret
        coEvery { evaluator.isAllowed(any<AuthenticationContext>(), any<PipelineSecret>(), any()) } returns false

        val e = kotlin.test.assertFailsWith<IllegalStateException> {
            service.resolveJobSecrets(job(secretNames = listOf("PLAY_KEY")), run())
        }
        assertTrue("PLAY_KEY" in (e.message ?: ""), e.message)
    }

    @Test
    fun `legacy fallback resolves grant-less secrets without attribution and hides scoped ones`() = runTest {
        val plain = storedSecret("PLAIN", "plain-value")
        val scoped = storedSecret("PROD_ONLY", "prod-value", environmentKey = "production")
        coEvery { secretRepository.findAllByRepository(repoId) } returns listOf(plain, scoped)

        // No declaration, no environment binding, and — deliberately — no run initiator: exactly
        // the legacy shape, which must keep working unchanged. The scoped secret is silently
        // excluded; it was never asked for.
        val resolved = service.resolveJobSecrets(job(), run(triggeredBy = null))

        assertEquals(mapOf("PLAIN" to "plain-value"), resolved)
    }

    @Test
    fun `legacy fallback enforces grants and demands attribution once a secret carries them`() = runTest {
        val granted = storedSecret("GRANTED", "granted-value")
        coEvery { secretRepository.findAllByRepository(repoId) } returns listOf(granted)
        coEvery { permissionRepository.findBySecretId(granted.id) } returns listOf(
            bosca.git.model.PipelineSecretPermission(
                secretId = granted.id, groupId = UUID.random(),
                action = bosca.security.model.PermissionAction.EXECUTE,
            ),
        )

        // A granted secret without a run initiator cannot resolve — nothing executes unattributed.
        val e = kotlin.test.assertFailsWith<IllegalStateException> {
            service.resolveJobSecrets(job(), run(triggeredBy = null))
        }
        assertTrue("initiating principal" in (e.message ?: ""), e.message)

        // With an initiator the evaluator decides.
        coEvery { evaluator.isAllowed(any<AuthenticationContext>(), any<PipelineSecret>(), any()) } returns true
        assertEquals(
            mapOf("GRANTED" to "granted-value"),
            service.resolveJobSecrets(job(), run()),
        )
    }
}
