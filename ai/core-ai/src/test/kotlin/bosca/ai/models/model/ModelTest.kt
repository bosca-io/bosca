package bosca.ai.models.model

import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModelTest {

    private fun model(type: String) = Model(key = "test", type = type, name = "Test", description = "Test model")

    // --- Google models ---

    @Test
    fun `toLLMModel maps Gemini2_5Pro correctly`() {
        assertEquals(GoogleModels.Gemini2_5Pro, model("google.Gemini2_5Pro").toLLMModel())
    }

    @Test
    fun `toLLMModel maps Gemini2_5Flash correctly`() {
        assertEquals(GoogleModels.Gemini2_5Flash, model("google.Gemini2_5Flash").toLLMModel())
    }

    @Test
    fun `toLLMModel maps Gemini2_0Flash001 to FlashLite001`() {
        assertEquals(GoogleModels.Gemini2_0FlashLite001, model("google.Gemini2_0Flash001").toLLMModel())
    }

    @Test
    fun `toLLMModel maps Gemini2_5FlashLite correctly`() {
        assertEquals(GoogleModels.Gemini2_5FlashLite, model("google.Gemini2_5FlashLite").toLLMModel())
    }

    @Test
    fun `toLLMModel maps legacy Gemini3_Pro_Preview to Gemini3_1Pro_Preview`() {
        assertEquals(GoogleModels.Gemini3_1Pro_Preview, model("google.Gemini3_Pro_Preview").toLLMModel())
    }

    @Test
    fun `toLLMModel maps Gemini3_1Pro_Preview correctly`() {
        assertEquals(GoogleModels.Gemini3_1Pro_Preview, model("google.Gemini3_1Pro_Preview").toLLMModel())
    }

    @Test
    fun `toLLMModel throws for unsupported Google model`() {
        val error = assertFailsWith<IllegalStateException> {
            model("google.UnknownModel").toLLMModel()
        }
        assertEquals("Unsupported model: UnknownModel", error.message)
    }

    // --- OpenAI chat models ---

    @Test
    fun `toLLMModel maps GPT4o correctly`() {
        assertEquals(OpenAIModels.Chat.GPT4o, model("openai.chat.GPT4o").toLLMModel())
    }

    @Test
    fun `toLLMModel maps GPT4oMini correctly`() {
        assertEquals(OpenAIModels.Chat.GPT4oMini, model("openai.chat.GPT4oMini").toLLMModel())
    }

    @Test
    fun `toLLMModel maps GPT4_1 correctly`() {
        assertEquals(OpenAIModels.Chat.GPT4_1, model("openai.chat.GPT4_1").toLLMModel())
    }

    @Test
    fun `toLLMModel maps GPT4_1Mini correctly`() {
        assertEquals(OpenAIModels.Chat.GPT4_1Mini, model("openai.chat.GPT4_1Mini").toLLMModel())
    }

    @Test
    fun `toLLMModel maps GPT5 correctly`() {
        assertEquals(OpenAIModels.Chat.GPT5, model("openai.chat.GPT5").toLLMModel())
    }

    @Test
    fun `toLLMModel maps GPT5Mini correctly`() {
        assertEquals(OpenAIModels.Chat.GPT5Mini, model("openai.chat.GPT5Mini").toLLMModel())
    }

    @Test
    fun `toLLMModel maps GPT5_1 correctly`() {
        assertEquals(OpenAIModels.Chat.GPT5_1, model("openai.chat.GPT5_1").toLLMModel())
    }

    @Test
    fun `toLLMModel maps GPT5_1Codex correctly`() {
        assertEquals(OpenAIModels.Chat.GPT5_1Codex, model("openai.chat.GPT5_1Codex").toLLMModel())
    }

    @Test
    fun `toLLMModel maps GPT5_2 correctly`() {
        assertEquals(OpenAIModels.Chat.GPT5_2, model("openai.chat.GPT5_2").toLLMModel())
    }

    @Test
    fun `toLLMModel maps GPT5_2Pro correctly`() {
        assertEquals(OpenAIModels.Chat.GPT5_2Pro, model("openai.chat.GPT5_2Pro").toLLMModel())
    }

    @Test
    fun `toLLMModel throws for unsupported OpenAI chat model`() {
        val error = assertFailsWith<IllegalStateException> {
            model("openai.chat.UnknownModel").toLLMModel()
        }
        assertEquals("Unsupported model: UnknownModel", error.message)
    }

    // --- Unsupported type ---

    @Test
    fun `toLLMModel throws for completely unsupported type`() {
        val error = assertFailsWith<IllegalStateException> {
            model("anthropic.claude").toLLMModel()
        }
        assertEquals("Unsupported model type: anthropic.claude", error.message)
    }

    // --- Data class properties ---

    @Test
    fun `Model has correct default id`() {
        val m = Model(key = "k", type = "t", name = "n", description = "d")
        assertEquals(kotlin.uuid.Uuid.NIL, m.id)
    }

    @Test
    fun `Model configuration defaults to null`() {
        val m = Model(key = "k", type = "t", name = "n", description = "d")
        assertEquals(null, m.configuration)
    }
}
