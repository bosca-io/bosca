package bosca.analytics.ai.service

import ai.koog.prompt.executor.model.PromptExecutor
import bosca.analytics.ai.agents.errors.ErrorGroupAnalysisAgent
import bosca.analytics.model.ErrorGroup
import bosca.analytics.model.ErrorGroupStatus
import bosca.analytics.service.ErrorGroupService
import bosca.analytics.service.NoOpErrorGroupAnalysisService
import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class AiErrorGroupAnalysisServiceTest {

    private val errorGroupService = mockk<ErrorGroupService>()
    private val agent = mockk<ErrorGroupAnalysisAgent>()
    private val service = AiErrorGroupAnalysisService(errorGroupService, agent)

    private val now: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)
    private val sampleGroup = ErrorGroup(
        fingerprint = "fp-1",
        appId = "app-1",
        type = "java.lang.NullPointerException",
        message = "boom",
        fatal = false,
        status = ErrorGroupStatus.OPEN,
        firstSeen = now,
        lastSeen = now,
        eventCount = 1L,
        sampleStack = "at com.example.Foo.bar(Foo.kt:42)",
        created = now,
        modified = now,
    )

    @Test
    fun `analysis cooldown is five minutes`() {
        assertEquals(Duration.ofMinutes(5), AiErrorGroupAnalysisService.ANALYSIS_COOLDOWN)
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    @Test
    fun `analyze caches the AI summary on the group`() = runTest {
        val updated = sampleGroup.copy(aiSummary = "root cause: x", aiSummaryAt = now)
        coEvery { errorGroupService.getByFingerprint("fp-1") } returns sampleGroup
        coEvery { agent.analyze(sampleGroup) } returns "root cause: x"
        coEvery { errorGroupService.setAiSummary("fp-1", "root cause: x") } returns updated

        val result = service.analyze("fp-1")

        assertEquals(updated, result)
        coVerify { errorGroupService.setAiSummary("fp-1", "root cause: x") }
    }

    @Test
    fun `analyze returns the existing group when the agent returns null`() = runTest {
        coEvery { errorGroupService.getByFingerprint("fp-1") } returns sampleGroup
        coEvery { agent.analyze(sampleGroup) } returns null

        val result = service.analyze("fp-1")

        assertSame(sampleGroup, result)
        coVerify(exactly = 0) { errorGroupService.setAiSummary(any(), any()) }
    }

    @Test
    fun `analyze returns the existing group when the agent returns blank text`() = runTest {
        coEvery { errorGroupService.getByFingerprint("fp-1") } returns sampleGroup
        coEvery { agent.analyze(sampleGroup) } returns "   "

        val result = service.analyze("fp-1")

        assertSame(sampleGroup, result)
        coVerify(exactly = 0) { errorGroupService.setAiSummary(any(), any()) }
    }

    @Test
    fun `analyze swallows agent failures and returns the existing group`() = runTest {
        coEvery { errorGroupService.getByFingerprint("fp-1") } returns sampleGroup
        coEvery { agent.analyze(sampleGroup) } throws RuntimeException("upstream timeout")

        val result = service.analyze("fp-1")

        assertSame(sampleGroup, result)
        coVerify(exactly = 0) { errorGroupService.setAiSummary(any(), any()) }
    }

    @Test
    fun `analyze skips LLM call when within cooldown window`() = runTest {
        val recentlyAnalyzed = sampleGroup.copy(
            aiSummary = "previous analysis",
            aiSummaryAt = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1),
        )
        coEvery { errorGroupService.getByFingerprint("fp-1") } returns recentlyAnalyzed

        val result = service.analyze("fp-1")

        assertSame(recentlyAnalyzed, result)
        coVerify(exactly = 0) { agent.analyze(any()) }
        coVerify(exactly = 0) { errorGroupService.setAiSummary(any(), any()) }
    }

    @Test
    fun `analyze proceeds when cooldown has elapsed`() = runTest {
        val oldAnalysis = sampleGroup.copy(
            aiSummary = "old analysis",
            aiSummaryAt = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10),
        )
        val updated = oldAnalysis.copy(aiSummary = "new analysis", aiSummaryAt = now)
        coEvery { errorGroupService.getByFingerprint("fp-1") } returns oldAnalysis
        coEvery { agent.analyze(oldAnalysis) } returns "new analysis"
        coEvery { errorGroupService.setAiSummary("fp-1", "new analysis") } returns updated

        val result = service.analyze("fp-1")

        assertEquals(updated, result)
        coVerify { agent.analyze(oldAnalysis) }
    }

    @Test
    fun `analyze truncates oversized summaries`() = runTest {
        val huge = "x".repeat(100_000)
        coEvery { errorGroupService.getByFingerprint("fp-1") } returns sampleGroup
        coEvery { agent.analyze(sampleGroup) } returns huge
        coEvery { errorGroupService.setAiSummary(eq("fp-1"), any()) } answers {
            sampleGroup.copy(aiSummary = secondArg(), aiSummaryAt = now)
        }

        service.analyze("fp-1")

        coVerify { errorGroupService.setAiSummary("fp-1", match { it.length == 65_536 }) }
    }

    @Test
    fun `analyze throws when the group does not exist`() = runTest {
        coEvery { errorGroupService.getByFingerprint("missing") } returns null
        assertFailsWith<IllegalStateException> { service.analyze("missing") }
    }

    @Test
    fun `analyze preserves coroutine cancellation`() = runTest {
        coEvery { errorGroupService.getByFingerprint("fp-1") } returns sampleGroup
        coEvery { agent.analyze(sampleGroup) } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> { service.analyze("fp-1") }
    }

    @Test
    fun `provider falls back to the no-op service when no prompt executor exists`() = runTest {
        val executor = mockk<ObjectProvider<PromptExecutor>>()
        every { executor.exists } returns false
        val application = BoscaApplication(ApplicationConfig.load("bosca: {}".byteInputStream()))

        val result = AiErrorGroupAnalysisServiceProvider().analysisService(errorGroupService, executor, application)

        assertIs<NoOpErrorGroupAnalysisService>(result)
    }

    @Test
    fun `provider builds the AI service with the default model`() = runTest {
        val executor = mockk<ObjectProvider<PromptExecutor>>()
        every { executor.exists } returns true
        coEvery { executor.get() } returns mockk()
        val application = BoscaApplication(ApplicationConfig.load("bosca: {}".byteInputStream()))

        val result = AiErrorGroupAnalysisServiceProvider().analysisService(errorGroupService, executor, application)

        assertIs<AiErrorGroupAnalysisService>(result)
    }

    @Test
    fun `provider accepts an explicitly configured model`() = runTest {
        val executor = mockk<ObjectProvider<PromptExecutor>>()
        every { executor.exists } returns true
        coEvery { executor.get() } returns mockk()
        val config = ApplicationConfig.load(
            """
            analytics:
              errorAnalysis:
                model: openai.chat.GPT4_1Mini
            """.trimIndent().byteInputStream(),
        )
        val application = BoscaApplication(config)

        val result = AiErrorGroupAnalysisServiceProvider().analysisService(errorGroupService, executor, application)

        assertIs<AiErrorGroupAnalysisService>(result)
    }
}
