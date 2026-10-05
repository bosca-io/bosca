package bosca.profile.organization.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.profile.organization.model.Organization
import bosca.serialization.UUID

@Repository
interface OrganizationRepository {

    @Query("select * from organizations order by name limit :limit offset :offset")
    suspend fun getAll(offset: Long, limit: Int): List<Organization>

    @Query("select * from organizations where id = :id")
    suspend fun getById(id: UUID): Organization?

    @Query("select * from organizations where profile_id = :id")
    suspend fun getByProfileId(id: UUID): Organization?

    @Query("select * from organizations where id = any(:ids)")
    suspend fun getByIds(ids: List<UUID>): List<Organization>

    @Query("insert into organizations (name, attributes, system_attributes, visibility, profile_id, created, modified) values (:name, :attributes, :systemAttributes, :visibility, :profileId, now(), now()) returning *")
    suspend fun add(organization: Organization): Organization

    @Query("update organizations set name = :name, attributes = :attributes, system_attributes = :systemAttributes, visibility = :visibility, profile_id = :profileId, modified = now() where id = :id returning *")
    suspend fun edit(organization: Organization): Organization

    @Query("delete from organizations where id = :id")
    suspend fun deleteById(id: UUID)
}
