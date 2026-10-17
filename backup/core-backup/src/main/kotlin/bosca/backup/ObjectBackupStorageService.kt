package bosca.backup

import bosca.service.Service
import bosca.storage.configuration.StorageConfiguration
import bosca.storage.service.ObjectStorageService

/** Associates backup storage identities with fixed configured addresses, independently of credentials. */
interface ObjectBackupStorageService : Service {
    /** Reads the recorded address using the caller's connection-manager context. */
    suspend fun get(key: String): ObjectBackupStorage?

    /**
     * Records or checks an address atomically using the caller's connection-manager context.
     *
     * Filesystem paths are canonicalized. S3 addresses require an explicit region and an endpoint
     * override or AWS_ENDPOINT environment fallback. Conflicting identities or addresses fail.
     * Credentials and URL settings are never persisted. This does not open the store or verify
     * historical inventories, storage accounts, mount identity or arbitrary endpoint aliases.
     */
    suspend fun bind(key: String, configuration: StorageConfiguration): ObjectBackupStorage

    /**
     * Captures and binds the selected address in a short database context, then opens that store.
     *
     * The store closes afterward, including failure or cancellation. The caller must close streams
     * inside [block]. Address conflicts fail before opening or invoking work. The caller supplies
     * credentials, preserves the storage account, and establishes exclusive writer access.
     * An active caller transaction is rejected before binding or opening storage.
     */
    suspend fun <T> withStorage(
        key: String,
        configuration: StorageConfiguration,
        block: suspend (ObjectStorageService) -> T,
    ): T
}
