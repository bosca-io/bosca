package bosca.analytics.ai.agents.errors

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import bosca.analytics.model.ErrorGroup

/**
 * Generates a short, operator-friendly root-cause summary for an
 * [ErrorGroup] using a one-shot LLM call. Used by
 * [bosca.analytics.ai.service.AiErrorGroupAnalysisService] to populate
 * the cached `aiSummary` shown in the admin error tracking UI.
 *
 * The agent intentionally uses a single non-streaming call rather than
 * the multi-step graph agent pattern: error analysis is a one-shot
 * synthesis task without tool use, and a flat call keeps latency,
 * cost, and failure modes predictable.
 */
class ErrorGroupAnalysisAgent(
    private val executor: PromptExecutor,
    private val model: LLModel = OpenAIModels.Chat.GPT4_1,
) {

    /**
     * Returns a markdown-formatted root-cause summary, or null if the
     * LLM produced no usable text. Throws on transient executor
     * failures so the caller can decide whether to log-and-fall-back
     * or surface to the user.
     */
    suspend fun analyze(group: ErrorGroup): String? {
        val prompt = prompt("error-group-analysis") {
            system(SYSTEM_PROMPT)
            user(buildUserPrompt(group))
        }
        val responses = executor.execute(prompt = prompt, model = model)
        return responses.textContent()
    }

    /** Visible for testing. */
    internal fun buildUserPrompt(group: ErrorGroup): String = buildString {
        appendLine("An error has been recorded in our application.")
        appendLine("Application: ${group.appId}")
        appendLine("Exception type: ${group.type}")
        appendLine("Latest message: ${group.message}")
        appendLine("Fatal: ${group.fatal}")
        appendLine("Total occurrences: ${group.eventCount}")
        appendLine("First seen: ${group.firstSeen}")
        appendLine("Last seen: ${group.lastSeen}")
        appendLine()
        val stack = group.sampleStack
        if (!stack.isNullOrBlank()) {
            appendLine("Latest stack trace:")
            appendLine("```")
            // Truncate very long stack traces to keep token cost bounded
            // and avoid context window blow-out on deeply recursive frames.
            appendLine(stack.take(MAX_STACK_CHARS))
            appendLine("```")
        } else {
            appendLine("No stack trace was captured for this group.")
        }
        appendLine()
        appendLine(
            "Provide a brief root cause analysis suitable for an on-call engineer. " +
                "Be concrete: name the most likely failing component, identify the most " +
                "probable proximate cause, and suggest the next debugging step. Do not " +
                "speculate beyond what the stack trace and metadata support. Keep the " +
                "response under 200 words and use plain markdown."
        )
    }

    companion object {
        private const val MAX_STACK_CHARS = 8_000

        private val SYSTEM_PROMPT = """
            You are a senior software engineer helping triage production exceptions.
            You receive a single error class along with one representative stack trace
            and metadata about how often the error has occurred. Your job is to produce
            a concise root cause hypothesis and a clear next debugging step.

            Guidelines:
            - Focus on the proximate cause first, then the likely root cause.
            - Cite specific frames or class names from the stack trace when you can.
            - If the stack trace is missing or unhelpful, say so explicitly and suggest
              what additional context would help.
            - Never invent file paths, line numbers, or behaviors that are not present
              in the input.
            - Output plain markdown with short paragraphs and at most one short bullet
              list. Do not include headings.
        """.trimIndent()
    }
}
