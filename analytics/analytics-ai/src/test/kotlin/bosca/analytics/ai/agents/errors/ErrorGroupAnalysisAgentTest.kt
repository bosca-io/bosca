package bosca.analytics.ai.agents.errors

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import bosca.analytics.model.ErrorGroup
import bosca.analytics.model.ErrorGroupStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ErrorGroupAnalysisAgentTest {

    private val now: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)
    private val sampleGroup = ErrorGroup(
        fingerprint = "fp-1",
        appId = "app-1",
        type = "java.lang.NullPointerException",
        message = "Cannot invoke method on null object",
        fatal = true,
        status = ErrorGroupStatus.OPEN,
        firstSeen = now.minusHours(3),
        lastSeen = now,
        eventCount = 42L,
        sampleEventId = "client-1",
        sampleStack = "java.lang.NullPointerException\n\tat com.example.Foo.bar(Foo.kt:42)",
        created = now,
        modified = now,
    )

    /** A PromptExecutor whose single-shot call always fails, to prove the agent does not swallow it. */
    private val failingExecutor: PromptExecutor = object : PromptExecutor() {
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant =
            throw RuntimeException("upstream timeout")

        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
            throw RuntimeException("upstream timeout")

        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
            throw RuntimeException("upstream timeout")

        override fun close() {}
    }

    @Test
    fun `analyze returns the assistant message text content`() = runTest {
        val executor = getMockExecutor { mockLLMAnswer("This is the root cause.").asDefaultResponse }
        assertEquals("This is the root cause.", ErrorGroupAnalysisAgent(executor).analyze(sampleGroup))
    }

    @Test
    fun `analyze sends the built error prompt to the executor`() = runTest {
        // The mock only answers when the request carries our prompt, so a matched answer proves the
        // agent actually built the user prompt from the group and handed it to the executor.
        val executor = getMockExecutor {
            mockLLMAnswer("matched") onRequestContains "An error has been recorded"
        }
        assertEquals("matched", ErrorGroupAnalysisAgent(executor).analyze(sampleGroup))
    }

    @Test
    fun `analyze returns blank when the model produces no usable text`() = runTest {
        // The agent forwards the assistant's text verbatim; collapsing blank-or-missing content into
        // "no summary" is the caller's job (AiErrorGroupAnalysisService uses isNullOrBlank()), so the
        // agent must not swallow it here.
        val executor = getMockExecutor { mockLLMAnswer("   \n  ").asDefaultResponse }
        assertTrue(ErrorGroupAnalysisAgent(executor).analyze(sampleGroup).isNullOrBlank())
    }

    @Test
    fun `analyze surfaces transient executor failures to the caller`() = runTest {
        assertFailsWith<RuntimeException> { ErrorGroupAnalysisAgent(failingExecutor).analyze(sampleGroup) }
    }

    @Test
    fun `user prompt includes the error metadata and stack`() {
        val prompt = ErrorGroupAnalysisAgent(failingExecutor).buildUserPrompt(sampleGroup)
        assertTrue(prompt.contains("app-1"))
        assertTrue(prompt.contains("java.lang.NullPointerException"))
        assertTrue(prompt.contains("Cannot invoke method on null object"))
        assertTrue(prompt.contains("Fatal: true"))
        assertTrue(prompt.contains("Total occurrences: 42"))
        assertTrue(prompt.contains("at com.example.Foo.bar(Foo.kt:42)"))
    }

    @Test
    fun `user prompt notes when stack trace is missing`() {
        val prompt = ErrorGroupAnalysisAgent(failingExecutor).buildUserPrompt(sampleGroup.copy(sampleStack = null))
        assertTrue(prompt.contains("No stack trace was captured"))
    }

    @Test
    fun `user prompt truncates extremely long stack traces`() {
        val huge = "at com.example.Foo.bar(Foo.kt:1)\n".repeat(2_000)
        val prompt = ErrorGroupAnalysisAgent(failingExecutor).buildUserPrompt(sampleGroup.copy(sampleStack = huge))
        // 8K char cap on the stack section keeps the prompt within reasonable bounds
        assertTrue(prompt.length < 10_000, "prompt should be truncated to bound token cost")
    }
}
