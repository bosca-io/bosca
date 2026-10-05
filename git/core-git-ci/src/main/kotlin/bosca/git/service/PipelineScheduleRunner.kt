package bosca.git.service

import bosca.serialization.UUID
import bosca.service.Service

/** Revalidates one due schedule occurrence and creates its pipeline run when eligible. */
interface PipelineScheduleRunner : Service {
    suspend fun run(scheduledJobId: UUID, pipelineId: UUID, executionPrincipalId: UUID)
}
