package bosca.backup

import bosca.service.Service
import bosca.storage.service.ObjectStorageService

/**
 * Tracks fixed object inventories using the caller's connection-manager context.
 * [execute] owns short database contexts around storage work and manifest publication.
 */
interface ObjectBackupRunService : Service {
    /** Atomically prepares immutable run intent and its exact object checkpoint associations. */
    suspend fun prepare(inventory: ObjectBackupManifest, destinationId: String): ObjectBackupRun

    /** Reads a persisted run without changing its attempt or historical result. */
    suspend fun get(key: String): ObjectBackupRun?

    /** Lists runs within one source/destination scope using offset pagination. */
    suspend fun list(
        sourceId: String,
        destinationId: String,
        scope: String,
        offset: Long,
        limit: Int,
    ): List<ObjectBackupRun>

    /** Returns the latest completed inventory, separately from newer partial attempts. */
    suspend fun lastSuccessful(sourceId: String, destinationId: String, scope: String): ObjectBackupRun?

    /** Lists the run's current shared checkpoints in declared inventory order. */
    suspend fun items(key: String, offset: Long, limit: Int): List<ObjectBackupProgress>

    /** Reads current checkpoint health; terminal outcome totals remain frozen in the run's result. */
    suspend fun summary(key: String): ObjectBackupRunSummary

    /** Starts a pending or retryable terminal run using its observed version. */
    suspend fun start(key: String, expectedVersion: Long): ObjectBackupRun

    /** Explicitly queues a failed, partial or cancelled run for another attempt. */
    suspend fun retry(key: String, expectedVersion: Long): ObjectBackupRun

    /**
     * Acknowledges a published inventory only when all required checkpoints are completed.
     * Callers publish and verify the manifest before invoking this transition.
     */
    suspend fun complete(key: String, expectedVersion: Long): ObjectBackupRun

    /** Records failure or a partial outcome after the earlier writer has stopped. */
    suspend fun fail(key: String, expectedVersion: Long, error: String): ObjectBackupRun

    /** Cancels a pending run, or records a running writer's cancellation after it stops. */
    suspend fun cancel(key: String, expectedVersion: Long, error: String): ObjectBackupRun

    /**
     * Transfers and publishes an inventory outside an active database transaction.
     *
     * [sources] addresses the declared logical stores, and [destination] matches the run's
     * destination identity. The caller pins source revisions, owns exclusive payload/manifest
     * writer access and establishes that any prior run writer has stopped.
     * Completed/cancelled runs return their historical result. Cancelled runs require [retry];
     * use a new inventory identity for a new copy of completed content.
     */
    suspend fun execute(
        key: String,
        sources: Map<String, ObjectStorageService>,
        destination: ObjectStorageService,
    ): ObjectBackupRun
}
