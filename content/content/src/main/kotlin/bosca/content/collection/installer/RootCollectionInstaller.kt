package bosca.content.collection.installer

import bosca.content.collection.model.CollectionInput
import bosca.content.collection.service.CollectionService
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonObject

class RootCollectionInstaller(
    private val collectionService: CollectionService
) : PackageInstaller {
    override val version: String = "1.0.0"

    override suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion) {
        collectionService.getById(UUID.NIL)?.let { return }
        collectionService.addRoot(CollectionInput(
            name = "Root",
            attributes = JsonObject(emptyMap())
        ))
    }
}