@file:OptIn(ExperimentalUuidApi::class)

package bosca.pipelines.graphql

import bosca.di.ObjectProvider
import bosca.di.provide
import bosca.pipelines.service.PipelineRunResultStore
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.pipelines.DryRunTrace
import bosca.pipelines.PipelineContext
import bosca.pipelines.git.PipelineBackfillEntry
import bosca.pipelines.git.PipelineGitSyncService
import bosca.pipelines.git.PipelineSyncResult
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineNamedShape
import bosca.pipelines.model.PipelineRun
import bosca.pipelines.model.PipelineRunStatus
import bosca.pipelines.model.PipelineSecret
import bosca.pipelines.node.ShapeField
import bosca.pipelines.run.resolvePipelineRunInput
import bosca.pipelines.security.PipelinePermissionEvaluator
import bosca.pipelines.service.PipelineExecutor
import bosca.pipelines.service.PipelineRunService
import bosca.pipelines.service.PipelineSecretService
import bosca.pipelines.service.PipelineService
import bosca.pipelines.service.PipelineShapeService
import bosca.pipelines.service.requireCompleted
import bosca.pipelines.trigger.PipelineScheduledRunExecutor
import bosca.pipelines.trigger.PipelineScheduledRunJob
import bosca.scheduler.model.ScheduledJobInput
import bosca.scheduler.service.SchedulerService
import bosca.security.model.PermissionAction
import bosca.security.model.PermissionInput
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import kotlin.uuid.ExperimentalUuidApi

/** GraphQL namespace for pipeline mutations (`Mutation.pipelines`). */
object PipelinesMutation

/** Input for [PipelinesMutationController.save]. */
@Serializable
data class PipelineInput(
    @Contextual
    val id: UUID? = null,
    val name: String,
    val description: String? = null,
    val acceptedInputType: String,
    /** Free-form categorization labels; null = leave empty. */
    val tags: List<String>? = null,
    val triggered: Boolean? = null,
    val key: String? = null,
    val api: Boolean? = null,
    val public: Boolean? = null,
    /** Cron expression to run this pipeline on a schedule; null/blank = not scheduled. */
    val schedule: String? = null,
    /** Max durable runs in flight at once; null/≤0 = unlimited. */
    val maxConcurrentRuns: Int? = null,
    /** Max durable runs started per rolling minute; null/≤0 = unlimited. */
    val maxRunsPerMinute: Int? = null,
    val version: Int? = null,
    @Contextual
    val graph: JsonElement,
)

/** Input for [PipelinesMutationController.backfill]. */
@Serializable
data class PipelineBackfillEntryInput(
    @Contextual
    val pipelineId: UUID,
    val gitPath: String,
)

/** Field wiring for `Mutation.pipelines` — pipeline authoring, Git sync, and run controls. */
@TypeController
class PipelinesMutationController(
    private val service: PipelineService,
    private val groups: GroupEvaluator,
    private val permissionEvaluator: PipelinePermissionEvaluator,
    private val executor: PipelineExecutor,
    private val gitSyncService: ObjectProvider<PipelineGitSyncService>,
    private val runService: PipelineRunService,
    private val secretService: PipelineSecretService,
    private val schedulerService: ObjectProvider<SchedulerService>,
    private val shapeService: PipelineShapeService,
) : GraphQLController<PipelinesMutation> {

    private val log = LoggerFactory.getLogger(PipelinesMutationController::class.java)

    /**
     * Mirror a pipeline's cron [Pipeline.schedule] into a SchedulerService ScheduledJob:
     * create when first scheduled, update the cron on change, delete when cleared. Matched to the
     * pipeline by the [PipelineScheduledRunJob] id in the job's parameters. Best-effort — a scheduler
     * hiccup is logged, not allowed to fail the save (mirrors the Git push-after-save).
     */
    private suspend fun syncSchedule(pipeline: Pipeline) {
        if (!schedulerService.exists) return
        try {
            val scheduler = schedulerService.get()
            val json = provide<Json>()
            val existing = scheduler.getJobs(limit = SCHEDULE_SCAN_LIMIT).firstOrNull { job ->
                job.jobName == PipelineScheduledRunExecutor.NAME &&
                    runCatching { json.decodeFromJsonElement(PipelineScheduledRunJob.serializer(), job.jobParameters).pipelineId }
                        .getOrNull() == pipeline.id
            }
            val cron = pipeline.schedule?.trim()?.takeIf { it.isNotEmpty() }
            if (cron == null) {
                existing?.let { scheduler.deleteJob(it.id) }
                return
            }
            val input = ScheduledJobInput(
                name = "Pipeline schedule: ${pipeline.name}",
                description = "Scheduled run of pipeline ${pipeline.id}",
                jobName = PipelineScheduledRunExecutor.NAME,
                jobParameters = json.encodeToJsonElement(PipelineScheduledRunJob.serializer(), PipelineScheduledRunJob(pipeline.id)),
                cronExpression = cron,
                enabled = true,
                allowConcurrent = false,
                catchUp = false,
                maxCatchUp = 1,
            )
            if (existing == null) scheduler.createJob(input, UUID.NIL) else scheduler.updateJob(existing.id, input)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to sync schedule for pipeline {}", pipeline.id, e)
        }
    }

    /**
     * Authorizes [run]/[dryRun]: public pipelines are open to anyone; otherwise the caller needs
     * [PermissionAction.EXECUTE] via a group grant (admins and service accounts always pass).
     */
    private suspend fun verifyCanExecute(authentication: AuthenticationContext, pipeline: Pipeline) {
        if (pipeline.public) return
        permissionEvaluator.verifyAllowed(authentication, pipeline, PermissionAction.EXECUTE)
    }

    /**
     * Best-effort push after a save — failures are logged and recorded as `lastSyncError` on the
     * row (by the sync service) but do not fail the save. Same model as the AI agent mutations.
     */
    private suspend fun pushAfterSave(pipelineId: UUID, authorName: String?, authorEmail: String?) {
        if (!gitSyncService.exists || authorName == null || authorEmail == null) return
        try {
            val result = gitSyncService.get().pushToGit(pipelineId, authorName, authorEmail)
            if (result !is PipelineSyncResult.Ok) {
                log.warn("Push to Git after save failed for pipeline {}: {}", pipelineId, result)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to push pipeline {} back to Git after save", pipelineId, e)
        }
    }

    @Field
    suspend fun save(
        authentication: AuthenticationContext,
        input: PipelineInput,
        authorName: String? = null,
        authorEmail: String? = null,
    ): Pipeline {
        groups.verifyHasAdminGroup(authentication)
        val saved = service.save(
            id = input.id ?: UUID.NIL,
            name = input.name,
            description = input.description ?: "",
            acceptedInputType = input.acceptedInputType,
            triggered = input.triggered == true,
            version = (input.version ?: 0).toLong(),
            graph = input.graph,
            tags = input.tags ?: emptyList(),
            key = input.key ?: "",
            api = input.api == true,
            public = input.public == true,
            schedule = input.schedule,
            maxConcurrentRuns = input.maxConcurrentRuns,
            maxRunsPerMinute = input.maxRunsPerMinute,
        )
        syncSchedule(saved)
        if (saved.gitRepositoryId != null) {
            pushAfterSave(saved.id, authorName, authorEmail)
        }
        return saved
    }

    @Field
    suspend fun backfill(
        authentication: AuthenticationContext,
        repositoryId: UUID,
        entries: List<PipelineBackfillEntryInput>,
        authorName: String,
        authorEmail: String,
    ): PipelineRepoSyncResultModel {
        groups.verifyHasAdminGroup(authentication)
        if (!gitSyncService.exists) {
            return PipelineRepoSyncResultModel(false, null, null, "Git sync is not available on this server")
        }
        val result = gitSyncService.get().backfill(
            repositoryId = repositoryId,
            entries = entries.map { PipelineBackfillEntry(it.pipelineId, it.gitPath) },
            authorName = authorName,
            authorEmail = authorEmail,
        )
        return result.toModel()
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID): Boolean {
        groups.verifyHasAdminGroup(authentication)
        // Read for schedule teardown, but never let a broken graph — one that no longer decodes, so
        // `get` throws — block the delete. Deleting such a pipeline is the whole point of surfacing it.
        val existing = try {
            service.get(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        service.delete(id)
        // Tear down any scheduled job for the deleted pipeline (sync with a null schedule = remove).
        existing?.let { syncSchedule(it.copy(schedule = null)) }
        return true
    }

    /** Full copy of the pipeline's graph under a new id, created INACTIVE so it can't start firing. */
    @Field
    suspend fun clone(authentication: AuthenticationContext, id: UUID): Pipeline {
        groups.verifyHasAdminGroup(authentication)
        val source = service.get(id) ?: error("Pipeline not found: $id")
        return service.save(
            id = UUID.NIL,
            name = "${source.name} (copy)",
            description = source.description,
            acceptedInputType = source.acceptedInputType,
            triggered = false,
            version = 0,
            graph = service.graphAsJsonElement(source),
            tags = source.tags,
        )
    }

    /**
     * Dry run: decodes the typed event from (eventType fqdn, payload JSON) via the Event Catalog's
     * explicitly compiled serializer maps (native-safe), traverses the graph under the CALLER's
     * principal with [PipelineContext.dryRun] set, and returns the per-node trace. Action nodes
     * record what they *would* do and execute nothing. Public pipelines are open to anyone;
     * otherwise requires [PermissionAction.EXECUTE] via a group grant (admins and service
     * accounts always pass).
     */
    @Field
    suspend fun dryRun(
        authentication: AuthenticationContext,
        id: UUID,
        eventType: String?,
        payload: JsonElement,
    ): PipelineDryRunResult {
        val pipeline = service.get(id) ?: error("Pipeline not found: $id")
        verifyCanExecute(authentication, pipeline)
        val json = provide<Json>()
        val trace = DryRunTrace()
        val context = PipelineContext(authentication, json, dryRun = true, trace = trace)
        var error: String? = null
        try {
            executor.execute(pipeline, resolvePipelineRunInput(pipeline, eventType, payload, json), context).requireCompleted()
        }
        catch (e: CancellationException) {
            throw e
        }
        catch (e: Exception) {
            error = e.message ?: e.toString()
        }
        return PipelineDryRunResult(
            outputs = JsonObject(trace.outputs),
            actions = JsonObject(trace.actions),
            errors = JsonObject(trace.errors.mapValues { JsonPrimitive(it.value) }),
            skipped = JsonObject(trace.skipped.mapValues { JsonPrimitive(it.value) }),
            error = error,
        )
    }

    /**
     * Runs a pipeline now as a durable on-demand run **for real** — action nodes execute their side
     * effects, unlike [dryRun]. Public pipelines are open to anyone; otherwise requires
     * [PermissionAction.EXECUTE] via a group grant (admins and service accounts always pass). The run is
     * driven by its own enqueued run job (so a node that suspends resumes durably instead of orphaning)
     * and executes under the caller's principal end to end; it lands in run history as a `manual` run,
     * and a run that suspends returns its handle (status SUSPENDED/RUNNING) after a brief block.
     * A payload that fails input resolution (schema violation, undecodable
     * event) is reported without recording a run — nothing executed.
     */
    @Field
    suspend fun run(
        authentication: AuthenticationContext,
        id: UUID,
        payload: JsonElement,
    ): PipelineRunResultModel {
        val pipeline = service.get(id) ?: error("Pipeline not found: $id")
        verifyCanExecute(authentication, pipeline)
        val json = provide<Json>()
        val input = try {
            resolvePipelineRunInput(pipeline, eventType = null, payload = payload, json = json)
        }
        catch (e: CancellationException) {
            throw e
        }
        catch (e: Exception) {
            // Input resolution failed before any run was created — no run handle to return.
            return PipelineRunResultModel(
                ok = false,
                runId = UUID.NIL,
                status = PipelineRunStatus.FAILED,
                output = null,
                error = e.message ?: e.toString(),
            )
        }
        val run = runService.start(pipeline, input, MANUAL_RUN, java.time.OffsetDateTime.now(), authentication)
            ?: return PipelineRunResultModel(
                ok = false,
                runId = UUID.NIL,
                status = PipelineRunStatus.FAILED,
                output = null,
                error = "Run shed: pipeline '${pipeline.name}' is at its concurrency or rate cap",
            )
        return PipelineRunResultModel(
            // A still-suspended run started fine — only a terminal failure is not ok.
            ok = run.status != PipelineRunStatus.FAILED && run.status != PipelineRunStatus.CANCELLED,
            runId = run.id,
            status = run.status,
            output = run.output,
            error = run.error,
        )
    }

    @Field
    suspend fun cancelRun(authentication: AuthenticationContext, runId: UUID): Boolean {
        groups.verifyHasAdminGroup(authentication)
        runService.cancel(runId, "cancelled by operator")
        return true
    }

    /**
     * Supply the value a [bosca.pipelines.builtin.WaitForInputNode] on [runId] is parked waiting for,
     * and resume the run with [input] as that node's output. Authorized by the run's pipeline
     * [PermissionAction.EXECUTE] (public pipelines are open; admins/service accounts always pass) — the
     * same gate as running the pipeline. Idempotent via the run service's resume (a second call after
     * the node has already resumed is a no-op). Approvals, form submissions, and chosen options all use
     * this — the meaning of [input] is up to the pipeline's downstream nodes.
     */
    @Field
    suspend fun provideInput(
        authentication: AuthenticationContext,
        runId: UUID,
        nodeId: String,
        input: JsonElement,
    ): Boolean {
        val run = runService.get(runId) ?: error("Pipeline run not found: $runId")
        val pipeline = service.get(run.pipelineId) ?: error("Pipeline not found: ${run.pipelineId}")
        verifyCanExecute(authentication, pipeline)
        // Stage the supplied value as the node's output, then resume — the value flows downstream and
        // is recorded on the node's timeline entry.
        provide<PipelineRunResultStore>().put(runId, nodeId, input)
        runService.resume(runId, nodeId, succeeded = true, error = null)
        return true
    }

    /**
     * Decide an [bosca.pipelines.builtin.ApprovalGateNode] the run is parked on. **Approved** resumes the
     * run WITHOUT touching the node's staged output — the gate emits its original inbound value, so the
     * typed chain through the gate is preserved. **Rejected** resumes the run as failed at the gate,
     * carrying [note] in the failure. Same authorization as [provideInput] (the pipeline's EXECUTE);
     * idempotent via the run service's resume.
     */
    @Field
    suspend fun resolveGate(
        authentication: AuthenticationContext,
        runId: UUID,
        nodeId: String,
        approved: Boolean,
        note: String? = null,
    ): Boolean {
        val run = runService.get(runId) ?: error("Pipeline run not found: $runId")
        val pipeline = service.get(run.pipelineId) ?: error("Pipeline not found: ${run.pipelineId}")
        verifyCanExecute(authentication, pipeline)
        if (approved) {
            runService.resume(runId, nodeId, succeeded = true, error = null)
        } else {
            val reason = note?.takeIf { it.isNotBlank() }?.let { "Gate rejected: $it" } ?: "Gate rejected"
            runService.resume(runId, nodeId, succeeded = false, error = reason)
        }
        return true
    }

    /**
     * Remove a dead-lettered (terminal) run from the queue: soft-delete it so it no longer appears in
     * the dead-letter list or run views. True when a row was removed; false when the run is missing or
     * still in-flight (cancel an in-flight run first). Admin-gated.
     */
    @Field
    suspend fun deleteRun(authentication: AuthenticationContext, runId: UUID): Boolean {
        groups.verifyHasAdminGroup(authentication)
        return runService.delete(runId)
    }

    /**
     * Restart a dead-lettered (failed) run: start a fresh durable run from the
     * source run's seed input against the current pipeline. Returns the new run, or null if the source is
     * gone. Admin-gated.
     */
    @Field
    suspend fun restartRun(authentication: AuthenticationContext, runId: UUID): PipelineRun? {
        groups.verifyHasAdminGroup(authentication)
        return runService.restart(runId)
    }

    /**
     * Create or replace a node secret: the value is encrypted before storage
     * and never returned. Returns the secret's metadata. Admin-gated.
     */
    @Field
    suspend fun setSecret(authentication: AuthenticationContext, name: String, value: String): PipelineSecret {
        groups.verifyHasAdminGroup(authentication)
        return secretService.setSecret(name, value)
    }

    /** Delete a node secret by name. Admin-gated; no-op if absent. */
    @Field
    suspend fun deleteSecret(authentication: AuthenticationContext, name: String): Boolean {
        groups.verifyHasAdminGroup(authentication)
        secretService.deleteSecret(name)
        return true
    }

    /** Create or replace a reusable named object shape from its typed [fields] (a JSON array of `{ name, type }`). */
    @Field
    suspend fun saveShape(authentication: AuthenticationContext, name: String, fields: JsonElement): PipelineNamedShape {
        groups.verifyHasAdminGroup(authentication)
        val list = provide<Json>().decodeFromJsonElement(ListSerializer(ShapeField.serializer()), fields)
        return shapeService.save(name, list)
    }

    /** Delete a named shape by name. Admin-gated; no-op if absent. */
    @Field
    suspend fun deleteShape(authentication: AuthenticationContext, name: String): Boolean {
        groups.verifyHasAdminGroup(authentication)
        shapeService.delete(name)
        return true
    }

    @Field
    suspend fun addPermission(authentication: AuthenticationContext, permission: PermissionInput): Boolean {
        groups.verifyHasAdminGroup(authentication)
        service.addPermission(permission.entityId, permission.groupId, permission.action)
        return true
    }

    @Field
    suspend fun deletePermission(authentication: AuthenticationContext, permission: PermissionInput): Boolean {
        groups.verifyHasAdminGroup(authentication)
        service.deletePermission(permission.entityId, permission.groupId, permission.action)
        return true
    }

    private companion object {
        /** Run-history `eventName` for runs started by hand via the run mutation. */
        const val MANUAL_RUN = "manual"

        /** Upper bound on scheduled jobs scanned to find a pipeline's existing schedule entry. */
        const val SCHEDULE_SCAN_LIMIT = 1000
    }
}
