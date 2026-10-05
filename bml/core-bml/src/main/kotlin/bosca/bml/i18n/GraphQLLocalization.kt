package bosca.bml.i18n

import bosca.bml.graphql.GraphQLClient
import bosca.bml.graphql.GraphQLException
import bosca.bml.render.BmlLocales
import java.util.Locale
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The site's localization definition, as its bound Bosca localization project declares it:
 * the project's source language plus its target languages — the ONLY locale
 * configuration BML has.
 */
class LocalizationProjectConfig(
    /** The project's source language: the site default and every lookup chain's terminal. */
    val sourceLocale: Locale,
    /** The negotiation policy: source language first (the default), then the target languages. */
    val locales: BmlLocales,
)

/**
 * Raw fetch layer over the localization GraphQL API — project config
 * (source + target languages) and per-locale string catalogs. Deliberately uncached and
 * throwing: [BmlLocalization] wraps it with the TTL cache, stale-while-revalidate, and the
 * never-throw degraded behavior; this class only knows how to ask.
 *
 * Uses the plain [GraphQLClient] data plane — never `LocalizationService` (bml-server carries
 * no Bosca domain registrars). Reads are server-initiated with the configured service [token],
 * independent of per-request user-token passthrough. The structured `strings` query (not a
 * format export) is deliberate: plural category rows must ride along, and `JSON_FLAT` cannot
 * carry them.
 */
class GraphQLLocalizationClient(
    private val gql: GraphQLClient,
    /** The bound localization project — a UUID, or a project name resolved via `projects`. */
    private val project: String,
    /** Service token for catalog/config reads (`BML_LOCALIZATION_TOKEN`). */
    private val token: String? = null,
    /** Which translation states render — `PUBLISHED` by default, matching the export default. */
    private val states: Set<String> = setOf("PUBLISHED"),
    private val pageSize: Int = 500,
) {

    @Volatile
    private var resolvedProjectId: String? = null

    /** The project's locale definition. Throws on transport/API failure — the cache layer copes. */
    suspend fun fetchConfig(): LocalizationProjectConfig {
        val projectId = projectId()
        val data = gql.execute(CONFIG_QUERY, buildJsonObject { put("id", projectId) }, "BmlLocalizationConfig", token)
        val projectEl = data.path("localization", "project")
            ?: throw GraphQLException("localization project '$project' ($projectId) not found")
        val source = projectEl.jsonObject["sourceLanguage"]?.jsonPrimitive?.content
            ?: throw GraphQLException("localization project '$project' has no sourceLanguage")
        val targets = projectEl.jsonObject["languages"]?.jsonArray.orEmpty()
            .mapNotNull { it.jsonObject["languageTag"]?.jsonPrimitive?.content }
        return LocalizationProjectConfig(
            sourceLocale = Locale.forLanguageTag(source),
            // Source first = the default; targets keep their declared order; dedupe guards a
            // project that also lists its source as a target.
            locales = BmlLocales(listOf(source) + targets.filterNot { it.equals(source, ignoreCase = true) }),
        )
    }

    /**
     * The full catalog for one exact locale: every string's translation in that locale (state
     * permitting) plus its plural category rows. Paginates `strings` until drained.
     */
    suspend fun fetchCatalog(locale: Locale): MessageCatalog {
        val projectId = projectId()
        val tag = locale.toLanguageTag()
        val messages = LinkedHashMap<String, String>()
        val plurals = LinkedHashMap<String, Map<PluralCategory, String>>()
        var offset = 0
        while (true) {
            val variables = buildJsonObject {
                put("id", projectId)
                put("tag", tag)
                put("offset", offset)
                put("limit", pageSize)
            }
            val data = gql.execute(CATALOG_QUERY, variables, "BmlLocalizationCatalog", token)
            val strings = data.path("localization", "project", "strings")?.jsonArray.orEmpty()
            for (string in strings) {
                val obj = string.jsonObject
                val key = obj["key"]?.jsonPrimitive?.content ?: continue
                val isPlural = obj["plural"]?.jsonPrimitive?.content == "true"
                if (isPlural) {
                    val forms = obj["pluralTranslations"]?.jsonArray.orEmpty()
                        .map { it.jsonObject }
                        .filter { row -> row["state"]?.jsonPrimitive?.content in states }
                        .mapNotNull { row ->
                            val category = row["pluralCategory"]?.jsonPrimitive?.content ?: return@mapNotNull null
                            val text = row["text"]?.jsonPrimitive?.content ?: return@mapNotNull null
                            PluralCategory.entries.firstOrNull { it.name == category }?.let { it to text }
                        }
                        .toMap()
                    if (forms.isNotEmpty()) plurals[key] = forms
                } else {
                    obj["translations"]?.jsonArray.orEmpty()
                        .map { it.jsonObject }
                        .firstOrNull { row ->
                            row["languageTag"]?.jsonPrimitive?.content.equals(tag, ignoreCase = true) &&
                                row["state"]?.jsonPrimitive?.content in states
                        }
                        ?.get("text")?.jsonPrimitive?.content
                        ?.let { messages[key] = it }
                }
            }
            if (strings.size < pageSize) break
            offset += pageSize
        }
        return MessageCatalog(messages, plurals)
    }

    // A UUID binds directly; a name resolves through `localization.projects` once and is kept
    // for the life of the source (project ids never change; a rebind is a redeploy).
    private suspend fun projectId(): String {
        resolvedProjectId?.let { return it }
        if (UUID_PATTERN.matches(project)) return project.also { resolvedProjectId = it }
        val data = gql.execute(PROJECTS_QUERY, null, "BmlLocalizationProjects", token)
        val match = data.path("localization", "projects")?.jsonArray.orEmpty()
            .map { it.jsonObject }
            .firstOrNull { it["name"]?.jsonPrimitive?.content.equals(project, ignoreCase = true) }
            ?: throw GraphQLException("localization project named '$project' not found")
        val id = match["id"]?.jsonPrimitive?.content
            ?: throw GraphQLException("localization project '$project' has no id")
        resolvedProjectId = id
        return id
    }

    private fun JsonElement.path(vararg keys: String): JsonElement? {
        var current: JsonElement = this
        for (key in keys) {
            val next = (current as? kotlinx.serialization.json.JsonObject)?.get(key) ?: return null
            if (next is JsonNull) return null
            current = next
        }
        return current
    }

    private companion object {
        val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        const val PROJECTS_QUERY = """
            query BmlLocalizationProjects {
                localization { projects { id name } }
            }"""

        const val CONFIG_QUERY = """
            query BmlLocalizationConfig(${'$'}id: UUID!) {
                localization {
                    project(id: ${'$'}id) {
                        sourceLanguage
                        languages { languageTag }
                    }
                }
            }"""

        const val CATALOG_QUERY = """
            query BmlLocalizationCatalog(${'$'}id: UUID!, ${'$'}tag: String!, ${'$'}offset: Int, ${'$'}limit: Int) {
                localization {
                    project(id: ${'$'}id) {
                        strings(offset: ${'$'}offset, limit: ${'$'}limit) {
                            key
                            plural
                            translations { languageTag text state }
                            pluralTranslations(languageTag: ${'$'}tag) { pluralCategory text state }
                        }
                    }
                }
            }"""
    }
}
