package bosca.content.state.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

@DbMapper(WorkflowStateTypeMapper::class)
@Serializable
enum class WorkflowStateType {
    ADVERTISED,
    APPROVAL,
    APPROVED,
    DRAFT,
    FAILURE,
    PENDING,
    PROCESSING,
    PUBLISHED,
}

object WorkflowStateTypeMapper : EnumMapper<WorkflowStateType>({ WorkflowStateType.valueOf(it.uppercase()) })