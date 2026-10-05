package bosca.git.dfs

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Metadata for a single git packfile stored in ObjectStorage. JGit's
 * [BoscaDfsObjDatabase] uses these rows to enumerate available packs
 * without listing the storage bucket.
 */
@Serializable
data class DfsPack(
    @Contextual val id: UUID = UUID.NIL,
    @Contextual @ColumnName("repository_id") val repositoryId: UUID,
    @ColumnName("pack_name") val packName: String,
    @ColumnName("pack_source") val packSource: String = "INSERT",
    @ColumnName("file_size") val fileSize: Long = 0L,
    @ColumnName("object_count") val objectCount: Long = 0L,
    @ColumnName("delta_count") val deltaCount: Long = 0L,
    @ColumnName("min_update_idx") val minUpdateIdx: Long = 0L,
    @ColumnName("max_update_idx") val maxUpdateIdx: Long = 0L,
    val committed: Boolean = false,
    @ColumnName("gc_retained") val gcRetained: Boolean = false,
    @Contextual val created: OffsetDateTime = OffsetDateTime.now(),
    /**
     * When non-null, this pack has been replaced by GC/compaction and is awaiting
     * physical removal from ObjectStorage by the pack reaper. Soft-deleted packs
     * are excluded from all committed-pack lookups. See the V23 migration.
     */
    @ColumnName("deleted_at") @Contextual val deletedAt: OffsetDateTime? = null
)

/**
 * Maps a packfile extension (`.pack`, `.idx`, `.bitmap`, `.rev`) to its
 * location in ObjectStorage. JGit loads `.idx` files eagerly for fast
 * object lookups and streams `.pack` files on demand.
 */
@Serializable
data class DfsPackExtension(
    @Contextual @ColumnName("pack_id") val packId: UUID,
    val extension: String,
    @ColumnName("file_size") val fileSize: Long = 0L,
    @ColumnName("storage_path") val storagePath: String,
    @Contextual val created: OffsetDateTime = OffsetDateTime.now()
)
