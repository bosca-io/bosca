package bosca.backup

import bosca.service.Service
import bosca.storage.configuration.StorageConfiguration
import bosca.storage.service.ManagedObjectStorageService

/** Associates backup storage identities with fixed configured addresses, independently of credentials. */
interface ObjectBackupStorageService : Service {
    /**
     * Reads only [keys] from backup.objectStores in application configuration, using literal IDs.
     * An empty selection needs no catalog. Missing or malformed selected entries fail on use;
     * unrelated entries are ignored. This performs no binding or storage I/O and returns runtime
     * credentials for internal execution only. No shared content-storage fallback is applied.
     */
    suspend fun configurations(keys: Set<String>): Map<String, StorageConfiguration>

    /**
     * Atomically binds selected application configurations in the caller's database context.
     * All selected entries and addresses validate before binding. Any binding conflict rolls back
     * the entire selection. Credentials are not persisted, and no store is opened.
     */
    suspend fun bindConfigured(keys: Set<String>): List<ObjectBackupStorage>

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
        block: suspend (ManagedObjectStorageService) -> T,
    ): T

    /**
     * Captures and atomically binds all selected configurations before opening any store.
     *
     * IDs are the keys of [configurations]. All claims roll back on a binding conflict. Already
     * opened stores close if a later store fails to open, or work fails or is cancelled. The caller
     * closes streams inside [block]. Active caller transactions are rejected before mutation or I/O.
     */
    suspend fun <T> withStorages(
        configurations: Map<String, StorageConfiguration>,
        block: suspend (Map<String, ManagedObjectStorageService>) -> T,
    ): T
}
