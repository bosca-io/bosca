package bosca.workops.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.bml.render.currentRenderContext
import bosca.design.model.FlowGraph
import bosca.design.model.SchemaFormOption
import bosca.workops.ui.graphql.*
import bosca.workops.ui.graphql.WorkOpsReleasePlansData.WorkOps.CrossProject.ReleasePlans as ReleasePlan
import bosca.workops.ui.graphql.WorkOpsReleaseNativeRunData.Git.PipelineRun as NativeRun
import bosca.workops.ui.graphql.WorkOpsReleaseNativeRunData.Git.PipelineRun.Jobs as NativeJob
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** WorkOps owns release intent; existing WorkOps/Git APIs own execution, authorization and durability. */
@Serializable
class WorkOpsReleaseCiModel(
    val id: String,
    val read: WorkOpsReleaseCiReadModel = WorkOpsReleaseCiReadModel(id),
    var launching: WorkOpsReleaseCiLaunchDraft? = null,
    var confirming: WorkOpsReleaseCiAction? = null,
    var patching: Boolean = false,
    var patchValues: JsonObject = JsonObject(emptyMap()),
    var comments: Map<String, String> = emptyMap(),
    var rollbackRevisionDraft: String = "0",
    var rollbackOutcomes: List<String> = emptyList(),
    var nextHref: String = "",
    var draftRevision: Long = 0,
    var revision: Long = 0,
    val feedback: WorkOpsFeedback = WorkOpsFeedback(),
) {
    val launchTitle get() = if (launching?.trigger == "PROMOTION") "Promote to ${launching?.environment}" else "Start release"
    val launchInputs get() = launching?.inputs ?: WorkOpsReleaseCiInputs()
    val launchValues get() = launching?.values ?: JsonObject(emptyMap())
    val hasDialog get() = launching != null || confirming != null || patching
    val confirmingControl get() = confirming?.kind in setOf("cancelRun", "cancelJob", "rerunJob")
    val controlTitle get() = when (confirming?.kind) { "cancelRun" -> "Cancel run"; "cancelJob" -> "Cancel job"; else -> "Rerun job" }
    fun graph(plan: ReleasePlan): FlowGraph = plan.releaseGraph()
    fun plansForEnvironment(environment: String) = read.promotionPlans.filter { it.environment == environment }
    fun phase(plan: ReleasePlan): String = read.runs.firstOrNull { run ->
        run.pipelineId == plan.pipelineId && run.triggerType.name == plan.triggerType &&
            (plan.triggerType != "PROMOTION" || run.parameter("promotion.environment") == plan.environment)
    }?.status?.name ?: if (!read.summariesLoaded || read.runErrors.isNotEmpty()) "Unavailable" else "No recent run"
    fun active(status: String) = status in setOf("QUEUED", "RUNNING")
    fun rerunnable(status: String) = status in setOf("FAILURE", "CANCELLED")
    fun tone(status: String) = when (status) { "SUCCESS" -> "success"; "FAILURE" -> "error"; "RUNNING" -> "info"; "QUEUED" -> "warning"; else -> "neutral" }
    fun runHref(id: String) = "/pipelines/${workOpsEncode(id)}"
    fun runRef(run: NativeRun) = run.parameter("promotion.environment") ?: run.ref
    fun runLabel(id: String) = read.runs.firstOrNull { it.id == id }?.let { "Run #${it.number}" } ?: "Run details unavailable"
    fun parkedReason(run: NativeRun, job: NativeJob): String = when {
        job.awaitingApproval -> "Awaiting approval"
        job.awaitingRequirements -> (requirementLabels(job.requirements) + requirementLabels(job.pipelineRequirements))
            .takeIf { it.isNotEmpty() }?.joinToString(", ", "Waiting for ") ?: "Waiting for requirements"
        job.status == GitPipelineRunStatus.QUEUED -> job.dependsOn.filter { name -> run.jobs.firstOrNull { it.name == name }?.status != GitPipelineRunStatus.SUCCESS }
            .takeIf { it.isNotEmpty() }?.joinToString(", ", "Waiting for ").orEmpty()
        else -> ""
    }
    private fun requirementLabels(value: JsonElement?): List<String> = (value as? JsonArray).orEmpty().map { entry ->
        val item = entry as? JsonObject
        fun text(key: String) = (item?.get(key) as? JsonPrimitive)?.contentOrNull
        if (text("pipeline") != null) "${text("repository").orEmpty()}/${text("pipeline")}@${text("ref") ?: "this ref"}"
        else listOfNotNull(text("type"), text("namespace"), text("coordinate")).joinToString(":").ifBlank { (entry as? JsonPrimitive)?.contentOrNull ?: entry.toString() }
    }
    val patchProjects get() = read.components.distinctBy { it.projectId }
    val patchChoices get() = patchProjects.map { SchemaFormOption(it.projectId, it.projectLabel, disabled = it.projectName.isBlank()) }
    val patchSchema get() = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { putJsonObject("projects") {
            put("type", "array"); put("minItems", 1)
            putJsonObject("items") { put("type", "string"); put("enum", JsonArray(patchProjects.map { JsonPrimitive(it.projectId) })) }
            put("default", JsonArray(emptyList()))
        } }
        put("required", JsonArray(listOf(JsonPrimitive("projects"))))
    }
    val patchUiSchema get() = buildJsonObject {
        put("version", 1)
        put("layout", JsonArray(listOf(buildJsonObject { put("type", "field"); put("property", "projects"); put("label", "Affected projects"); put("control", "tag-input") })))
    }

    private fun snapshot() = read.summaries.joinToString { "${it.runId}:${it.status}" } +
        read.components.joinToString { "${it.versionId}:${it.status}" } + read.release?.released.orEmpty()
    suspend fun refresh(ctx: RenderContext) {
        val before = snapshot(); read.retry(ctx)
        if (!read.feedback.failed && before != snapshot()) revision++
    }
    fun selectRun(id: String) {
        require(read.summaries.any { it.runId == id }) { "Choose a run from this release." }
        read.selectedRunId = id
    }
    suspend fun openStart() = feedback.perform {
        require(read.startReason == null) { read.startReason.orEmpty() }
        launching = WorkOpsReleaseCiLaunchDraft("RELEASE", inputs = WorkOpsReleaseCiInputs.from(read.releasePlans)); draftRevision++
    }
    suspend fun openPromotion(environment: String) = feedback.perform {
        require(read.promotionReason(environment) == null) { read.promotionReason(environment).orEmpty() }
        launching = WorkOpsReleaseCiLaunchDraft("PROMOTION", environment,
            WorkOpsReleaseCiInputs.from(read.promotionPlans.filter { it.environment == environment })); draftRevision++
    }
    fun cancelLaunch() { launching = null }
    suspend fun launch(raw: String, allowDowngrade: Boolean, ctx: RenderContext) = feedback.perform {
        val target = requireNotNull(launching) { "Review a release action first." }
        val values = requireNotNull(Json.parseToJsonElement(raw) as? JsonObject) { "Release inputs must be an object." }
        launching = target.copy(values = values, allowDowngrade = allowDowngrade)
        val payload = target.inputs.payload(raw)
        val accepted = when (target.trigger) {
            "RELEASE" -> ctx.gql.execute(WorkOpsLaunchRelease, WorkOpsLaunchRelease.Variables(id, payload)).workOps.crossProject.launchRelease
            "PROMOTION" -> ctx.gql.execute(WorkOpsPromoteRelease, WorkOpsPromoteRelease.Variables(id,
                PromoteWorkOpsReleaseInput(target.environment, allowDowngrade, payload))).workOps.crossProject.promoteRelease
            else -> error("Unknown release trigger.")
        }
        require(accepted) { "The release action was not started." }
        launching = null; revision++
        feedback.message = if (target.trigger == "PROMOTION") "Promotion requested." else "Release started."
        read.retry(ctx)
    }
    suspend fun openPatch() = feedback.perform {
        require(read.canManage) { "Manage permission on the release program is required." }
        require(read.componentsLoaded) { "Bundled versions are unavailable." }
        patching = true; patchValues = buildJsonObject { put("projects", JsonArray(emptyList())) }; draftRevision++
    }
    fun cancelPatch() { patching = false }
    suspend fun patch(raw: String, ctx: RenderContext) = feedback.perform {
        require(patching) { "Open the patch release dialog first." }
        patchValues = requireNotNull(Json.parseToJsonElement(raw) as? JsonObject) { "Patch choices must be an object." }
        val values = requireNotNull(patchValues["projects"] as? JsonArray) { "Select the affected projects." }
        val selected = values.map { requireNotNull((it as? JsonPrimitive)?.contentOrNull) { "Choose available projects." } }.distinct()
        require(selected.isNotEmpty() && selected.all { project -> patchProjects.any { it.projectId == project } }) { "Choose available affected projects." }
        val created = ctx.gql.execute(WorkOpsPatchRelease, WorkOpsPatchRelease.Variables(id, StartWorkOpsPatchReleaseInput(selected))).workOps.crossProject.startPatchRelease
        nextHref = "/workops/releases/${workOpsEncode(created.id)}"; patching = false
        feedback.message = "Patch release created."
    }

    fun controlReason(kind: String, run: NativeRun, job: NativeJob? = null): String? = when {
        kind in setOf("cancelRun", "cancelJob") && !read.canEdit(run.repositoryId) -> "Edit permission on the repository is required."
        kind == "rerunJob" && !read.canExecute(run.repositoryId) -> "Execute permission on the repository is required."
        kind == "cancelRun" && run.status !in activeStatuses -> "The run is no longer active."
        kind == "cancelJob" && (job == null || job.status !in activeStatuses) -> "The job is no longer active."
        kind == "rerunJob" && (run.status !in failureStatuses || job == null || job.status !in failureStatuses) -> "Wait for a failed or cancelled run before rerunning this job."
        kind !in setOf("cancelRun", "cancelJob", "rerunJob") -> "Choose an available run action."
        else -> null
    }
    suspend fun openControl(kind: String, runId: String, jobId: String) = feedback.perform {
        val run = requireNotNull(read.runs.firstOrNull { it.id == runId }) { "Refresh the run details first." }
        val job = jobId.takeIf(String::isNotBlank)?.let { chosen -> requireNotNull(run.jobs.firstOrNull { it.id == chosen }) { "The job does not belong to this run." } }
        require(controlReason(kind, run, job) == null) { controlReason(kind, run, job).orEmpty() }
        confirming = WorkOpsReleaseCiAction(kind, runId, jobId, label = job?.name ?: "Run #${run.number}")
    }
    fun cancelConfirmation() { confirming = null }
    suspend fun control(confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        require(confirmed) { "Confirm this run action." }
        val target = requireNotNull(confirming) { "Choose a run action first." }
        val run = requireNotNull(ctx.gql.execute(WorkOpsReleaseNativeRun, WorkOpsReleaseNativeRun.Variables(target.runId)).git.pipelineRun) { "Run details are unavailable." }
        val job = target.jobId.takeIf(String::isNotBlank)?.let { chosen -> requireNotNull(run.jobs.firstOrNull { it.id == chosen }) { "The job does not belong to this run." } }
        require(controlReason(target.kind, run, job) == null) { controlReason(target.kind, run, job).orEmpty() }
        when (target.kind) {
            "cancelRun" -> ctx.gql.execute(WorkOpsCancelReleaseRun, WorkOpsCancelReleaseRun.Variables(target.runId))
            "cancelJob" -> ctx.gql.execute(WorkOpsCancelReleaseJob, WorkOpsCancelReleaseJob.Variables(target.jobId))
            "rerunJob" -> ctx.gql.execute(WorkOpsRerunReleaseJob, WorkOpsRerunReleaseJob.Variables(target.jobId))
            else -> error("Choose an available run action.")
        }
        confirming = null; revision++
        feedback.message = if (target.kind == "rerunJob") "Job rerun requested." else "Cancellation requested."
        read.retry(ctx)
    }
    fun decisionReason(run: NativeRun, job: NativeJob): String? = when {
        !read.canExecute(run.repositoryId) -> "Execute permission on the repository is required."
        !read.canExecuteEnvironment(job.environment) -> "Execute permission on ${job.environment} is required."
        !job.awaitingApproval -> "This job is not awaiting approval."
        else -> null
    }
    suspend fun decide(jobId: String, comment: String, approve: Boolean, ctx: RenderContext) = feedback.perform {
        comments = comments + (jobId to comment)
        val selected = requireNotNull(read.selectedRun) { "Select an available run first." }
        val run = requireNotNull(ctx.gql.execute(WorkOpsReleaseNativeRun, WorkOpsReleaseNativeRun.Variables(selected.id)).git.pipelineRun) { "Run details are unavailable." }
        val job = requireNotNull(run.jobs.firstOrNull { it.id == jobId }) { "The job does not belong to this run." }
        require(decisionReason(run, job) == null) { decisionReason(run, job).orEmpty() }
        val text = comment.trim().takeIf(String::isNotBlank)
        if (approve) ctx.gql.execute(WorkOpsApproveReleaseJob, WorkOpsApproveReleaseJob.Variables(jobId, text))
        else ctx.gql.execute(WorkOpsRejectReleaseJob, WorkOpsRejectReleaseJob.Variables(jobId, text))
        comments = comments - jobId; revision++
        feedback.message = if (approve) "Job approved." else "Job rejected."
        read.retry(ctx)
    }

    suspend fun openAttemptRollback() = feedback.perform {
        require(read.rollbackAttemptReason == null) { read.rollbackAttemptReason.orEmpty() }
        confirming = WorkOpsReleaseCiAction("rollbackAttempt", label = read.release?.name.orEmpty())
    }
    suspend fun rollbackAttempt(confirmed: Boolean, ctx: RenderContext) = feedback.perform {
        require(confirmed) { "Confirm rollback of this release attempt." }
        require(confirming?.kind == "rollbackAttempt") { "Review the attempt rollback first." }
        require(ctx.gql.execute(WorkOpsRollbackReleaseAttempt, WorkOpsRollbackReleaseAttempt.Variables(id)).workOps.crossProject.rollbackReleaseArtifacts) { "The release attempt was not rolled back." }
        confirming = null; revision++; feedback.message = "Release attempt rolled back."
        read.retry(ctx)
    }
    suspend fun openEnvironmentRollback(environment: String) = feedback.perform {
        require(read.rollbackEnvironmentReason(environment) == null) { read.rollbackEnvironmentReason(environment).orEmpty() }
        confirming = WorkOpsReleaseCiAction("rollbackEnvironment", environment = environment, label = environment)
        rollbackRevisionDraft = "0"; rollbackOutcomes = emptyList()
    }
    suspend fun rollbackEnvironment(confirmed: Boolean, revision: String, ctx: RenderContext) = feedback.perform {
        rollbackRevisionDraft = revision
        require(confirmed) { "Confirm rollback of this environment." }
        val target = requireNotNull(confirming?.takeIf { it.kind == "rollbackEnvironment" }) { "Review the environment rollback first." }
        val number = requireNotNull(revision.toIntOrNull()?.takeIf { it >= 0 }) { "Enter a nonnegative whole revision; zero selects the previous revision." }
        rollbackOutcomes = ctx.gql.execute(WorkOpsRollbackReleaseEnvironment, WorkOpsRollbackReleaseEnvironment.Variables(id, target.environment, number)).workOps.crossProject.rollbackReleaseEnvironment
        confirming = null; this.revision++; feedback.message = "Rollback requested for ${rollbackOutcomes.size} targets."
        read.retry(ctx)
    }

    companion object {
        private val activeStatuses = setOf(GitPipelineRunStatus.QUEUED, GitPipelineRunStatus.RUNNING)
        private val failureStatuses = setOf(GitPipelineRunStatus.FAILURE, GitPipelineRunStatus.CANCELLED)
    }
}

suspend fun workOpsReleaseCi(id: String): WorkOpsReleaseCiModel = WorkOpsReleaseCiModel(id).also { it.read.retry(currentRenderContext()) }
