package bosca.profile.profile.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.serialization.UUID
import kotlinx.coroutines.flow.Flow

@Repository
interface ProfileRepository {

    @Query("select * from profiles order by id limit :limit offset :offset")
    suspend fun getAll(offset: Long, limit: Int): List<Profile>

    @Query("select * from profiles where type = :type order by id limit :limit offset :offset")
    suspend fun getAllByType(offset: Long, limit: Int, type: ProfileType): List<Profile>

    @Query("select * from profiles where id = any(:ids)")
    suspend fun getAllByIds(ids: List<UUID>): List<Profile>

    @Query("select * from profiles where id = :id")
    suspend fun getById(id: UUID): Profile?

    @Query("select * from profiles where lower(name) = any(:names) order by id")
    suspend fun getByNames(names: List<String>): List<Profile>

    @Query("select * from profiles where principal = :principalId order by created desc")
    suspend fun getByPrincipal(principalId: UUID): List<Profile>

    /**
     * Returns profiles whose linked principal is a member of the named group.
     * Distinct because a single principal could in theory match more than once
     * if duplicate group rows existed; the `distinct on (p.id)` keeps the
     * shape of the result strictly one-row-per-profile and makes pagination
     * stable across calls.
     */
    @Query(
        """
        select distinct on (p.id) p.*
        from profiles p
        join principal_groups pg on pg.principal = p.principal
        join groups g on g.id = pg.group_id
        where g.name = :groupName
        order by p.id, p.created desc
        offset :offset limit :limit
        """,
    )
    suspend fun getByGroupName(groupName: String, offset: Long, limit: Int): List<Profile>

    @Query("insert into profiles (principal, name, visibility, searchable, collection_id, type) values (:principal, :name, :visibility, :searchable, :collectionId, (:type)::profile_type) returning *")
    suspend fun add(profile: Profile): Profile

    @Query("update profiles set name = :name, visibility = :visibility, searchable = :searchable, modified = now() where id = :id returning *")
    suspend fun update(profile: Profile): Profile

    @Query("update profiles set principal = :principalId, modified = now() where id = :id returning *")
    suspend fun setPrincipal(id: UUID, principalId: UUID): Profile

    @Query("update profiles set principal = null, modified = now() where id = :id returning *")
    suspend fun clearPrincipal(id: UUID): Profile

    @Query("update profiles set collection_id = :collectionId, modified = now() where id = :id returning *")
    suspend fun setCollectionId(id: UUID, collectionId: UUID): Profile

    @Query("update profiles set modified = now() where id = :id")
    suspend fun setModified(id: UUID)

    /** Stages the profile for deletion (reversible). */
    @Query("update profiles set deleted_at = now(), modified = now() where id = :id returning *")
    suspend fun markDeleted(id: UUID): Profile

    /** Clears the soft-delete marker. */
    @Query("update profiles set deleted_at = null, modified = now() where id = :id returning *")
    suspend fun restore(id: UUID): Profile

    @Query("delete from profiles where id = :id")
    suspend fun deleteById(id: UUID)
}
