package bosca.kubernetes.service

import bosca.kubernetes.model.HelmRepoCredentials
import bosca.kubernetes.repository.HelmRepoRepository
import bosca.kubernetes.repository.HelmRepo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HelmRepoServiceTest {

    private val repository = mockk<HelmRepoRepository>()
    private val credentials = mockk<HelmRepoCredentialService>()
    private val service = HelmRepoServiceImpl(repository, credentials)

    @AfterTest
    fun teardown() {
        io.mockk.unmockkAll()
    }

    @Test
    fun `save persists the validated index and encrypted private credentials`() = runTest {
        val row = HelmRepo("private", "https://charts.example.com", "http", indexYaml = "entries: {}")
        val secret = HelmRepoCredentials("api_token", "bsk_secret")
        coEvery { repository.upsert("private", row.url, "http", "entries: {}") } returns row
        coEvery { credentials.store("private", secret) } returns Unit

        val saved = service.save("private", row.url, "http", "entries: {}", secret)

        assertEquals(row, saved)
        coVerify { credentials.store("private", secret) }
        coVerify(exactly = 0) { credentials.delete(any()) }
    }

    @Test
    fun `save removes an old credential when repository is public`() = runTest {
        val row = HelmRepo("public", "https://charts.example.com", "http", indexYaml = "entries: {}")
        coEvery { repository.upsert("public", row.url, "http", "entries: {}") } returns row
        coEvery { credentials.delete("public") } returns Unit

        service.save("public", row.url, "http", "entries: {}", null)

        coVerify { credentials.delete("public") }
        coVerify(exactly = 0) { credentials.store(any(), any()) }
    }

    @Test
    fun `credentials delegate to encrypted credential service`() = runTest {
        val secret = HelmRepoCredentials("user", "password")
        coEvery { credentials.load("private") } returns secret

        val loaded = service.credentials("private")

        assertEquals("user", loaded?.username)
        assertEquals("password", loaded?.password)
    }
}
