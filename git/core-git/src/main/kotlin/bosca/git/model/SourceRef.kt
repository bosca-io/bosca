package bosca.git.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Links a script to a file in a Bosca-hosted git repository. The database
 * stores the deployed source as a cache; git is the source of truth. On push,
 * the sync flow reads the file at [path]@[ref], updates the script's source
 * field, and bumps its version.
 */
@Serializable
data class ScriptSourceRef(
    @Contextual @ColumnName("script_id") val scriptId: UUID,
    @Contextual @ColumnName("repository_id") val repositoryId: UUID,
    val path: String,
    val ref: String = "main",
    @ColumnName("resolved_commit") val resolvedCommit: String? = null
)

/**
 * Links an analytics query to a file in a Bosca-hosted git repository.
 * Same pattern as [ScriptSourceRef] — git is source of truth, the query's
 * SQL field in the database is a deployed cache.
 */
@Serializable
data class QuerySourceRef(
    @Contextual @ColumnName("query_id") val queryId: UUID,
    @Contextual @ColumnName("repository_id") val repositoryId: UUID,
    val path: String,
    val ref: String = "main",
    @ColumnName("resolved_commit") val resolvedCommit: String? = null
)
