package bosca.bml.i18n

import bosca.bml.graphql.GraphQLClient
import bosca.bml.graphql.GraphQLException
import java.util.Locale
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The raw localization fetch layer: project resolution, config parsing,
 * catalog assembly (pagination, state filtering, plural rows), and token forwarding — against a
 * scripted [GraphQLClient].
 */
class GraphQLLocalizationClientTest {

    private val projectId = "6a4f0b6e-7f3a-4b1e-9a5d-2c8e1f000001"

    /** Answers by operationName; records every call for assertions. */
    private class ScriptedGql(
        private val answers: (operationName: String?, variables: JsonObject?) -> String,
    ) : GraphQLClient {
        val calls = mutableListOf<Triple<String?, JsonObject?, String?>>() // (op, vars, token)
        override suspend fun execute(
            query: String,
            variables: JsonObject?,
            operationName: String?,
            token: String?,
        ): JsonElement {
            calls += Triple(operationName, variables, token)
            return Json.parseToJsonElement(answers(operationName, variables))
        }
    }

    private fun configAnswer(): String = """
        {"localization": {"project": {
            "sourceLanguage": "en",
            "languages": [{"languageTag": "es-419"}, {"languageTag": "pt-BR"}, {"languageTag": "en"}]
        }}}"""

    private fun stringsAnswer(strings: String): String =
        """{"localization": {"project": {"strings": [$strings]}}}"""

    @Test
    fun `config derives the policy source-first and dedupes the source from targets`() {
        val gql = ScriptedGql { op, _ -> if (op == "BmlLocalizationConfig") configAnswer() else "{}" }
        val config = runBlocking {
            GraphQLLocalizationClient(gql, projectId, token = "svc-token").fetchConfig()
        }
        assertEquals("en", config.sourceLocale.toLanguageTag())
        assertEquals(listOf("en", "es-419", "pt-BR"), config.locales.supported.map { it.toLanguageTag() })
        assertEquals("en", config.locales.default.toLanguageTag())
        assertEquals("svc-token", gql.calls.single().third, "the service token must ride every read")
    }

    @Test
    fun `a project name resolves through the projects query once and is cached`() {
        val gql = ScriptedGql { op, _ ->
            when (op) {
                "BmlLocalizationProjects" ->
                    """{"localization": {"projects": [
                        {"id": "other", "name": "Other Site"},
                        {"id": "$projectId", "name": "Acme Site"}
                    ]}}"""
                else -> configAnswer()
            }
        }
        val client = GraphQLLocalizationClient(gql, "acme site")
        runBlocking {
            client.fetchConfig()
            client.fetchConfig()
        }
        assertEquals(
            1,
            gql.calls.count { it.first == "BmlLocalizationProjects" },
            "name resolution must happen once: ${gql.calls.map { it.first }}",
        )
    }

    @Test
    fun `an unknown project name fails loudly for the cache layer to absorb`() {
        val gql = ScriptedGql { _, _ -> """{"localization": {"projects": []}}""" }
        assertFailsWith<GraphQLException> {
            runBlocking { GraphQLLocalizationClient(gql, "nope").fetchConfig() }
        }
    }

    @Test
    fun `catalog assembles plain and plural strings, filtering by locale and state`() {
        val gql = ScriptedGql { _, _ ->
            stringsAnswer(
                """
                {"key": "home.title", "plural": false, "translations": [
                    {"languageTag": "es-419", "text": "Bienvenido", "state": "PUBLISHED"},
                    {"languageTag": "es-419", "text": "Borrador", "state": "DRAFT"},
                    {"languageTag": "en", "text": "Welcome", "state": "PUBLISHED"}
                ], "pluralTranslations": []},
                {"key": "cart.items", "plural": true, "translations": [], "pluralTranslations": [
                    {"pluralCategory": "ONE", "text": "un artículo", "state": "PUBLISHED"},
                    {"pluralCategory": "OTHER", "text": "{count} artículos", "state": "PUBLISHED"},
                    {"pluralCategory": "MANY", "text": "no listo", "state": "IN_REVIEW"}
                ]},
                {"key": "empty.here", "plural": false, "translations": [
                    {"languageTag": "en", "text": "English only", "state": "PUBLISHED"}
                ], "pluralTranslations": []}
                """.trimIndent(),
            )
        }
        val catalog = runBlocking {
            GraphQLLocalizationClient(gql, projectId).fetchCatalog(Locale.forLanguageTag("es-419"))
        }
        assertEquals("Bienvenido", catalog.message("home.title"), "published es-419 text, not the draft")
        assertNull(catalog.message("empty.here"), "another locale's text must not leak in")
        assertEquals("un artículo", catalog.plural("cart.items", PluralCategory.ONE))
        assertEquals(
            "{count} artículos",
            catalog.plural("cart.items", PluralCategory.MANY),
            "unpublished MANY row is filtered; category falls back to OTHER",
        )
    }

    @Test
    fun `catalog paginates until a short page`() {
        val pageSize = 2
        val gql = ScriptedGql { op, vars ->
            if (op != "BmlLocalizationCatalog") return@ScriptedGql "{}"
            when (vars?.get("offset")?.jsonPrimitive?.content) {
                "0" -> stringsAnswer(
                    """{"key": "a", "plural": false, "translations": [{"languageTag": "en", "text": "A", "state": "PUBLISHED"}], "pluralTranslations": []},
                       {"key": "b", "plural": false, "translations": [{"languageTag": "en", "text": "B", "state": "PUBLISHED"}], "pluralTranslations": []}""",
                )
                else -> stringsAnswer(
                    """{"key": "c", "plural": false, "translations": [{"languageTag": "en", "text": "C", "state": "PUBLISHED"}], "pluralTranslations": []}""",
                )
            }
        }
        val catalog = runBlocking {
            GraphQLLocalizationClient(gql, projectId, pageSize = pageSize).fetchCatalog(Locale.ENGLISH)
        }
        assertEquals(3, catalog.messages.size, "both pages must land: ${catalog.messages.keys}")
        assertEquals(2, gql.calls.count { it.first == "BmlLocalizationCatalog" })
    }

    @Test
    fun `widened states admit review translations when configured`() {
        val gql = ScriptedGql { _, _ ->
            stringsAnswer(
                """{"key": "wip", "plural": false, "translations": [
                    {"languageTag": "en", "text": "Preview text", "state": "IN_REVIEW"}
                ], "pluralTranslations": []}""",
            )
        }
        val catalog = runBlocking {
            GraphQLLocalizationClient(gql, projectId, states = setOf("PUBLISHED", "IN_REVIEW"))
                .fetchCatalog(Locale.ENGLISH)
        }
        assertEquals("Preview text", catalog.message("wip"))
    }
}
