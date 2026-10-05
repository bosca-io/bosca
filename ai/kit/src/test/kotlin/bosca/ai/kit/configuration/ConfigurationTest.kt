package bosca.ai.kit.configuration

import ai.koog.prompt.llm.LLMCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigurationTest {

    @Test
    fun `Kit default model supports native structured output`() {
        val model = Configuration().kitModels().default

        assertEquals("gpt-5.3-codex", model.id)
        assertTrue(model.supports(LLMCapability.Schema.JSON.Basic))
        assertTrue(model.supports(LLMCapability.Schema.JSON.Standard))
    }
}
