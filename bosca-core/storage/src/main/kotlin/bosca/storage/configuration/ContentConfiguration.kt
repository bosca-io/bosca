package bosca.storage.configuration

import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.server.BoscaApplication
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.ObjectStorageServiceFactory
import bosca.storage.service.StorageDispatcher
import bosca.storage.service.UrlSigner
import bosca.storage.service.UrlSignerImpl
import kotlinx.coroutines.withContext

@Providers
class ContentConfiguration {

    @Provider(singleton = true)
    fun urlSigner(application: BoscaApplication): UrlSigner {
        val secretKey = application.environment.config.propertyOrNull("content.urlSigner.secretKey")?.getString() ?: error("Missing 'content.urlSigner.secretKey' property")
        return UrlSignerImpl(secretKey)
    }

    @Provider(singleton = true)
    suspend fun objectStorage(
        application: BoscaApplication,
        factory: ObjectStorageServiceFactory,
    ): ObjectStorageService {
        val configuration = application.environment.config.propertyOrNull("content.storage")?.getAs<StorageConfiguration>()
            ?: StorageConfiguration("", "", null, "")
        return factory.create(configuration).also { storage ->
            application.onShutdown { withContext(StorageDispatcher) { storage.close() } }
        }
    }
}
