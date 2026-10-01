package bosca.workops.service

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.artifact.AllocateAppBuildNumberInput
import bosca.workops.model.artifact.AppBuildNumberAllocation
import bosca.workops.model.artifact.AppBuildPlatform
import bosca.workops.repository.AppBuildNumberCounter
import bosca.workops.repository.AppBuildNumberRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppBuildNumberServiceImplTest {

    private val repository = mockk<AppBuildNumberRepository>()
    private val service = AppBuildNumberServiceImpl(repository)
    private val repositoryId = UUID.random()
    private val pipelineRunId = UUID.random()
    private val commit = "a".repeat(40)

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
    }

    @AfterTest
    fun teardown() {
        io.mockk.unmockkStatic("bosca.db.ConnectionManagerKt")
    }

    private fun input(
        platform: AppBuildPlatform = AppBuildPlatform.ANDROID,
        minimum: Long = 1,
    ) = AllocateAppBuildNumberInput(
        platform = platform,
        applicationId = " io.bosca.app ",
        buildKey = " release ",
        repositoryId = repositoryId,
        sourceCommitSha = commit.uppercase(),
        sourceVersion = " 6.2.0 ",
        pipelineRunId = pipelineRunId,
        minimumNumber = minimum,
    )

    @Test
    fun `fresh iOS release line starts at one and stores a plain integer`() = runTest {
        coEvery { repository.ensureCounter("IOS", "io.bosca.app", "6.2") } returns null
        coEvery { repository.lockCounter("IOS", "io.bosca.app", "6.2") } returns
            counter(AppBuildPlatform.IOS, "6.2", 0, 3)
        coEvery { repository.find(repositoryId, commit, "6.2.0", "IOS", "io.bosca.app", "release") } returns null
        coEvery { repository.updateCounter("IOS", "io.bosca.app", "6.2", 1, 3) } returns
            counter(AppBuildPlatform.IOS, "6.2", 1, 4)
        coEvery {
            repository.add(
                "IOS", "io.bosca.app", "6.2", "release", repositoryId, commit, "6.2.0",
                pipelineRunId, 1, "1",
            )
        } returns allocation(AppBuildPlatform.IOS, 1, "1", "6.2")

        val result = service.allocate(input(AppBuildPlatform.IOS))

        assertFalse(result.reused)
        assertEquals(1, result.allocation.number)
        assertEquals("1", result.allocation.value)
        assertEquals("6.2", result.allocation.versionScope)
        coVerify(ordering = io.mockk.Ordering.SEQUENCE) {
            repository.ensureCounter("IOS", "io.bosca.app", "6.2")
            repository.lockCounter("IOS", "io.bosca.app", "6.2")
            repository.find(repositoryId, commit, "6.2.0", "IOS", "io.bosca.app", "release")
            repository.updateCounter("IOS", "io.bosca.app", "6.2", 1, 3)
            repository.add(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `retry reuses the immutable allocation without advancing the counter`() = runTest {
        val existing = allocation(AppBuildPlatform.ANDROID, 42, "42")
        coEvery { repository.ensureCounter("ANDROID", "io.bosca.app", "global") } returns null
        coEvery { repository.lockCounter("ANDROID", "io.bosca.app", "global") } returns
            counter(AppBuildPlatform.ANDROID, "global", 42, 9)
        coEvery {
            repository.find(repositoryId, commit, "6.2.0", "ANDROID", "io.bosca.app", "release")
        } returns existing

        val result = service.allocate(input())

        assertTrue(result.reused)
        assertEquals(existing, result.allocation)
        coVerify(exactly = 0) { repository.updateCounter(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) {
            repository.add(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `blank build key uses the default and the counter determines the next Android number`() = runTest {
        val blankKeyInput = input().copy(buildKey = "  ", sourceCommitSha = "b".repeat(64))
        coEvery { repository.ensureCounter("ANDROID", "io.bosca.app", "global") } returns null
        coEvery { repository.lockCounter("ANDROID", "io.bosca.app", "global") } returns
            counter(AppBuildPlatform.ANDROID, "global", 41, 5)
        coEvery {
            repository.find(repositoryId, "b".repeat(64), "6.2.0", "ANDROID", "io.bosca.app", "default")
        } returns null
        coEvery { repository.updateCounter("ANDROID", "io.bosca.app", "global", 42, 5) } returns
            counter(AppBuildPlatform.ANDROID, "global", 42, 6)
        coEvery {
            repository.add(
                "ANDROID", "io.bosca.app", "global", "default", repositoryId, "b".repeat(64), "6.2.0",
                pipelineRunId, 42, "42",
            )
        } returns allocation(AppBuildPlatform.ANDROID, 42, "42").copy(
            buildKey = "default",
            sourceCommitSha = "b".repeat(64),
        )

        val result = service.allocate(blankKeyInput)

        assertFalse(result.reused)
        assertEquals(42, result.allocation.number)
        assertEquals("default", result.allocation.buildKey)
    }

    @Test
    fun `find normalizes lookup identity and defaults a blank build key`() = runTest {
        val expected = allocation(AppBuildPlatform.IOS, 1, "1", "6.2")
        coEvery {
            repository.find(repositoryId, commit, "6.2.0", "IOS", "io.bosca.app", "default")
        } returns expected

        val result = service.find(
            repositoryId = repositoryId,
            sourceCommitSha = "  ${commit.uppercase()}  ",
            sourceVersion = " 6.2.0 ",
            platform = AppBuildPlatform.IOS,
            applicationId = " io.bosca.app ",
            buildKey = " ",
        )

        assertEquals(expected, result)
    }

    @Test
    fun `find returns null when the source has no allocation`() = runTest {
        coEvery {
            repository.find(repositoryId, commit, "6.2.0", "ANDROID", "io.bosca.app", "release")
        } returns null

        assertNull(
            service.find(
                repositoryId, commit, "6.2.0", AppBuildPlatform.ANDROID, "io.bosca.app", "release",
            ),
        )
    }

    @Test
    fun `allocation fails before mutation when the platform space is exhausted`() = runTest {
        coEvery { repository.ensureCounter("ANDROID", "io.bosca.app", "global") } returns null
        coEvery { repository.lockCounter("ANDROID", "io.bosca.app", "global") } returns
            counter(AppBuildPlatform.ANDROID, "global", AppBuildPlatform.ANDROID.maximumNumber, 2)
        coEvery {
            repository.find(repositoryId, commit, "6.2.0", "ANDROID", "io.bosca.app", "release")
        } returns null

        val failure = assertFailsWith<IllegalStateException> { service.allocate(input()) }

        assertTrue("exhausted" in (failure.message ?: ""))
        coVerify(exactly = 0) { repository.updateCounter(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) {
            repository.add(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `allocation fails when the counter row cannot be locked`() = runTest {
        coEvery { repository.ensureCounter("ANDROID", "io.bosca.app", "global") } returns null
        coEvery { repository.lockCounter("ANDROID", "io.bosca.app", "global") } returns null

        val failure = assertFailsWith<IllegalStateException> { service.allocate(input()) }

        assertTrue("Failed to create" in (failure.message ?: ""))
        coVerify(exactly = 0) { repository.find(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `allocation fails when the locked counter cannot be updated`() = runTest {
        coEvery { repository.ensureCounter("ANDROID", "io.bosca.app", "global") } returns null
        coEvery { repository.lockCounter("ANDROID", "io.bosca.app", "global") } returns
            counter(AppBuildPlatform.ANDROID, "global", 41, 5)
        coEvery {
            repository.find(repositoryId, commit, "6.2.0", "ANDROID", "io.bosca.app", "release")
        } returns null
        coEvery { repository.updateCounter("ANDROID", "io.bosca.app", "global", 42, 5) } returns null

        val failure = assertFailsWith<IllegalStateException> { service.allocate(input()) }

        assertTrue("changed while locked" in (failure.message ?: ""))
        coVerify(exactly = 0) {
            repository.add(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `invalid source identity never reaches the repository`() = runTest {
        val invalidInputs = listOf(
            input().copy(applicationId = " "),
            input().copy(applicationId = "io.bosca bad"),
            input().copy(buildKey = "bad key"),
            input().copy(sourceCommitSha = "short"),
            input().copy(sourceCommitSha = "g".repeat(40)),
            input().copy(sourceVersion = " "),
            input().copy(minimumNumber = 0),
            input().copy(minimumNumber = AppBuildPlatform.ANDROID.maximumNumber + 1),
            input(AppBuildPlatform.IOS).copy(sourceVersion = "release"),
            input(AppBuildPlatform.IOS).copy(minimumNumber = AppBuildPlatform.IOS.maximumNumber + 1),
        )

        invalidInputs.forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { service.allocate(invalid) }
        }

        confirmVerified(repository)
    }

    @Test
    fun `counter model retains the durable application identity and modification time`() {
        val modifiedAt = OffsetDateTime.now()
        val counter = AppBuildNumberCounter(
            platform = AppBuildPlatform.IOS,
            applicationId = "com.example.app",
            versionScope = "6.2",
            lastNumber = 7,
            modifiedAt = modifiedAt,
            version = 7,
        )

        assertEquals(AppBuildPlatform.IOS, counter.platform)
        assertEquals("com.example.app", counter.applicationId)
        assertEquals("6.2", counter.versionScope)
        assertEquals(7, counter.lastNumber)
        assertEquals(modifiedAt, counter.modifiedAt)
        assertEquals(7, counter.version)
    }

    private fun counter(
        platform: AppBuildPlatform,
        versionScope: String,
        number: Long,
        version: Long,
    ) = AppBuildNumberCounter(
        platform = platform,
        applicationId = "io.bosca.app",
        versionScope = versionScope,
        lastNumber = number,
        modifiedAt = OffsetDateTime.now(),
        version = version,
    )

    private fun allocation(
        platform: AppBuildPlatform,
        number: Long,
        value: String,
        versionScope: String = "global",
    ) = AppBuildNumberAllocation(
        id = UUID.random(),
        platform = platform,
        applicationId = "io.bosca.app",
        versionScope = versionScope,
        buildKey = "release",
        repositoryId = repositoryId,
        sourceCommitSha = commit,
        sourceVersion = "6.2.0",
        pipelineRunId = pipelineRunId,
        number = number,
        value = value,
    )
}
