package bosca.ai.kit.harness.runtime

import bosca.ai.chat.model.ChatMessageInput
import bosca.ai.chat.model.ChatMessagePartInput
import bosca.ai.chat.model.ChatSessionInput
import bosca.ai.chat.service.ChatHistoryService
import bosca.ai.kit.agents.KitResponse
import bosca.ai.kit.agents.session.KitSessionService
import bosca.ai.kit.harness.*
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.db.transaction
import bosca.security.encryption.EncryptionService
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.security.service.SecurityException
import bosca.security.service.SecurityService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*

/** Keeps delegation inside Kit's existing conversation, context, credential and job lifecycle. */
@ServiceImplementation
class KitDelegationServiceImpl(
    private val repository: KitDelegationRepository,
    private val runtimes: KitRuntimeRepository,
    private val harness: KitHarnessService,
    private val history: ChatHistoryService,
    private val sessions: KitSessionService,
    private val policy: KitHarnessPolicyResolver,
    private val context: KitContextResolver,
    private val metadata: MetadataService,
    private val metadataPermissions: MetadataPermissionEvaluator,
    private val security: SecurityService,
    private val encryption: EncryptionService,
    private val json: Json,
) : KitDelegationService {
    override suspend fun settings(authentication: AuthenticationContext, sessionId: UUID): KitDelegationPolicy =
        policy.delegation(resolved(caller(authentication, sessionId)))

    override suspend fun start(authentication: AuthenticationContext, sessionId: UUID, input: KitDelegationInput): KitDelegation {
        input.validate()
        val observed = caller(authentication, sessionId, active = true)
        val ancestor = repository.child(sessionId)
        val rootTurn = requireNotNull(runtimes.turn(ancestor?.rootTurnId ?: requireNotNull(observed.activeTurn)))
        return transaction {
            requireNotNull(runtimes.lock(rootTurn.sessionId)) { "Parent conversation was deleted" }
            val parent = caller(authentication, sessionId, active = true, lock = true)
            require(parent.activeTurn == observed.activeTurn) { "Parent turn changed before child admission" }
            repository.get(input.requestId)?.let {
                require(it.parentSessionId == sessionId && it.parentTurnId == parent.activeTurn && it.input == input) { "Delegation identity was reused with different input" }
                return@transaction visible(authentication, it)
            }
            val parentTurn = requireNotNull(runtimes.turn(requireNotNull(parent.activeTurn)))
            val parentResolution = turnResolution(parentTurn)
            val limits = policy.delegation(parentResolution)
            val depth = (ancestor?.depth ?: 0) + 1
            val reservations = repository.tree(rootTurn.id)
            require(depth <= limits.maxDepth && reservations.size < limits.maxChildren &&
                reservations.count { !it.status.terminal } < limits.maxActive &&
                input.runtimeSeconds <= limits.maxRuntimeSeconds &&
                reservations.sumOf { it.input.runtimeSeconds } + input.runtimeSeconds <= limits.totalRuntimeSeconds) { "Child harness depth, concurrency or runtime budget is exhausted" }
            val parentAuthority = parentResolution.authority
            val requestedScopes = input.scopes ?: parentAuthority.scopes
            require(parentAuthority.scopes.containsAll(requestedScopes)) { "Child scopes exceed the parent authority" }
            val principal = requireNotNull(authentication.principal())
            val ceiling = parentAuthority.copy(scopes = requestedScopes, callerScopes = requestedScopes,
                groups = parentAuthority.groups.filter(principal::hasGroup))
            val childResolution = policy.resolveChild(parent.principalId, parentResolution, ceiling, input.profile,
                input.runtimeSeconds, history.getSession(sessionId)?.agentKey ?: "kit")
            val now = OffsetDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
            val expires = java.time.Instant.ofEpochSecond(runtimeEnvironment(parent, encryption, json).getValue("BOSCA_RUNTIME_EXPIRES").toLong()).atOffset(java.time.ZoneOffset.UTC)
            val deadline = listOfNotNull(now.plusSeconds(input.runtimeSeconds), input.deadline, parentTurn.deadline, ancestor?.deadline, expires).minOrNull() ?: error("Missing child deadline")
            require(deadline.isAfter(now)) { "Child task deadline has expired" }
            val childId = UUID.random()
            val selected = (input.context + input.artifacts.map { KitContextSelection(KitContextSourceKind.METADATA, it) }).distinct()
            val state = buildJsonObject { put("harnessSources", json.encodeToJsonElement(ListSerializer(KitContextSelection.serializer()), selected)) }
            history.createSession(parent.principalId, ChatSessionInput(history.getSession(sessionId)?.agentKey ?: "kit",
                "Child task: " + input.brief.take(80), state, parentSessionId = sessionId), childId)
            val child = KitRuntimeSession(childId, parent.principalId,
                json.encodeToJsonElement(KitHarnessConfiguration.serializer(), childResolution.configuration),
                json.encodeToJsonElement(KitRuntimeAuthority.serializer(), childResolution.authority),
                resolvedSettings = json.encodeToJsonElement(KitHarnessResolution.serializer(), childResolution), idleAt = now)
            val snapshot = context.resolve(child, caller = authentication)
            require(selected.all { selection -> snapshot.references.any { it.kind == selection.kind.name.lowercase() && it.id == selection.id.toString() && it.ref == selection.ref && it.path == selection.path } }) { "Some child input references are unavailable" }
            runtimes.create(child)
            val message = ChatMessageInput("user", listOf(ChatMessagePartInput("text",
                "Delegated task:\n${input.brief}\n\nExpected result:\n${input.expectedResult}\n\nReturn a concise summary and Bosca content artifact references. Use bosca_delegate submit for structured output when available.")))
            val turn = KitRuntimeTurn(UUID.random(), childId, message = json.encodeToJsonElement(ChatMessageInput.serializer(), message), created = now)
            runtimes.add(turn)
            val delegation = KitDelegation(input.requestId, sessionId, parentTurn.id, rootTurn.id, childId, turn.id,
                depth, input, limits, childResolution.authority, snapshot.manifest, deadline = deadline, created = now, modified = now)
            check(repository.create(delegation) == 1)
            sessions.recordUserMessage(childId, message)
            history.setProcessing(childId, true)
            KitRuntimeJob(childId).enqueue()
            requireNotNull(repository.get(delegation.id))
        }
    }

    override suspend fun get(authentication: AuthenticationContext, sessionId: UUID, id: UUID): KitDelegation? {
        caller(authentication, sessionId)
        val delegation = repository.get(id) ?: return null
        require(delegation.parentSessionId == sessionId) { "Child task belongs to another parent" }
        runtimes.get(delegation.childSessionId)?.let { reconcile(it) }
        return visible(authentication, requireNotNull(repository.get(id)))
    }

    override suspend fun wait(authentication: AuthenticationContext, sessionId: UUID, id: UUID): KitDelegation = transaction {
        val parent = caller(authentication, sessionId, active = true, lock = true)
        val delegation = requireNotNull(repository.get(id)) { "Child task not found" }
        require(delegation.parentSessionId == sessionId) { "Child task belongs to another parent" }
        repository.wait(id, requireNotNull(parent.activeTurn))
        requireNotNull(get(authentication, sessionId, id))
    }

    override suspend fun list(authentication: AuthenticationContext, sessionId: UUID, offset: Long, limit: Int): List<KitDelegation> {
        caller(authentication, sessionId)
        require(offset >= 0 && limit in 1..100) { "Invalid child task page" }
        return repository.list(sessionId, offset, limit).map { child ->
            runtimes.get(child.childSessionId)?.let { reconcile(it) }
            visible(authentication, requireNotNull(repository.get(child.id)))
        }
    }

    override suspend fun submit(authentication: AuthenticationContext, sessionId: UUID, result: KitDelegationResult): KitDelegation = transaction {
        result.validate()
        val child = caller(authentication, sessionId, active = true, lock = true)
        val delegation = requireNotNull(repository.child(sessionId)) { "This runtime is not a managed child" }
        require(child.activeTurn == delegation.childTurnId && !delegation.status.terminal) { "Child task is no longer accepting results" }
        require(result.artifacts.all { readable(authentication, it.metadataId) }) { "Child artifact is unavailable" }
        if (repository.submit(delegation.id, result) != 1) require(repository.get(delegation.id)?.result == result) { "Child result was already submitted with different content" }
        requireNotNull(repository.get(delegation.id))
    }

    override suspend fun cancel(authentication: AuthenticationContext, sessionId: UUID, id: UUID): KitDelegation {
        val delegation = requireNotNull(get(authentication, sessionId, id)) { "Child task not found" }
        if (!delegation.status.terminal) harness.cancel(delegation.childSessionId)
        return requireNotNull(get(authentication, sessionId, id))
    }

    override suspend fun resolution(session: KitRuntimeSession): KitHarnessResolution? {
        val child = repository.child(session.sessionId) ?: return null
        val parentTurn = requireNotNull(runtimes.turn(child.parentTurnId)) { "Parent turn was deleted" }
        return policy.resolveChild(session.principalId, turnResolution(parentTurn), child.authority,
            child.input.profile, child.input.runtimeSeconds, history.getSession(session.sessionId)?.agentKey ?: "kit")
    }

    override suspend fun deadline(sessionId: UUID) = repository.child(sessionId)?.deadline
    override suspend fun waiting(turnId: UUID) = repository.waiting(turnId)

    override suspend fun validateContext(sessionId: UUID, manifest: KitContextManifest) {
        val child = repository.child(sessionId) ?: return
        val snapshots = child.contextManifest?.sources.orEmpty()
        require(snapshots.all { original -> manifest.sources.any { it == original } }) { "Child input changed before dispatch; create a new delegation after reviewing the current sources" }
    }

    override suspend fun reconcile(session: KitRuntimeSession) {
        val child = repository.child(session.sessionId) ?: return
        if (child.status.terminal) return
        val turn = runtimes.turn(child.childTurnId) ?: return
        if (turn.status in setOf("COMPLETED", "CANCELLED", "FAILED", "INTERRUPTED")) finish(session, turn, turn.status, "")
        else if (session.deletedAt != null && turn.status != "SENT") finish(session, turn, "CANCELLED", "")
        else if (session.state == "FAILED") finish(session, turn, if (turn.status == "SENT") "INTERRUPTED" else "FAILED", "")
        else if (turn.status == "SENT") repository.running(child.id)
    }

    override suspend fun finish(session: KitRuntimeSession, turn: KitRuntimeTurn, status: String, summary: String) = transaction<Unit> {
        val child = repository.child(session.sessionId) ?: return@transaction
        if (turn.id != child.childTurnId || child.status.terminal) return@transaction
        val cancelled = session.cancelRequestedAt
        var outcome = if (status != "COMPLETED" && !child.deadline.isAfter(OffsetDateTime.now()) &&
            (cancelled == null || !cancelled.isBefore(child.deadline))) KitDelegationStatus.TIMED_OUT
            else KitDelegationStatus.valueOf(status)
        val result = child.result ?: summary.takeIf(String::isNotBlank)?.let { KitDelegationResult(it.take(16000)) }
        var failure = if (outcome == KitDelegationStatus.COMPLETED) null else session.failure ?: "Child task ${outcome.name.lowercase()}; completed actions were not undone."
        if (outcome == KitDelegationStatus.COMPLETED && result == null) { outcome = KitDelegationStatus.FAILED; failure = "The child returned no usable result." }
        if (repository.finish(child.id, outcome.name, result, failure) != 1) return@transaction
        val parent = runtimes.get(child.parentSessionId)
        if (parent != null && parent.deletedAt == null && history.getSession(parent.sessionId) != null) {
            val principal = requireNotNull(security.getPrincipalById(parent.principalId))
            val authority = resolved(parent).authority
            val scoped = ScopedAuthenticatedPrincipal(principal, security.getPrincipalGroups(principal.id), authority.scopes, authority.groups.toSet(), 0)
            val authentication = object : AuthenticationContext(null, null) { override fun principal() = scoped }
            val visible = visible(authentication, requireNotNull(repository.get(child.id)))
            sessions.recordResponse(parent.sessionId, KitResponse.Text(buildString {
                append("Child task ").append(outcome.name.lowercase()).append(": ").append(child.input.brief.take(160)).append('\n')
                visible.result?.let { output -> append(output.summary); output.artifacts.forEach { append("\n").append(it.label.ifBlank { "Artifact" }).append(": bosca://content/metadata/").append(it.metadataId) } }
                visible.failure?.let { append('\n').append(it) }
            }))
            KitRuntimeJob(parent.sessionId).enqueue()
        }
    }

    override suspend fun cancelChildren(sessionId: UUID) { repository.children(sessionId).filter { !it.status.terminal }.forEach { if (runtimes.get(it.childSessionId)?.deletedAt == null) harness.cancel(it.childSessionId) } }
    override suspend fun childrenStopped(sessionId: UUID): Boolean {
        for (child in repository.children(sessionId)) if (runtimes.get(child.childSessionId) != null) return false
        return true
    }

    private fun resolved(session: KitRuntimeSession): KitHarnessResolution = json.decodeFromJsonElement(KitHarnessResolution.serializer(), requireNotNull(session.resolvedSettings))
    private fun turnResolution(turn: KitRuntimeTurn): KitHarnessResolution = json.decodeFromJsonElement(KitHarnessResolution.serializer(), requireNotNull(turn.resolvedSettings))

    private suspend fun caller(authentication: AuthenticationContext, sessionId: UUID, active: Boolean = false, lock: Boolean = false): KitRuntimeSession {
        val principal = authentication.principal() as? ScopedAuthenticatedPrincipal ?: throw SecurityException("Managed child operations require a runtime scoped credential")
        val runtime = requireNotNull(if (lock) runtimes.lock(sessionId) else runtimes.get(sessionId)) { "Runtime not found" }
        val current = resolved(runtime)
        if (principal.scopes == null || !principal.hasScope("ai:delegate") || principal.id != runtime.principalId || runtime.credentialId != principal.credentialId ||
            runtime.deletedAt != null || runtime.cancelRequestedAt != null || current.authority.groups.any { !principal.hasGroup(it) }) throw SecurityException("Child operation is outside the current runtime authority")
        val expires = runtimeEnvironment(runtime, encryption, json).getValue("BOSCA_RUNTIME_EXPIRES").toLong()
        require(java.time.Instant.now().epochSecond < expires) { "Runtime has expired" }
        if (active) require(runtime.activeTurn?.let { runtimes.turn(it)?.status } == "SENT") { "Delegation requires an active sent parent turn" }
        return runtime
    }

    private suspend fun visible(authentication: AuthenticationContext, child: KitDelegation): KitDelegation {
        val result = child.result ?: return child
        val principal = requireNotNull(authentication.principal())
        val authority = child.authority
        val runtime = runtimes.get(child.childSessionId)
        val permitted = authority.groups.all(principal::hasGroup) && authority.scopes.all { (principal as? ScopedAuthenticatedPrincipal)?.hasScope(it) != false } &&
            runtime != null && context.canRestoreWorkspace(runtime) && result.artifacts.all { readable(authentication, it.metadataId) }
        return if (permitted) child else child.copy(result = null, failure = "Child output is unavailable under the current source permissions.")
    }

    private suspend fun readable(authentication: AuthenticationContext, id: UUID): Boolean {
        val item = metadata.getById(id) ?: return false
        return !item.deleted && metadataPermissions.isAllowed(authentication, item, PermissionAction.VIEW)
    }
}
