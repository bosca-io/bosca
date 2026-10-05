package bosca.bml.features

import bosca.bml.graphql.GraphQLClient
import bosca.bml.graphql.GraphQLException
import bosca.bml.render.RenderContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FeatureFlagsTest {

    private data class Call(
        val query: String,
        val variables: JsonObject?,
        val operationName: String?,
        val token: String?,
    )

    private class ScriptedGraphQLClient(
        private val answer: (String?, JsonObject?) -> String,
    ) : GraphQLClient {
        val calls = mutableListOf<Call>()

        override suspend fun execute(
            query: String,
            variables: JsonObject?,
            operationName: String?,
            token: String?,
        ): JsonElement {
            calls += Call(query, variables, operationName, token)
            return Json.parseToJsonElement(answer(operationName, variables))
        }
    }

    private fun evaluation(
        flagKey: String,
        variationKey: String,
        value: String,
        degraded: Boolean = false,
        experimentId: String? = null,
    ): String = """
        {
          "flagKey": "$flagKey",
          "variationKey": "$variationKey",
          "value": $value,
          "experimentId": ${experimentId?.let { "\"$it\"" } ?: "null"},
          "degraded": $degraded
        }
    """.trimIndent()

    private fun response(featureFlags: String): String = """{"featureFlags":$featureFlags}"""

    @Test
    fun `evaluate forwards stable identity and token then caches the complete evaluation`() = runBlocking {
        val answer = evaluation("checkout", "compact", "true", experimentId = "experiment-1")
        val gql = ScriptedGraphQLClient { _, _ -> response("""{"evaluate":$answer}""") }
        val flags = FeatureFlags(gql, installationId = "installation-1", token = "Bearer token")

        val first = flags.evaluate("checkout")
        val second = flags.evaluate("checkout")

        assertSame(first, second)
        assertEquals("compact", first.variationKey)
        assertEquals(JsonPrimitive(true), first.value)
        assertEquals("experiment-1", first.experimentId)
        assertFalse(first.degraded)
        assertEquals(1, gql.calls.size)
        assertEquals("BmlEvaluateFeatureFlag", gql.calls.single().operationName)
        assertEquals("checkout", gql.calls.single().variables?.get("flagKey")?.jsonPrimitive?.content)
        assertEquals("installation-1", gql.calls.single().variables?.get("installationId")?.jsonPrimitive?.content)
        assertEquals("Bearer token", gql.calls.single().token)
        assertTrue("query BmlEvaluateFeatureFlag" in gql.calls.single().query)
        assertTrue("\$installationId: String!" in gql.calls.single().query)
    }

    @Test
    fun `evaluateAll returns only its snapshot and seeds later per-key evaluation`() = runBlocking {
        val unknown = evaluation("missing", "", "false", degraded = true)
        val a = evaluation("a", "on", "true")
        val b = evaluation("b", "blue", "\"navy\"")
        val gql = ScriptedGraphQLClient { operation, _ ->
            when (operation) {
                "BmlEvaluateFeatureFlag" -> response("""{"evaluate":$unknown}""")
                else -> response("""{"evaluateAll":[$a,$b]}""")
            }
        }
        val flags = FeatureFlags(gql, installationId = "installation-1")

        flags.evaluate("missing")
        val all = flags.evaluateAll()
        assertEquals(listOf("a", "b"), all.map { it.flagKey })
        assertSame(all, flags.evaluateAll())
        assertEquals("blue", flags.evaluate("b").variationKey)
        assertEquals(2, gql.calls.size, "one single evaluation plus one evaluateAll snapshot")
    }

    @Test
    fun `evaluateAll preserves an earlier value for an overlapping key`() = runBlocking {
        val first = evaluation("a", "on", "true")
        val changed = evaluation("a", "off", "false")
        val gql = ScriptedGraphQLClient { operation, _ ->
            if (operation == "BmlEvaluateFeatureFlag") response("""{"evaluate":$first}""")
            else response("""{"evaluateAll":[$changed]}""")
        }
        val flags = FeatureFlags(gql, installationId = "installation-1")

        assertEquals("on", flags.evaluate("a").variationKey)
        assertEquals("on", flags.evaluateAll().single().variationKey)
        assertEquals("on", flags.evaluate("a").variationKey)
    }

    @Test
    fun `a render without an installation identity uses degraded feature fallbacks`() = runBlocking {
        val gql = ScriptedGraphQLClient { _, _ -> error("GraphQL must not run") }
        val ctx = RenderContext(gql = gql)

        val evaluation = ctx.featureFlags.evaluate("offer")
        assertEquals("offer", evaluation.flagKey)
        assertEquals("", evaluation.variationKey)
        assertEquals(JsonNull, evaluation.value)
        assertTrue(evaluation.degraded)
        assertTrue(ctx.featureFlags.evaluateAll().isEmpty())
        assertTrue(gql.calls.isEmpty())
    }

    @Test
    fun `typed helpers and variation use defaults only for unknown or wrong JSON types`() = runBlocking {
        val gql = ScriptedGraphQLClient { _, variables ->
            val key = variables?.get("flagKey")?.jsonPrimitive?.content.orEmpty()
            val answer = when (key) {
                "enabled" -> evaluation(key, "on", "true")
                "label" -> evaluation(key, "beta", "\"Beta\"")
                "ratio" -> evaluation(key, "half", "0.5")
                "object" -> evaluation(key, "config", "{\"limit\":3}")
                "wrong" -> evaluation(key, "on", "\"yes\"")
                "boolean-string" -> evaluation(key, "on", "\"true\"")
                else -> evaluation(key, "", "false", degraded = true)
            }
            response("""{"evaluate":$answer}""")
        }
        val flags = FeatureFlags(gql, installationId = "installation-1")

        assertTrue(flags.enabled("enabled"))
        assertEquals("Beta", flags.string("label", "fallback"))
        assertEquals(0.5, flags.number("ratio", -1.0))
        assertEquals(Json.parseToJsonElement("{\"limit\":3}"), flags.value("object"))
        assertTrue(flags.variation("label", "beta"))
        assertFalse(flags.variation("label", "control"))
        assertFalse(flags.enabled("wrong"))
        assertFalse(flags.enabled("boolean-string"))
        assertEquals("fallback", flags.string("enabled", "fallback"))
        assertEquals(-1.0, flags.number("wrong", -1.0))
        assertTrue(flags.enabled("unknown", default = true))
        assertFalse(flags.variation("unknown", ""))
        assertEquals(JsonPrimitive("fallback"), flags.value("another-unknown", JsonPrimitive("fallback")))
    }

    @Test
    fun `blank keys remain invalid while malformed responses use degraded fallbacks`() {
        assertFailsWith<IllegalArgumentException> {
            runBlocking { FeatureFlags(ScriptedGraphQLClient { _, _ -> "{}" }).evaluate(" ") }
        }

        val missing = ScriptedGraphQLClient { _, _ -> """{"featureFlags":{}}""" }
        val missingResult = runBlocking { FeatureFlags(missing, installationId = "installation-1").evaluate("a") }
        assertEquals("a", missingResult.flagKey)
        assertEquals("", missingResult.variationKey)
        assertTrue(missingResult.degraded)

        val mismatch = evaluation("other", "on", "true")
        val mismatched = ScriptedGraphQLClient { _, _ -> """{"featureFlags":{"evaluate":$mismatch}}""" }
        assertTrue(runBlocking { FeatureFlags(mismatched, installationId = "installation-1").evaluate("a") }.degraded)

        val badDegraded = """{"flagKey":"a","variationKey":"on","value":true,"degraded":{}}"""
        val malformed = ScriptedGraphQLClient { _, _ -> """{"featureFlags":{"evaluate":$badDegraded}}""" }
        assertTrue(runBlocking { FeatureFlags(malformed, installationId = "installation-1").evaluate("a") }.degraded)

        val stringDegraded = """{"flagKey":"a","variationKey":"on","value":true,"degraded":"false"}"""
        val malformedString = ScriptedGraphQLClient { _, _ -> """{"featureFlags":{"evaluate":$stringDegraded}}""" }
        assertTrue(runBlocking { FeatureFlags(malformedString, installationId = "installation-1").evaluate("a") }.degraded)

        val nonArray = ScriptedGraphQLClient { _, _ -> """{"featureFlags":{"evaluateAll":null}}""" }
        assertTrue(runBlocking { FeatureFlags(nonArray, installationId = "installation-1").evaluateAll() }.isEmpty())
        val wrongCollectionType = ScriptedGraphQLClient { _, _ -> """{"featureFlags":{"evaluateAll":{}}}""" }
        assertTrue(runBlocking { FeatureFlags(wrongCollectionType, installationId = "installation-1").evaluateAll() }.isEmpty())
        assertEquals(JsonNull, FeatureFlagEvaluation("a", "", JsonNull).value)
    }

    @Test
    fun `transport failures use fallbacks but coroutine cancellation remains loud`() = runBlocking {
        val failed = FeatureFlags(
            ScriptedGraphQLClient { _, _ -> throw GraphQLException("flags unavailable") },
            installationId = "installation-1",
        )
        val failedEvaluation = failed.evaluate("offer")
        assertTrue(failedEvaluation.degraded)
        assertEquals(JsonNull, failedEvaluation.value)
        assertTrue(failed.evaluateAll().isEmpty())

        val cancelled = FeatureFlags(
            ScriptedGraphQLClient { _, _ -> throw CancellationException("cancelled") },
            installationId = "installation-1",
        )
        assertFailsWith<CancellationException> { cancelled.evaluate("offer") }
        assertFailsWith<CancellationException> { cancelled.evaluateAll() }
        Unit
    }
}
