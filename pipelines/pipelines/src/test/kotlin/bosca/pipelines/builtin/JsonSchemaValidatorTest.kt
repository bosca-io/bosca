package bosca.pipelines.builtin

import bosca.pipelines.node.JsonSchemaValidator
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonSchemaValidatorTest {

    private fun validate(value: String, schema: String): List<String> =
        JsonSchemaValidator.validate(Json.parseToJsonElement(value), Json.parseToJsonElement(schema))

    @Test
    fun `a conforming object passes`() {
        val violations = validate(
            """{"email": "ada@x.io", "count": 3, "active": true}""",
            """
            {
              "type": "object",
              "required": ["email"],
              "properties": {
                "email": {"type": "string"},
                "count": {"type": "integer"},
                "active": {"type": "boolean"}
              }
            }
            """,
        )
        assertEquals(emptyList(), violations)
    }

    @Test
    fun `missing required fields and wrong types are all reported with paths`() {
        val violations = validate(
            """{"count": "three"}""",
            """
            {
              "type": "object",
              "required": ["email"],
              "properties": {
                "email": {"type": "string"},
                "count": {"type": "integer"}
              }
            }
            """,
        )
        assertEquals(2, violations.size, "expected both violations, got: $violations")
        assertTrue(violations.any { "missing required field 'email'" in it })
        assertTrue(violations.any { it.startsWith("$.count") && "integer" in it })
    }

    @Test
    fun `nested objects and arrays validate recursively`() {
        val violations = validate(
            """{"profile": {"name": 1}, "tags": ["a", 2]}""",
            """
            {
              "type": "object",
              "properties": {
                "profile": {
                  "type": "object",
                  "properties": {"name": {"type": "string"}}
                },
                "tags": {"type": "array", "items": {"type": "string"}}
              }
            }
            """,
        )
        assertTrue(violations.any { it.startsWith("$.profile.name") }, "nested object path: $violations")
        assertTrue(violations.any { it.startsWith("$.tags[1]") }, "array index path: $violations")
    }

    @Test
    fun `top-level type mismatch is reported`() {
        val violations = validate("""["not", "an", "object"]""", """{"type": "object"}""")
        assertEquals(listOf("$: expected object but got an array"), violations)
    }

    @Test
    fun `enum restricts values`() {
        val schema = """{"type": "object", "properties": {"state": {"type": "string", "enum": ["DRAFT", "LIVE"]}}}"""
        assertEquals(emptyList(), validate("""{"state": "LIVE"}""", schema))
        assertTrue(validate("""{"state": "GONE"}""", schema).any { "must be one of" in it })
    }

    @Test
    fun `null is allowed for optional fields but not required ones`() {
        val schema = """
            {
              "type": "object",
              "required": ["email"],
              "properties": {
                "email": {"type": "string"},
                "nickname": {"type": "string"}
              }
            }
        """
        assertEquals(emptyList(), validate("""{"email": "a@x.io", "nickname": null}""", schema))
        assertTrue(validate("""{"email": null}""", schema).any { "$.email" in it })
    }

    @Test
    fun `unknown schema keywords and undeclared fields are ignored`() {
        val violations = validate(
            """{"email": "a@x.io", "extra": 42}""",
            """{"type": "object", "properties": {"email": {"type": "string", "format": "email"}}, "additionalProperties": false}""",
        )
        assertEquals(emptyList(), violations, "the subset validator must not reject what it does not understand")
    }

    @Test
    fun `number accepts integers and decimals, integer rejects decimals`() {
        val schema = """{"type": "object", "properties": {"n": {"type": "number"}, "i": {"type": "integer"}}}"""
        assertEquals(emptyList(), validate("""{"n": 1.5, "i": 2}""", schema))
        assertEquals(emptyList(), validate("""{"n": 2}""", schema))
        assertTrue(validate("""{"i": 1.5}""", schema).any { "$.i" in it })
    }
}
