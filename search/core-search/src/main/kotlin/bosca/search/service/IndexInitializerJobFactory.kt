package bosca.search.service

import bosca.serialization.UUID

/**
 * Factory for enqueuing index initialization jobs that rebuild the search index
 * for a given storage system.
 *
 * When a storage system's index configuration changes or needs to be fully rebuilt,
 * implementations submit a background job that re-indexes all applicable content
 * into the search engine.
 */
interface IndexInitializerJobFactory {

    /**
     * Enqueues an asynchronous job to initialize or rebuild the search index
     * for the specified storage system.
     *
     * @param storageSystemId the unique identifier of the storage system whose index should be rebuilt
     */
    suspend fun enqueueJob(storageSystemId: UUID)
}