package bosca.ide.pipeline

data class BoscaPipeline(
    val serverProfileId: String,
    val repositoryId: String,
    val id: String,
    val name: String,
    val filePath: String,
    val triggerTypes: List<String>,
    val runs: List<BoscaPipelineRun>,
)

data class BoscaPipelineRun(
    val id: String,
    val number: Int,
    val status: String,
    val ref: String,
    val commitSha: String,
    val triggerType: String,
    val created: String,
    val started: String?,
    val finished: String?,
    val durationSeconds: Long?,
    val jobs: List<BoscaPipelineJob>,
    val artifacts: List<BoscaPipelineArtifact>,
)

data class BoscaPipelineJob(
    val id: String,
    val name: String,
    val status: String,
    val runnerLabel: String,
    val attempt: Int,
    val awaitingRequirements: Boolean,
    val awaitingApproval: Boolean,
    val approvalRequired: Boolean,
    val errorMessage: String?,
    val started: String?,
    val finished: String?,
    val steps: List<BoscaPipelineStep>,
)

data class BoscaPipelineStep(
    val id: String,
    val name: String,
    val ordinal: Int,
    val status: String,
    val exitCode: Int?,
    val errorMessage: String?,
    val started: String?,
    val finished: String?,
)

data class BoscaPipelineArtifact(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val created: String,
)

data class BoscaPipelineLogLine(
    val lineNumber: Int,
    val timestamp: String,
    val content: String,
    val stream: String,
)

internal fun String.isPipelineActive(): Boolean = this == "QUEUED" || this == "RUNNING"
internal fun String.isPipelineTerminal(): Boolean = this in setOf("SUCCESS", "FAILURE", "CANCELLED", "SKIPPED")
