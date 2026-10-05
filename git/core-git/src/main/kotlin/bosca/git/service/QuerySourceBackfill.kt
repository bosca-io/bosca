package bosca.git.service

import bosca.serialization.UUID
import bosca.service.Service

/**
 * Bridges the git module's source-ref read side with whatever module owns the
 * analytics query records. Git uses this to trigger SQL writeback when a
 * source ref is created or when a manual repository sync is requested,
 * without taking a hard dependency on the analytics module. The
 * implementation lives in the analytics module.
 */
interface QuerySourceBackfill : Service {

    /**
     * Reads every query source ref in [repositoryId] at each branch's current
     * HEAD and writes the resulting SQL into the matching analytics queries.
     * Returns the number of queries whose SQL was changed.
     */
    suspend fun backfillRepository(repositoryId: UUID): Int

    /**
     * Reads the query source ref linked to [queryId] at its branch's current
     * HEAD and writes the SQL into the analytics query. Returns true when the
     * query was changed, false when there is no source ref, the file is
     * missing/binary, or the SQL already matches.
     */
    suspend fun backfillQuery(queryId: UUID): Boolean
}
