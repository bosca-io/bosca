package bosca.content.collection.installer

import bosca.content.collection.model.CollectionInput
import bosca.content.collection.service.CollectionService
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.slug.service.SlugService
import kotlinx.serialization.json.JsonObject

class BiblesCollectionInstaller(
    private val collectionService: CollectionService,
    private val slugService: SlugService
) : PackageInstaller {
    override val version: String = "1.0.0"

    override suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion) {
        if (slugService.get("bibles") != null) return
        collectionService.add(
            CollectionInput(
                name = "Bibles",
                slug = "bibles",
                attributes = JsonObject(emptyMap())
            )
        )
    }
}