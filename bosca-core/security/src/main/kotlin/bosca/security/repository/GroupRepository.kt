package bosca.security.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.serialization.UUID
import kotlinx.coroutines.flow.Flow

@Repository
interface GroupRepository {

    @Query("select * from groups where (:type is null or type = (:type)::group_type) order by name limit :limit offset :offset")
    suspend fun getAll(type: GroupType?, offset: Long, limit: Int): List<Group>

    @Query("select * from groups where ((lower(name) like lower(:name) || '%') or (lower(description) like lower(:name) || '%')) and (:type is null or type = (:type)::group_type) order by name limit :limit offset :offset")
    suspend fun findByNameOrDescription(name: String, type: GroupType?, offset: Long, limit: Int): List<Group>

    @Query("select * from groups where name = :name and type = (:type)::group_type")
    suspend fun getGroupByName(name: String, type: GroupType): Group?

    @Query("select * from groups where id = :id")
    suspend fun getById(id: UUID): Group?

    @Query("select * from groups where id = any(:ids)")
    suspend fun getByIds(ids: List<UUID>): List<Group>

    @Query("insert into groups (name, description, type) values (:name, :description, (:type)::group_type) returning *")
    suspend fun add(group: Group): Group

    @Query("update groups set name = :name, description = :description where id = :id returning *")
    suspend fun update(group: Group): Group

    @Query("delete from groups where id = :id")
    suspend fun deleteById(id: UUID)
}
