package bosca.storage.service

import bosca.service.Service
import bosca.storage.configuration.StorageConfiguration

/** Opens independently configured object stores without changing the application's content store. */
interface ObjectStorageServiceFactory : Service {
    /**
     * Creates an independent store on the storage I/O dispatcher.
     *
     * The caller owns the store and must close it after all operations and open streams finish.
     * Application-scoped callers should close it during application shutdown. Bucket creation
     * is opt-in through [StorageConfiguration.createBucket].
     */
    suspend fun create(configuration: StorageConfiguration): ObjectStorageService

    /**
     * Opens the configured store and runs [block] on the storage I/O dispatcher.
     *
     * The store and any open streams must stay inside [block]. Each S3 scope owns
     * its client and closes it after success, failure or cancellation. Nested scopes are
     * independent. Work failures and cancellation propagate; bucket creation is opt-in through
     * [StorageConfiguration.createBucket]. This does not establish backup coverage or retention.
     */
    suspend fun <T> withStorage(
        configuration: StorageConfiguration,
        block: suspend (ObjectStorageService) -> T,
    ): T
}
