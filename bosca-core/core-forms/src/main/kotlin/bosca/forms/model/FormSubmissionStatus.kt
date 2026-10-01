package bosca.forms.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

/**
 * The processing lifecycle status of a form submission,
 * tracking it from initial receipt through completion or rejection.
 */
@DbMapper(FormSubmissionStatusMapper::class)
@Serializable
enum class FormSubmissionStatus {
    PENDING,
    PROCESSED,
    REJECTED,
    SPAM,
    ARCHIVED
}

object FormSubmissionStatusMapper : EnumMapper<FormSubmissionStatus>({ FormSubmissionStatus.valueOf(it.uppercase()) })
