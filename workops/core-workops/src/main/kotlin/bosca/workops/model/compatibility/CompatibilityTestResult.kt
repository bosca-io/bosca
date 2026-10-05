package bosca.workops.model.compatibility

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Records whether a specific consumer version is compatible with a
 * specific provider version. Produced by compile-check pipelines
 * or contract test suites and used by release gates to verify
 * cross-repo compatibility before shipping.
 */
@Serializable
data class CompatibilityTestResult(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("consumer_project_id")
    @Contextual
    val consumerProjectId: UUID,
    @ColumnName("consumer_version_id")
    @Contextual
    val consumerVersionId: UUID,
    @ColumnName("provider_project_id")
    @Contextual
    val providerProjectId: UUID,
    @ColumnName("provider_version_id")
    @Contextual
    val providerVersionId: UUID,
    @ColumnName("test_suite")
    val testSuite: String,
    val status: CompatibilityStatus = CompatibilityStatus.PENDING,
    @ColumnName("breaking_changes_detected")
    val breakingChangesDetected: Boolean = false,
    @ColumnName("pipeline_run_id")
    @Contextual
    val pipelineRunId: UUID? = null,
    @ColumnName("result_url")
    val resultUrl: String? = null,
    @ColumnName("tested_at")
    @Contextual
    val testedAt: OffsetDateTime = OffsetDateTime.now(),
    val version: Long = 0,
)

@Serializable
enum class CompatibilityStatus { PASSED, FAILED, SKIPPED, PENDING }

@Serializable
data class RecordCompatibilityResultInput(
    @Contextual
    val consumerProjectId: UUID,
    @Contextual
    val consumerVersionId: UUID,
    @Contextual
    val providerProjectId: UUID,
    @Contextual
    val providerVersionId: UUID,
    val testSuite: String,
    val status: CompatibilityStatus,
    val breakingChangesDetected: Boolean = false,
    @Contextual
    val pipelineRunId: UUID? = null,
    val resultUrl: String? = null,
)
