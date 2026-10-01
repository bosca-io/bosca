package bosca.ai.models.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModelInputTest {

    @Test
    fun `ModelInput stores all properties`() {
        val input = ModelInput(key = "k", name = "n", description = "d", type = "google.Gemini2_5Pro", configuration = null)
        assertEquals("k", input.key)
        assertEquals("n", input.name)
        assertEquals("d", input.description)
        assertEquals("google.Gemini2_5Pro", input.type)
        assertNull(input.configuration)
    }
}
