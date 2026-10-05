package bosca.analytics.service

import bosca.analytics.model.ErrorGroup
import bosca.analytics.model.ErrorGroupStatus
import bosca.di.ObjectProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class NoOpErrorGroupAnalysisServiceTest {

    private val errorGroupService = mockk<ErrorGroupService>()
    private val service = NoOpErrorGroupAnalysisService(errorGroupService)

    private val now: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)
    private val sampleGroup = ErrorGroup(
        fingerprint = "fp-1",
        appId = "app-1",
        type = "E",
        message = "boom",
        fatal = false,
        status = ErrorGroupStatus.OPEN,
        firstSeen = now,
        lastSeen = now,
        eventCount = 1L,
        created = now,
        modified = now,
    )

    @Test
    fun `returns the existing group unchanged when no analyzer is wired`() = runTest {
        coEvery { errorGroupService.getByFingerprint("fp-1") } returns sampleGroup
        val result = service.analyze("fp-1")
        assertEquals(sampleGroup, result)
        assertNull(result.aiSummary, "no-op service must not fabricate an AI summary")
    }

    @Test
    fun `throws when the group does not exist`() = runTest {
        coEvery { errorGroupService.getByFingerprint("missing") } returns null
        assertFailsWith<IllegalStateException> {
            service.analyze("missing")
        }
    }

    @Test
    fun `provider facade returns the AI implementation when available`() = runTest {
        val aiService = mockk<ErrorGroupAnalysisService>()
        val aiProvider = mockk<ObjectProvider<ErrorGroupAnalysisService>> {
            every { exists } returns true
            coEvery { get() } returns aiService
        }

        val result = ErrorGroupAnalysisServiceProvider().analysisService(errorGroupService, aiProvider)

        assertSame(aiService, result)
        coVerify(exactly = 1) { aiProvider.get() }
    }

    @Test
    fun `provider facade returns the no-op implementation when AI is unavailable`() = runTest {
        val aiProvider = mockk<ObjectProvider<ErrorGroupAnalysisService>> {
            every { exists } returns false
        }

        val result = ErrorGroupAnalysisServiceProvider().analysisService(errorGroupService, aiProvider)

        assertIs<NoOpErrorGroupAnalysisService>(result)
        coVerify(exactly = 0) { aiProvider.get() }
    }
}
