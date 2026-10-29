package bosca.storage.service

/** An independently owned store with inventory access, created by [ObjectStorageServiceFactory]. */
interface ManagedObjectStorageService : ObjectStorageService, AutoCloseable {
    /** Releases this store after its operations and open streams finish. Close on [StorageDispatcher]. */
    override fun close() {}

    /**
     * Reads the current object's length and available provider metadata without opening its body.
     *
     * This observes the object at lookup time; it does not pin a revision, verify a digest or
     * establish recovery coverage. Entity tags and modification times alone do not prove unchanged
     * content. Blocking metadata access runs on [StorageDispatcher].
     *
     * @throws ObjectNotFoundException when no object exists at [path]; other failures propagate
     */
    suspend fun getObjectInfo(path: ObjectPath): ObjectStorageInfo

    /**
     * Lists current object keys beginning with the literal [prefix], in backend key order.
     *
     * [offset] must be nonnegative and [limit] positive. An empty prefix selects the whole store.
     * Returns at most [limit] entries, or an empty page beyond the end. Reads no asset bodies.
     * Cloud stores use provider listing metadata without per-object requests; S3 listings have no version IDs.
     * Filesystem storage lists regular files, including paths reached through directory aliases.
     * Blocking enumeration runs on [StorageDispatcher]; failures and cancellation propagate.
     *
     * Enumeration does not pin source revisions or establish a closed inventory. Source changes
     * can shift offset positions between calls; callers establish exact capture/retention separately.
     */
    suspend fun listObjects(prefix: String, offset: Long, limit: Int): List<ObjectStorageEntry>

}
