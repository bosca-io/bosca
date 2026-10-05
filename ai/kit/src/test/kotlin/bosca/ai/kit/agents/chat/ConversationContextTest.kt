package bosca.ai.kit.agents.chat

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The RAG-style seeding that gives the ChatAgent the broader session — [renderConversationContext] builds
 * the background block from the full transcript, and [ConversationContextPreProcessor] strips it back out
 * before the agent's own memory thread is persisted (so the seed can't bloat the thread).
 */
class ConversationContextTest {

    private fun user(text: String) = Message.User(text, RequestMetaInfo.Empty)
    private fun kit(text: String) = Message.Assistant(text, ResponseMetaInfo.Empty)

    @Test
    fun `renders nothing when there is no prior conversation`() {
        assertEquals("", renderConversationContext(emptyList(), "hi"))
        // only the current message is present → nothing PRIOR to seed.
        assertEquals("", renderConversationContext(listOf(user("hi")), currentText = "hi"))
    }

    @Test
    fun `renders prior turns as a delimited block, labeling speakers and excluding the current message`() {
        val transcript = listOf(
            user("What content templates are available?"),
            kit("Devotionals, Bible studies, sermon outlines, and more."),
            user("Are you sure?"), // the current turn — sent as the real request, not context
        )

        val context = renderConversationContext(transcript, currentText = "Are you sure?")

        assertTrue(context.startsWith(CONTEXT_OPEN), "context should open with the delimiter: $context")
        assertTrue(context.trimEnd().endsWith(CONTEXT_CLOSE), "context should close with the delimiter: $context")
        assertTrue(context.contains("User: What content templates are available?"))
        assertTrue(context.contains("Kit: Devotionals, Bible studies, sermon outlines, and more."))
        // The current message is the actual request; it must NOT be duplicated into the context block.
        assertFalse(context.contains("Are you sure?"), "the current turn must be excluded: $context")
    }

    @Test
    fun `caps the seeded context at the most recent messages`() {
        val transcript = (1..MAX_CONTEXT_MESSAGES + 10).map { kit("msg $it") }

        val context = renderConversationContext(transcript, currentText = "current")
        val body = context.removePrefix("$CONTEXT_OPEN\n").removeSuffix("\n$CONTEXT_CLOSE\n\n")

        assertEquals(MAX_CONTEXT_MESSAGES, body.lines().size, "only the most recent window is seeded")
        assertFalse(context.contains("Kit: msg 1\n"), "the oldest turn should be dropped")
        assertTrue(context.contains("Kit: msg ${MAX_CONTEXT_MESSAGES + 10}"), "the newest turn should be kept")
    }

    @Test
    fun `preprocessor strips the seeded context block from a user message`() {
        val seeded = user("$CONTEXT_OPEN\nUser: earlier\nKit: reply\n$CONTEXT_CLOSE\n\nAre you sure?")

        val processed = ConversationContextPreProcessor().preprocess(listOf(seeded))

        assertEquals("Are you sure?", (processed.single() as Message.User).textContent())
    }

    @Test
    fun `preprocessor leaves clean user and assistant messages untouched`() {
        val messages = listOf(user("Just a question"), kit("An answer"))

        val processed = ConversationContextPreProcessor().preprocess(messages)

        assertEquals("Just a question", (processed[0] as Message.User).textContent())
        assertEquals("An answer", (processed[1] as Message.Assistant).textContent())
    }

    @Test
    fun `a seeded turn round-trips back to the clean message after preprocessing`() {
        val transcript = listOf(user("first"), kit("answer"))
        val current = "the new question"

        // What the render lambda produces for the LLM this turn: context block + the real message…
        val seededText = renderConversationContext(transcript, current) + current
        // …and what ChatMemory persists after the preprocessor runs on store:
        val stored = ConversationContextPreProcessor().preprocess(listOf(user(seededText)))

        // The persisted thread holds only the clean current turn — so it can't grow quadratically.
        assertEquals(current, (stored.single() as Message.User).textContent())
    }
}
