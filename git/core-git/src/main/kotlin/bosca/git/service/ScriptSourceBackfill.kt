package bosca.git.service

import bosca.serialization.UUID
import bosca.service.Service

/**
 * Counterpart to [QuerySourceBackfill] for the scripting module. Triggers
 * source-ref writeback when a manual sync is requested or a script source
 * ref is freshly linked. Implementation lives in the scripting module to
 * keep the git module free of script-specific knowledge.
 */
interface ScriptSourceBackfill : Service {

    /**
     * Reads every script source ref in [repositoryId] at each branch's
     * current HEAD and writes the resulting source into the matching
     * script records. Returns the number of scripts whose source was
     * changed.
     */
    suspend fun backfillRepository(repositoryId: UUID): Int

    /**
     * Reads the script source ref linked to [scriptId] at its branch's
     * current HEAD and writes the source into the script. Returns true
     * when the script was changed, false when there is no source ref,
     * the file is missing/binary, or the source already matches.
     */
    suspend fun backfillScript(scriptId: UUID): Boolean
}
