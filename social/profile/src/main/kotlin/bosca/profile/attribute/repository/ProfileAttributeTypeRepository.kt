package bosca.profile.attribute.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.profile.attribute.model.ProfileAttributeType

@Repository
interface ProfileAttributeTypeRepository {

    @Query("select * from profile_attribute_types order by name asc")
    suspend fun getAll(): List<ProfileAttributeType>

    @Query("select * from profile_attribute_types where id = :id")
    suspend fun getById(id: String): ProfileAttributeType?

    @Query("insert into profile_attribute_types (id, name, description, visibility, protected, form_schema_id) values (:id, :name, :description, :visibility, :protected, :formSchemaId) returning *")
    suspend fun add(attributeType: ProfileAttributeType): ProfileAttributeType

    @Query("update profile_attribute_types set name = :name, description = :description, visibility = :visibility, protected = :protected, form_schema_id = :formSchemaId where id = :id returning *")
    suspend fun update(attributeType: ProfileAttributeType): ProfileAttributeType

    @Query("delete from profile_attribute_types where id = :id")
    suspend fun deleteById(id: String)
}