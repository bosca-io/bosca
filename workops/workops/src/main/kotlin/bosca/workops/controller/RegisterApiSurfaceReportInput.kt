package bosca.workops.controller

import bosca.serialization.UUID
import bosca.workops.model.artifact.BreakingChangeLevel
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class RegisterApiSurfaceReportInput(
    @Contextual val projectId: UUID,
    @Contextual val versionId: UUID,
    @Contextual val previousVersionId: UUID,
    @Contextual val artifactPublicationId: UUID? = null,
    val breakingChangeLevel: BreakingChangeLevel,
    @Contextual val changes: JsonElement,
    val analyzerTool: String,
    val reportUrl: String? = null,
)
