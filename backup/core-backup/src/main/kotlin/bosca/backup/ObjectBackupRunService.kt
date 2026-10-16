package bosca.backup

import bosca.service.Service
import bosca.storage.service.ObjectStorageService

/**
 * Tracks fixed object inventories using the caller's connection-manager context.
 * [execute] and [executeRestore] own short database contexts around storage work.
 */
interface ObjectBackupRunService : Service {
    /** Atomically prepares backup intent and its exact retained-payload checkpoint associations. */
    suspend fun prepare(inventory: ObjectBackupManifest, destinationId: String): ObjectBackupRun

    /** Atomically fixes restore input, its manifest path and exact destination object checkpoints. */
    suspend fun prepareRestore(
        inventory: ObjectBackupManifest,
        destinationId: String,
        path: String,
    ): ObjectBackupRun

    /** Reads a persisted run without changing its attempt or historical result. */
    suspend fun get(key: String): ObjectBackupRun?

    /** Lists runs of one operation within a source/destination scope using offset pagination. */
    suspend fun list(
        operation: ObjectBackupOperation,
        sourceId: String,
        destinationId: String,
        scope: String,
        offset: Long,
        limit: Int,
    ): List<ObjectBackupRun>

    /** Returns the operation's latest completed inventory, separately from newer partial attempts. */
    suspend fun lastSuccessful(
        operation: ObjectBackupOperation,
        sourceId: String,
        destinationId: String,
        scope: String,
    ): ObjectBackupRun?

    /** Lists the run's current shared checkpoints in declared inventory order. */
    suspend fun items(key: String, offset: Long, limit: Int): List<ObjectBackupProgress>

    /** Reads current checkpoint health; terminal outcome totals remain frozen in the run's result. */
    suspend fun summary(key: String): ObjectBackupRunSummary

    /** Starts a pending or retryable terminal run using its observed version. */
    suspend fun start(key: String, expectedVersion: Long): ObjectBackupRun

    /** Explicitly queues a failed, partial or cancelled run for another attempt. */
    suspend fun retry(key: String, expectedVersion: Long): ObjectBackupRun

    /**
     * Acknowledges an inventory only when all required checkpoints are completed.
     * Callers verify restored objects or publish and verify backup manifests before this transition.
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

    /**
     * Restores the exact prepared inventory through independent backup storage.
     *
     * Requires a restore run and compares its stored input manifest before destination writes.
     * Uses the same isolated-destination and immutable-backup contract as
     * [ObjectBackupRestoreService.restore]. Progress storage remains outside any application
     * database being replaced. Earlier writers must have stopped before recovery. Completed or
     * cancelled redelivery returns the recorded outcome; cancelled runs require explicit [retry].
     */
    suspend fun executeRestore(
        key: String,
        backup: ObjectStorageService,
        destinations: Map<String, ObjectStorageService>,
    ): ObjectBackupRun
}
