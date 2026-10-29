package bosca.storage.service

/** Preserves the application's no-storage configuration for factory-created stores. */
internal class ManagedNoOpObjectStorageService : ManagedObjectStorageService,
    ObjectStorageService by NoOpObjectStorageService() {
    override suspend fun getObjectInfo(path: ObjectPath): ObjectStorageInfo = throw ObjectNotFoundException(path)

    override suspend fun listObjects(prefix: String, offset: Long, limit: Int): List<ObjectStorageEntry> {
        require(offset >= 0) { "Object listing offset must not be negative" }
        require(limit > 0) { "Object listing limit must be positive" }
        return emptyList()
    }
}
