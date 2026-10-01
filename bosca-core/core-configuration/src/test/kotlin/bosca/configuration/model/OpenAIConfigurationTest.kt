package bosca.configuration.model

import kotlin.test.Test
import kotlin.test.assertEquals

class OpenAIConfigurationTest {

    @Test
    fun fieldsArePreserved() {
        val config = OpenAIConfiguration(key = "sk-test-123")
        assertEquals("sk-test-123", config.key)
    }

    @Test
    fun companionKeyConstant() {
        assertEquals("openai", OpenAIConfiguration.KEY)
    }

    @Test
    fun dataClassEquality() {
        val a = OpenAIConfiguration(key = "key1")
        val b = OpenAIConfiguration(key = "key1")
        assertEquals(a, b)
    }
}
