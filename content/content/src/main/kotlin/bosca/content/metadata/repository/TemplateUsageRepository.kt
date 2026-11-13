package bosca.content.metadata.repository

import bosca.content.metadata.model.TemplateUsage
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface TemplateUsageRepository {

    @Query("select count(*) from metadata where deleted = false and id <> :id and id in (select metadata_id from documents where template_metadata_id = :id union select metadata_id from data where template_metadata_id = :id union select metadata_id from guides where template_metadata_id = :id)")
    suspend fun countItems(id: UUID): Long

    @Query("select count(*) from collections where deleted = false and template_metadata_id = :id")
    suspend fun countCollections(id: UUID): Long

    @Query("select count(*) from metadata where deleted = false and id <> :id and id in (select metadata_id from guide_template_steps where template_metadata_id = :id union select metadata_id from guide_template_step_modules where template_metadata_id = :id)")
    suspend fun countTemplates(id: UUID): Long
}

suspend fun TemplateUsageRepository.getUsage(id: UUID) = TemplateUsage(
    items = countItems(id).toInt(),
    collections = countCollections(id).toInt(),
    templates = countTemplates(id).toInt(),
)
