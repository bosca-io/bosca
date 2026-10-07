package bosca.artifacts.sync

import bosca.artifacts.service.BlobStorageService
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers

@Providers
class ArtifactSyncConfiguration {
    @Provider(singleton = true)
    fun ghcrClient(blobs: BlobStorageService): GhcrClient = GhcrClient(blobs)
}
