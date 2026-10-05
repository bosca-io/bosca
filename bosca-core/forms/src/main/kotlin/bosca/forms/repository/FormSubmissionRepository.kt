package bosca.forms.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.forms.model.FormSubmission
import bosca.forms.model.FormSubmissionStatus
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

@Repository
interface FormSubmissionRepository {

    @Query("select * from form_submissions where id = :id")
    suspend fun getById(id: UUID): FormSubmission?

    @Query("select * from form_submissions where form_schema_id = :formSchemaId order by created desc limit :limit offset :offset")
    suspend fun getByFormSchema(formSchemaId: UUID, offset: Long, limit: Int): List<FormSubmission>

    @Query("select * from form_submissions where profile_id = :profileId order by created desc limit :limit offset :offset")
    suspend fun getByProfile(profileId: UUID, offset: Long, limit: Int): List<FormSubmission>

    @Query("insert into form_submissions (form_schema_id, profile_id, attributes) values (:formSchemaId, :profileId, :attributes) returning *")
    suspend fun add(formSchemaId: UUID, profileId: UUID, attributes: JsonElement): FormSubmission

    @Query("update form_submissions set status = :status, modified = now() where id = :id")
    suspend fun setStatus(id: UUID, status: FormSubmissionStatus)

    @Query("delete from form_submissions where id = :id")
    suspend fun delete(id: UUID)
}
