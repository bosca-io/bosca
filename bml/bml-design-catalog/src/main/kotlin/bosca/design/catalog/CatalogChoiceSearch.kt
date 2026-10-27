package bosca.design.catalog

import bosca.bml.graphql.GraphQLClient
import bosca.bml.render.BmlContractDispatcher
import kotlinx.coroutines.delay
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Searches deterministic local examples through BML's same-origin contract route. */
class CatalogChoiceSearch : BmlContractDispatcher {
    override val name: String = "CatalogChoices"
    override val methods: List<String> = listOf("search")

    /** Decodes one typed query argument and returns a select-compatible page. */
    override suspend fun dispatch(gql: GraphQLClient?, method: String, argsJson: String): String {
        require(method == "search") { "Unknown catalog choice method" }
        val request = Json.decodeFromString(ListSerializer(CatalogChoiceQuery.serializer()), argsJson).single()
        return Json.encodeToString(CatalogChoicePage.serializer(), search(request))
    }

    /** Filters labels and values before applying a three-option offset page. */
    suspend fun search(request: CatalogChoiceQuery): CatalogChoicePage {
        require(request.offset >= 0) { "Offset must be nonnegative" }
        delay(150)
        check(!request.fail) { "Catalog search failure requested" }
        val query = request.query.trim()
        if (query.length < 2) return CatalogChoicePage(emptyList())
        val matches = options.filter { it.label.contains(query, ignoreCase = true) || it.value.contains(query, ignoreCase = true) }
        val page = matches.drop(request.offset).take(PAGE_SIZE + 1)
        return CatalogChoicePage(
            options = page.take(PAGE_SIZE),
            previousOffset = if (request.offset > 0) maxOf(0, request.offset - PAGE_SIZE) else null,
            nextOffset = if (page.size > PAGE_SIZE) request.offset + PAGE_SIZE else null,
        )
    }

    companion object {
        private const val PAGE_SIZE = 3

        /** The complete native option list also serves the script-disabled example. */
        val options: List<CatalogChoice> = listOf(
            CatalogChoice("button", "Button states · control"),
            CatalogChoice("input", "Text inputs · control"),
            CatalogChoice("unavailable", "Unavailable sample · control", disabled = true),
            CatalogChoice("checkbox", "Checkbox choices · control"),
            CatalogChoice("switch", "Switch choices · control"),
            CatalogChoice("select", "Searchable selects · control"),
            CatalogChoice("search-input", "Search inputs · control"),
            CatalogChoice("segmented-control", "Segmented choices · control"),
            CatalogChoice("view-tabs", "Local view tabs · control"),
            CatalogChoice("card", "Cards · layout"),
            CatalogChoice("panel", "Panels · layout"),
            CatalogChoice("page-shell", "Page shells · layout"),
            CatalogChoice("page-heading", "Page headings with a deliberately long descriptive sample name · layout"),
            CatalogChoice("section", "Sections · layout"),
            CatalogChoice("dialog", "Dialogs · overlay"),
            CatalogChoice("popover", "Popovers · overlay"),
        )
    }
}
