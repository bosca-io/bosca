package bosca.cli.api

import bosca.graphql.client.execute
import bosca.graphql.gen.backup.GetPostgresBackupRun
import bosca.graphql.gen.backup.GetPostgresBackupRunData
import bosca.graphql.gen.backup.ListPostgresBackupRuns
import bosca.graphql.gen.backup.ListPostgresBackupRunsData

/** Typed administration queries through the canonical authenticated CLI transport. */
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
}
