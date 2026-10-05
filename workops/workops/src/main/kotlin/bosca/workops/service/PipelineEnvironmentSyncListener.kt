package bosca.workops.service

import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.git.model.EnvironmentDefinition
import bosca.git.model.PipelineEnvironmentsSynced
import bosca.pubsub.PubSubService
import bosca.serialization.UUID
import bosca.workops.model.OptimisticLockFailedException
import bosca.workops.model.environment.CreateEnvironmentInput
import bosca.workops.model.environment.Environment
import bosca.workops.model.environment.UpdateEnvironmentInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

/**
 * Applies a default-branch pipeline sync's `environments:` block to WorkOps. The YAML
 * is the source of truth for environment topology — which environments exist (by KEY), what each
 * promotes from, and whether it requires approval. WorkOps owns everything display-facing: name,
 * description, type, target. So the sync upserts by `(program, key)`: it creates missing
 * environments with a name derived from the key, and on existing ones it rewrites ONLY the
 * YAML-owned fields, never the display fields a user has curated.
 *
 * The owning programs resolve through the project↔repository links: every program with a project
 * that owns the event's repository receives the sync. An event for an unlinked repository is a
 * no-op, not an error — the link may simply not exist yet, and the next default-branch push
 * re-announces.
 */
class PipelineEnvironmentSync(
    private val environmentService: EnvironmentService,
    private val environmentTypeService: EnvironmentTypeService,
    private val projectRepositories: ProjectRepositoryService,
    private val projectService: ProjectService,
) {

    suspend fun apply(event: PipelineEnvironmentsSynced) {
        val programIds = projectRepositories.listByRepository(event.repositoryId)
            .mapNotNull { link -> projectService.getById(link.projectId)?.programId }
            .distinct()
        if (programIds.isEmpty()) {
            log.debug("No program links repository {} — environment sync skipped", event.repositoryId)
            return
        }
        for (programId in programIds) {
            apply(programId, event.environments)
        }
    }

    suspend fun apply(programId: UUID, environments: Map<String, EnvironmentDefinition>) {
        val existing = environmentService.listByProgram(programId).associateBy { it.key }.toMutableMap()

        // Pass 1 — every declared key exists, so pass 2 can resolve promotes-from edges by key.
        for ((key, definition) in environments) {
            val current = existing[key]
            if (current == null) {
                val created = environmentService.create(
                    CreateEnvironmentInput(
                        programId = programId,
                        key = key,
                        name = displayNameOf(key),
                        displayOrder = existing.size,
                        requiresApproval = definition.approval,
                        typeId = resolveTypeId(key),
                    )
                )
                existing[key] = created
                log.info("Created environment '{}' ({}) in program {} from pipeline YAML", key, created.id, programId)
            } else if (current.requiresApproval != definition.approval) {
                updateKeepingDisplayFields(current, requiresApproval = definition.approval)
                    ?.let { existing[key] = it }
            }
        }

        // Pass 2 — promotion edges. The YAML owns the topology, so the edge set is REPLACED for
        // every declared environment (a null promotes-from means "no sources").
        for ((key, definition) in environments) {
            val environment = existing[key] ?: continue
            val source = definition.promotesFrom?.let { existing[it] }
            if (definition.promotesFrom != null && source == null) {
                log.warn(
                    "Environment '{}' promotes from undeclared '{}' in program {} — edge skipped",
                    key, definition.promotesFrom, programId,
                )
            }
            val desired = source?.let { listOf(it.id) } ?: emptyList()
            if (environmentService.promotionSourceIds(environment.id) != desired) {
                updateKeepingDisplayFields(environment, promotionSourceIds = desired)
                    ?.let { existing[key] = it }
            }
        }
    }

    /**
     * Writes ONLY the YAML-owned fields, carrying every display field through unchanged. A stale
     * version means someone edited the environment concurrently — their write wins this round, and
     * the next default-branch push re-announces the YAML state.
     */
    private suspend fun updateKeepingDisplayFields(
        current: Environment,
        requiresApproval: Boolean = current.requiresApproval,
        promotionSourceIds: List<UUID>? = null,
    ): Environment? = try {
        environmentService.update(
            current.id,
            UpdateEnvironmentInput(
                name = current.name,
                description = current.description,
                displayOrder = current.displayOrder,
                promotionSourceIds = promotionSourceIds
                    ?: environmentService.promotionSourceIds(current.id),
                requiresApproval = requiresApproval,
                autoPromote = current.autoPromote,
                typeId = current.typeId,
                targetType = current.targetType,
                targetRef = current.targetRef,
                ephemeral = current.ephemeral,
            ),
            current.version,
        )
    } catch (e: OptimisticLockFailedException) {
        log.info("Environment '{}' changed concurrently — YAML sync defers to the next push", current.key)
        null
    }

    /**
     * Sync-created environments need a type; the key usually matches one (production, staging …).
     * A key with no matching type grows the catalog rather than guessing — the user can re-home it.
     */
    private suspend fun resolveTypeId(key: String): UUID {
        val types = environmentTypeService.list()
        types.firstOrNull { it.name.equals(key, ignoreCase = true) }?.let { return it.id }
        val created = environmentTypeService.create(
            name = key,
            description = "Created by pipeline environment sync",
            displayOrder = types.size,
        )
        log.info("Created environment type '{}' ({}) from pipeline YAML", key, created.id)
        return created.id
    }

    private fun displayNameOf(key: String): String =
        key.split('-').joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }

    private companion object {
        private val log = LoggerFactory.getLogger(PipelineEnvironmentSync::class.java)
    }
}

/**
 * Subscribes to the git module's `bosca.git.pipeline.environments` channel and applies each event
 * via [PipelineEnvironmentSync]. Mirrors [SpecDocumentSyncListener]'s connection handling: the
 * handler runs with an explicit connection context because a raw subscription has none.
 */
class PipelineEnvironmentSyncListener(
    private val pubSubService: PubSubService,
    private val sync: PipelineEnvironmentSync,
    private val connectionPool: ConnectionPool,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            while (true) {
                try {
                    pubSubService.subscribe(
                        "bosca.git.pipeline.environments",
                        PipelineEnvironmentsSynced.serializer(),
                    ).collect { msg ->
                        val mgr = connectionPool.connection()
                        try {
                            withContext(mgr.asCoroutineContext()) {
                                sync.apply(msg.message)
                            }
                        } finally {
                            withContext(NonCancellable) { mgr.release() }
                        }
                    }
                    // The flow completing (broker reconnect, shutdown race) must not become a hot
                    // resubscribe loop.
                    log.warn("Pipeline environment sync subscription completed; resubscribing in 5s")
                    delay(5000.milliseconds)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("Pipeline environment sync listener failed, retrying in 5s: {}", e.message)
                    delay(5000.milliseconds)
                }
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(PipelineEnvironmentSyncListener::class.java)
    }
}
