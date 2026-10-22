package bosca.cli.api

import bosca.graphql.client.execute
import bosca.graphql.gen.backup.CancelPendingObjectBackupRun
import bosca.graphql.gen.backup.CancelPendingObjectBackupRunData
import bosca.graphql.gen.backup.DispatchObjectBackupRun
import bosca.graphql.gen.backup.DispatchObjectBackupRunData
import bosca.graphql.gen.backup.GetObjectBackupRun
import bosca.graphql.gen.backup.GetObjectBackupRunData
import bosca.graphql.gen.backup.GetObjectBackupRunItems
import bosca.graphql.gen.backup.GetObjectBackupRunItemsData
import bosca.graphql.gen.backup.ListObjectBackupRuns
import bosca.graphql.gen.backup.ListObjectBackupRunsData
import bosca.graphql.gen.backup.GetPostgresBackupRun
import bosca.graphql.gen.backup.GetPostgresBackupRunData
import bosca.graphql.gen.backup.ListPostgresBackupRuns
import bosca.graphql.gen.backup.ListPostgresBackupRunsData
import bosca.graphql.gen.backup.ObjectBackupOperation
import bosca.graphql.gen.backup.RetryObjectBackupRun
import bosca.graphql.gen.backup.RetryObjectBackupRunData

/** Typed backup administration operations through the canonical authenticated CLI transport. */
class BackupApi(network: NetworkClient) : Api(network) {
    /** Reads one exact request; null means no request was returned by the administration API. */
    suspend fun postgresRun(key: String): GetPostgresBackupRunData.Backups.PostgresRun? =
        network.boscaGraphql.execute(GetPostgresBackupRun, GetPostgresBackupRun.Variables(key)).backups.postgresRun

    /** Reads one bounded offset page in the exact source/storage scope. */
    suspend fun postgresRuns(
        sourceId: String,
        storageKey: String,
        offset: Int,
        limit: Int,
    ): List<ListPostgresBackupRunsData.Backups.PostgresRuns> =
        network.boscaGraphql.execute(
            ListPostgresBackupRuns, ListPostgresBackupRuns.Variables(sourceId, storageKey, offset, limit),
        ).backups.postgresRuns

    /** Reads one bounded page of recorded object runs in the exact operation and scope. */
    suspend fun objectRuns(
        operation: ObjectBackupOperation,
        sourceId: String,
        destinationId: String,
        scope: String,
        offset: Int,
        limit: Int,
    ): List<ListObjectBackupRunsData.Backups.ObjectRuns.Runs> =
        network.boscaGraphql.execute(
            ListObjectBackupRuns, ListObjectBackupRuns.Variables(operation, sourceId, destinationId, scope, offset, limit),
        ).backups.objectRuns.runs

    /** Reads fixed intent, the frozen outcome, and current checkpoint totals; null means no run. */
    suspend fun objectRun(key: String): GetObjectBackupRunData.Backups.ObjectRuns.Run? =
        network.boscaGraphql.execute(GetObjectBackupRun, GetObjectBackupRun.Variables(key)).backups.objectRuns.run

    /** Reads a saved checkpoint page; a missing run is distinct from an empty page. */
    suspend fun objectRunItems(
        key: String,
        offset: Int,
        limit: Int,
    ): GetObjectBackupRunItemsData.Backups.ObjectRuns.Run? =
        network.boscaGraphql.execute(
            GetObjectBackupRunItems, GetObjectBackupRunItems.Variables(key, offset, limit),
        ).backups.objectRuns.run

    /** Returns the observed retryable run to pending without dispatching the returned version. */
    suspend fun retryObjectRun(key: String, expectedVersion: Long): RetryObjectBackupRunData.Backups.ObjectRuns.Retry =
        network.boscaGraphql.execute(
            RetryObjectBackupRun, RetryObjectBackupRun.Variables(key, expectedVersion),
        ).backups.objectRuns.retry

    /** Requests asynchronous publication of the exact configured pending version. */
    suspend fun dispatchObjectRun(
        key: String,
        expectedVersion: Long,
    ): DispatchObjectBackupRunData.Backups.ObjectRuns.Dispatch =
        network.boscaGraphql.execute(
            DispatchObjectBackupRun, DispatchObjectBackupRun.Variables(key, expectedVersion),
        ).backups.objectRuns.dispatch

    /** Cancels only the observed pending version, preserving the server's reason and outcome. */
    suspend fun cancelPendingObjectRun(
        key: String,
        expectedVersion: Long,
        reason: String,
    ): CancelPendingObjectBackupRunData.Backups.ObjectRuns.CancelPending =
        network.boscaGraphql.execute(
            CancelPendingObjectBackupRun, CancelPendingObjectBackupRun.Variables(key, expectedVersion, reason),
        ).backups.objectRuns.cancelPending
}
