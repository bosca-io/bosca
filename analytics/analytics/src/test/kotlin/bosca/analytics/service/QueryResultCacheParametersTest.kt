package bosca.analytics.service

import bosca.analytics.model.AnalyticsQueryExecutionParameterInput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class QueryResultCacheParametersTest {

    private val json = Json

    private fun param(name: String, value: String) =
        AnalyticsQueryExecutionParameterInput(name, JsonPrimitive(value))

    @Test
    fun `same parameters in different order produce the same hash`() {
        val a = QueryResultCacheParameters.canonicalize(json, listOf(param("a", "1"), param("b", "2")))
        val b = QueryResultCacheParameters.canonicalize(json, listOf(param("b", "2"), param("a", "1")))
        assertEquals(a.hash, b.hash)
        assertEquals(a.parameters, b.parameters)
    }

    @Test
    fun `null values remain part of an already-effective cache identity`() {
        val a = QueryResultCacheParameters.canonicalize(json, listOf(param("a", "1")))
        val b = QueryResultCacheParameters.canonicalize(
            json,
            listOf(param("a", "1"), AnalyticsQueryExecutionParameterInput("b", JsonNull)),
        )
        assertNotEquals(a.hash, b.hash)
    }

    @Test
    fun `duplicate parameter names remain distinguishable and are rejected before canonicalization`() {
        val a = QueryResultCacheParameters.canonicalize(json, listOf(param("a", "1"), param("a", "2")))
        val b = QueryResultCacheParameters.canonicalize(json, listOf(param("a", "2")))
        assertNotEquals(a.hash, b.hash)
    }

    @Test
    fun `different values produce different hashes`() {
        val a = QueryResultCacheParameters.canonicalize(json, listOf(param("a", "1")))
        val b = QueryResultCacheParameters.canonicalize(json, listOf(param("a", "2")))
        assertNotEquals(a.hash, b.hash)
    }

    @Test
    fun `empty and all-null parameter lists remain distinguishable`() {
        val a = QueryResultCacheParameters.canonicalize(json, emptyList())
        val b = QueryResultCacheParameters.canonicalize(
            json,
            listOf(AnalyticsQueryExecutionParameterInput("a", JsonNull)),
        )
        assertNotEquals(a.hash, b.hash)
    }

    @Test
    fun `object key order does not affect cache identity`() {
        val a = QueryResultCacheParameters.canonicalize(
            json,
            listOf(AnalyticsQueryExecutionParameterInput("value", JsonObject(mapOf("b" to JsonPrimitive(2), "a" to JsonPrimitive(1))))),
        )
        val b = QueryResultCacheParameters.canonicalize(
            json,
            listOf(AnalyticsQueryExecutionParameterInput("value", JsonObject(mapOf("a" to JsonPrimitive(1), "b" to JsonPrimitive(2))))),
        )
        assertEquals(a.hash, b.hash)
    }

    @Test
    fun `nested arrays preserve order while canonicalizing their objects`() {
        val a = QueryResultCacheParameters.canonicalize(
            json,
            listOf(
                AnalyticsQueryExecutionParameterInput(
                    "value",
                    JsonArray(listOf(JsonObject(mapOf("b" to JsonPrimitive(2), "a" to JsonPrimitive(1))))),
                ),
            ),
        )
        val b = QueryResultCacheParameters.canonicalize(
            json,
            listOf(
                AnalyticsQueryExecutionParameterInput(
                    "value",
                    JsonArray(listOf(JsonObject(mapOf("a" to JsonPrimitive(1), "b" to JsonPrimitive(2))))),
                ),
            ),
        )

        assertEquals(a.hash, b.hash)
        assertEquals(a.parameters, b.parameters)
    }
}
