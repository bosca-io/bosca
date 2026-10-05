package bosca.forms.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

/**
 * Categorizes a form schema by its intended usage pattern.
 *
 * - [SUBMISSION]: Forms that collect submissions from users (contact forms,
 *   signups, surveys). Displayed on the form submissions page.
 * - [INTERNAL]: Forms used within the admin for structured data editing
 *   (profile attributes, organization settings). Not submission-based.
 * - [WORK_OPS]: Forms that create Work Ops tasks on submission. The
 *   schema's `configuration` JSON carries a `workOps` object with
 *   `projectId`, optional `taskTypeId`, and `fieldMappings` that
 *   map form field keys to task fields (`summary`,
 *   `descriptionMarkdown`, etc.).
 */
@DbMapper(FormSchemaTypeMapper::class)
@Serializable
enum class FormSchemaType {
    SUBMISSION,
    INTERNAL,
    WORK_OPS,
}

object FormSchemaTypeMapper : EnumMapper<FormSchemaType>({ FormSchemaType.valueOf(it.uppercase()) })
