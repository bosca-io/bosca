package bosca.docs.installer

import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.search.model.IndexConfiguration
import bosca.storage.model.StorageSystemInput
import bosca.storage.model.StorageSystemType
import bosca.storage.service.StorageSystemService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DocsSearchIndexInstaller(
    private val storageSystemService: StorageSystemService,
    private val json: Json
) : PackageInstaller {
    override val version: String = "1.2.6"

    private val docsSearchIndex = StorageSystemInput(
        name = "API Documentation Index",
        type = StorageSystemType.SEARCH,
        description = "API Documentation Index for source code docs",
        configuration = json.encodeToJsonElement(
            IndexConfiguration(
                name = "api-documentation",
                primaryKey = "id",
                contentIndex = false,
                filterable = listOf("source", "module", "kind", "pkg", "category"),
                sortable = listOf("name", "module"),
                searchable = listOf("name", "qualifiedName", "signature", "description", "content", "module", "pkg", "category"),
                embedders = listOf(),
                chat = null,
                chatSettings = listOf()
            )
        ),
        models = emptyList(),
    )

    override suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion) {
        val systems = storageSystemService.getAll()
            .associateBy { it.configuration.jsonObject.getValue("indexName").jsonPrimitive.content }
        val indexName = docsSearchIndex.configuration.jsonObject.getValue("indexName").jsonPrimitive.content
        val existing = systems[indexName]
        if (existing != null) {
            storageSystemService.edit(existing.id, docsSearchIndex)
        } else {
            storageSystemService.add(docsSearchIndex)
        }
    }
}
