package bosca.git.installer

import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.search.model.IndexConfiguration
import bosca.storage.model.StorageSystemInput
import bosca.storage.model.StorageSystemType
import bosca.storage.service.StorageSystemService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

class GitSearchIndexInstaller(
    private val storageSystemService: StorageSystemService,
    private val json: Json
) : PackageInstaller {

    override val version: String = "1.0.2"

    override suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion) {
        val existing = storageSystemService.getByName(STORAGE_SYSTEM_NAME)
        val input = StorageSystemInput(
            name = STORAGE_SYSTEM_NAME,
            type = StorageSystemType.SEARCH,
            description = "Git repository code and metadata search index",
            configuration = json.encodeToJsonElement(
                IndexConfiguration(
                    name = INDEX_NAME,
                    primaryKey = "id",
                    contentIndex = false,
                    filterable = FilterableAttributes,
                    sortable = SortableAttributes,
                    searchable = SearchableAttributes,
                )
            ),
            models = emptyList(),
        )
        if (existing != null) {
            storageSystemService.edit(existing.id, input)
        } else {
            storageSystemService.add(input)
        }
    }

    companion object {
        const val STORAGE_SYSTEM_NAME = "git-code"
        const val INDEX_NAME = "git-code"

        private val FilterableAttributes = listOf(
            "_type",
            "ownerId",
            "repositoryId",
            "visibility",
            "contentType",
            "archived",
            "defaultBranch",
            "language",
            "branches",
        )

        private val SortableAttributes = listOf(
            "name",
            "created",
            "updated",
            "diskSizeBytes",
            "filePath",
        )

        private val SearchableAttributes = listOf(
            "name",
            "slug",
            "description",
            "content",
            "filePath",
            "fileName",
        )
    }
}
