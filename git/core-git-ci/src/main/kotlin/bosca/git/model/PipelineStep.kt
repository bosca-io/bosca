package bosca.git.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * An individual step within a pipeline job. Steps execute sequentially
 * in [ordinal] order. Each step is either a shell command ([run]) or a
 * built-in action invocation ([uses]).
 */
@Serializable
data class PipelineStep(
    @Contextual val id: UUID = UUID.NIL,
    @Contextual @ColumnName("pipeline_job_id") val pipelineJobId: UUID,
    val name: String,
    val ordinal: Int,
    val status: PipelineRunStatus = PipelineRunStatus.QUEUED,
    val uses: String? = null,
    val run: String? = null,
    val image: String? = null,
    val condition: String? = null,
    @ColumnName("working_directory") val workingDirectory: String? = null,
    @ColumnName("with_args") val with: JsonElement = JsonObject(emptyMap()),
    val env: JsonElement = JsonObject(emptyMap()),
    @ColumnName("exit_code") val exitCode: Int? = null,
    @ColumnName("error_message") val errorMessage: String? = null,
    @Contextual val started: OffsetDateTime? = null,
    @Contextual val finished: OffsetDateTime? = null
)
