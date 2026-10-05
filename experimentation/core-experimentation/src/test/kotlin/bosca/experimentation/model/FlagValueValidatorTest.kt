package bosca.experimentation.model

import bosca.experimentation.model.Variation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class FlagValueValidatorTest {

    @Test
    fun `BOOLEAN accepts true and false`() {
        assertNull(FlagValueValidator.check(FlagType.BOOLEAN, JsonPrimitive(true)))
        assertNull(FlagValueValidator.check(FlagType.BOOLEAN, JsonPrimitive(false)))
    }

    @Test
    fun `BOOLEAN rejects strings even if they spell true`() {
        assertNotNull(FlagValueValidator.check(FlagType.BOOLEAN, JsonPrimitive("true")))
        assertNotNull(FlagValueValidator.check(FlagType.BOOLEAN, JsonPrimitive("yes")))
    }

    @Test
    fun `BOOLEAN rejects numbers`() {
        assertNotNull(FlagValueValidator.check(FlagType.BOOLEAN, JsonPrimitive(1)))
        assertNotNull(FlagValueValidator.check(FlagType.BOOLEAN, JsonPrimitive(0)))
    }

    @Test
    fun `BOOLEAN rejects objects and arrays`() {
        assertNotNull(FlagValueValidator.check(FlagType.BOOLEAN, buildJsonObject { put("a", 1) }))
        assertNotNull(FlagValueValidator.check(FlagType.BOOLEAN, buildJsonArray { add(JsonPrimitive(true)) }))
    }

    @Test
    fun `STRING accepts string primitives`() {
        assertNull(FlagValueValidator.check(FlagType.STRING, JsonPrimitive("hello")))
        assertNull(FlagValueValidator.check(FlagType.STRING, JsonPrimitive("")))
    }

    @Test
    fun `STRING rejects numbers and booleans`() {
        assertNotNull(FlagValueValidator.check(FlagType.STRING, JsonPrimitive(42)))
        assertNotNull(FlagValueValidator.check(FlagType.STRING, JsonPrimitive(true)))
    }

    @Test
    fun `PERCENTAGE accepts numbers in 0 to 100 range`() {
        assertNull(FlagValueValidator.check(FlagType.PERCENTAGE, JsonPrimitive(0)))
        assertNull(FlagValueValidator.check(FlagType.PERCENTAGE, JsonPrimitive(50)))
        assertNull(FlagValueValidator.check(FlagType.PERCENTAGE, JsonPrimitive(100)))
        assertNull(FlagValueValidator.check(FlagType.PERCENTAGE, JsonPrimitive(33.33)))
    }

    @Test
    fun `PERCENTAGE rejects out of range`() {
        assertNotNull(FlagValueValidator.check(FlagType.PERCENTAGE, JsonPrimitive(-1)))
        assertNotNull(FlagValueValidator.check(FlagType.PERCENTAGE, JsonPrimitive(101)))
    }

    @Test
    fun `PERCENTAGE rejects strings even if numeric`() {
        assertNotNull(FlagValueValidator.check(FlagType.PERCENTAGE, JsonPrimitive("50")))
    }

    @Test
    fun `JSON accepts any non-null shape`() {
        assertNull(FlagValueValidator.check(FlagType.JSON, JsonPrimitive(true)))
        assertNull(FlagValueValidator.check(FlagType.JSON, JsonPrimitive("hello")))
        assertNull(FlagValueValidator.check(FlagType.JSON, JsonPrimitive(42)))
        assertNull(FlagValueValidator.check(FlagType.JSON, buildJsonObject { put("nested", true) }))
        assertNull(FlagValueValidator.check(FlagType.JSON, buildJsonArray { add(JsonPrimitive(1)) }))
    }

    @Test
    fun `all types reject null`() {
        for (type in FlagType.entries) {
            assertNotNull(FlagValueValidator.check(type, JsonNull), "$type should reject null")
        }
    }

    @Test
    fun `validate throws with context label`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            FlagValueValidator.validate(FlagType.BOOLEAN, JsonPrimitive("nope"), "default value")
        }
        assertEquals(true, ex.message?.contains("default value"))
        assertEquals(true, ex.message?.contains("BOOLEAN"))
    }

    @Test
    fun `validate succeeds silently when value matches`() {
        FlagValueValidator.validate(FlagType.STRING, JsonPrimitive("ok"), "default value")
    }

    // -- validateVariations tests --

    @Test
    fun `validateVariations rejects empty variation list`() {
        assertFailsWith<IllegalArgumentException> {
            FlagValueValidator.validateVariations(FlagType.BOOLEAN, emptyList())
        }
    }

    @Test
    fun `validateVariations rejects duplicate keys`() {
        val variations = listOf(
            Variation(key = "on", name = "On", value = JsonPrimitive(true)),
            Variation(key = "on", name = "On", value = JsonPrimitive(false)),
        )
        val ex = assertFailsWith<IllegalArgumentException> {
            FlagValueValidator.validateVariations(FlagType.BOOLEAN, variations)
        }
        assertEquals(true, ex.message?.contains("Duplicate variation key"))
    }

    @Test
    fun `validateVariations rejects keys containing comma`() {
        val variations = listOf(
            Variation(key = "on,off", name = "OnOff", value = JsonPrimitive(true)),
        )
        val ex = assertFailsWith<IllegalArgumentException> {
            FlagValueValidator.validateVariations(FlagType.BOOLEAN, variations)
        }
        assertEquals(true, ex.message?.contains("must not contain"))
    }

    @Test
    fun `validateVariations rejects keys containing unit separator`() {
        val variations = listOf(
            Variation(key = "on\u001Foff", name = "OnOff", value = JsonPrimitive(true)),
        )
        assertFailsWith<IllegalArgumentException> {
            FlagValueValidator.validateVariations(FlagType.BOOLEAN, variations)
        }
    }

    @Test
    fun `validateVariations rejects type-mismatched value`() {
        val variations = listOf(
            Variation(key = "control", name = "Control", value = JsonPrimitive(true)),
            Variation(key = "treatment", name = "Treatment", value = JsonPrimitive("not-a-bool")),
        )
        val ex = assertFailsWith<IllegalArgumentException> {
            FlagValueValidator.validateVariations(FlagType.BOOLEAN, variations)
        }
        assertEquals(true, ex.message?.contains("treatment"))
    }

    @Test
    fun `validateVariations accepts valid variation list`() {
        val variations = listOf(
            Variation(key = "control", name = "Control", value = JsonPrimitive(false)),
            Variation(key = "treatment", name = "Treatment", value = JsonPrimitive(true)),
        )
        FlagValueValidator.validateVariations(FlagType.BOOLEAN, variations)
    }
}
