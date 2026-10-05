package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import bosca.workops.model.bql.SavedFilter

/** Persists [SavedFilter] rows in `workops.saved_filter` (R10). */
@Repository
interface SavedFilterRepository {

    @Query("select * from workops.saved_filter where id = :id")
    suspend fun getById(id: UUID): SavedFilter?

    @Query("select * from workops.saved_filter where id = any(:ids)")
    suspend fun getByIds(ids: List<UUID>): List<SavedFilter>

    @Query(
        """
        select * from workops.saved_filter
        where owner_profile_id = :ownerProfileId
        order by name
        limit :limit offset :offset
        """
    )
    suspend fun listByOwner(ownerProfileId: UUID, offset: Long, limit: Int): List<SavedFilter>

    @Query(
        """
        insert into workops.saved_filter (owner_profile_id, name, description, bql_source, parsed_ast)
        values (:ownerProfileId, :name, :description, :bqlSource, :parsedAst::jsonb)
        returning *
        """
    )
    suspend fun add(filter: SavedFilter): SavedFilter

    @Query(
        """
        update workops.saved_filter
        set name = :name,
            description = :description,
            bql_source = :bqlSource,
            parsed_ast = :parsedAst::jsonb,
            modified_at = now(),
            version = version + 1
        where id = :id and version = :version
        returning *
        """
    )
    suspend fun update(filter: SavedFilter): SavedFilter?

    @Query("delete from workops.saved_filter where id = :id")
    suspend fun deleteById(id: UUID)
}
