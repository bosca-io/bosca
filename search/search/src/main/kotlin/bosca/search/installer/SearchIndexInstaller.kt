package bosca.search.installer

import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.search.model.IndexConfiguration
import bosca.search.pipeline.SearchDocumentPipeline
import bosca.storage.model.StorageSystemInput
import bosca.storage.model.StorageSystemType
import bosca.storage.service.StorageSystemService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SearchIndexInstaller(
    private val storageSystemService: StorageSystemService,
    json: Json
) : PackageInstaller {
    override val version: String = "1.0.6"

    private val defaultSearchIndex = json.createSearchIndex(SearchDocumentPipeline.DEFAULT_INDEX, "default")
    private val adminSearchIndex = json.createSearchIndex(SearchDocumentPipeline.ADMIN_INDEX, "administration")
    private val profileSearchIndex = json.createSearchIndex(
        name = SearchDocumentPipeline.PROFILE_INDEX,
        indexName = "profiles",
        contentIndex = false,
        filterable = ProfileFilterableAttributes,
        sortable = ProfileSortableAttributes,
        searchable = ProfileSearchableAttributes,
    )

    private fun Json.createSearchIndex(
        name: String,
        indexName: String,
        contentIndex: Boolean = true,
        filterable: List<String> = FilterableAttributes,
        sortable: List<String> = SortableAttributes,
        searchable: List<String> = SearchableAttributes,
    ) = StorageSystemInput(
        name = name,
        type = StorageSystemType.SEARCH,
        description = name,
        configuration = encodeToJsonElement(
            IndexConfiguration(
                name = indexName,
                primaryKey = "id",
                contentIndex = contentIndex,
                filterable = filterable,
                sortable = sortable,
                searchable = searchable,
                embedders = listOf(),
                chat = null,
                chatSettings = listOf()
            )
        ),
        models = emptyList(),
    )

    override suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion) {
        val systems = storageSystemService.getAll().associateBy { (it.configuration.takeIf { it != JsonNull } ?: JsonObject(emptyMap())).jsonObject.getValue("indexName").jsonPrimitive.content }
        for (index in listOf(defaultSearchIndex, adminSearchIndex, profileSearchIndex)) {
            val indexName = index.configuration.takeIf { it != JsonNull }?.jsonObject?.getValue("indexName")?.jsonPrimitive?.content ?: ""
            val existing = systems[indexName]
            if (existing != null) {
                storageSystemService.edit(existing.id, index)
            } else {
                storageSystemService.add(index)
            }
        }
    }

    companion object {

        private val FilterableAttributes = listOf(
            "_type",
            "type",
            "contentId",
            "topics.id",
            "authors.id",
            "season",
            "episode",
            "characters",
            "labels",
            "languageTag",
            "contentType",
            "bibleBooks",
            "hasDocument",
            "hasGuide",
            "hasData",
            "memberCount",
            "country"
        )

        private val SortableAttributes = listOf(
            "name",
            "published",
            "created",
            "season",
            "episode",
            "modified",
            "memberCount",
            "country"
        )

        private val SearchableAttributes = listOf(
            "name",
            "description",
            "topics.name",
            "authors.name",
            "characters",
            "collections.name",
            "bibleBooks",
            "content"
        )

        private val ProfileFilterableAttributes = listOf(
            "_type",
            "type",
            "country",
        )

        private val ProfileSortableAttributes = listOf(
            "name",
            "created",
            "modified",
            "memberCount",
            "country",
        )

        private val ProfileSearchableAttributes = listOf(
            "name",
            "description",
        )
    }
}
