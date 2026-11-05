package bosca.ai.kit.harness.runtime

import bosca.ai.chat.model.ChatMessageInput
import bosca.ai.chat.service.ChatHistoryService
import bosca.ai.kit.agents.session.KitSessionService
import bosca.ai.kit.harness.*
import bosca.db.transaction
import bosca.db.afterCommit
import bosca.security.encryption.EncryptionService
import bosca.security.model.ApiTokenCredentialAttributes
import bosca.security.service.AuthenticationContext
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.security.service.SecurityService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.json.*
import java.net.URI
import java.security.MessageDigest
import java.time.Instant

@ServiceImplementation
class KitHarnessServiceImpl(
    private val repository: KitRuntimeRepository,
    private val history: ChatHistoryService,
    private val sessions: KitSessionService,
    private val security: SecurityService,
    private val policy: KitHarnessPolicyResolver,
    private val context: KitContextResolver,
    private val json: Json,
    private val tokens: bosca.security.service.ApiTokenService,
    private val provider: KitRuntimeProvider,
    private val telemetry: KitRuntimeTelemetry,
    private val delegations: KitDelegationRepository,
    private val encryption: EncryptionService,
) : KitHarnessService {
    private val log = org.slf4j.LoggerFactory.getLogger(KitHarnessServiceImpl::class.java)

    override suspend fun selectContext(sessionId: UUID, source: KitContextSelection, remove: Boolean) {
        source.validate()
        transaction {
            val runtime = requireNotNull(repository.lock(sessionId)) { "Runtime not found" }
            require(runtime.deletedAt == null) { "Conversation was deleted" }
            val chat = requireNotNull(history.getSessionForUpdate(sessionId)) { "Conversation not found" }
            val state = chat.state as? JsonObject ?: JsonObject(emptyMap())
            if (source.kind in setOf(KitContextSourceKind.METADATA, KitContextSourceKind.COLLECTION)) {
                val key = if (source.kind == KitContextSourceKind.METADATA) "attachments" else "collectionAttachments"
                val selected = (state[key] as? JsonArray).orEmpty()
                val value = JsonPrimitive(source.id.toString())
                val updated = if (remove) selected - value else (selected + value).distinct()
                require(updated.size <= 16) { "Select at most 16 context attachments" }
                history.updateSessionState(sessionId, JsonObject(state + (key to JsonArray(updated))))
                return@transaction
            }
            val serializer = kotlinx.serialization.builtins.ListSerializer(KitContextSelection.serializer())
            val selections = state["harnessSources"]?.let { json.decodeFromJsonElement(serializer, it) }.orEmpty()
            val updated = if (remove) selections - source else (selections + source).distinct()
            require(updated.size <= 16) { "Select at most 16 additional context sources" }
            history.updateSessionState(sessionId, JsonObject(state + ("harnessSources" to json.encodeToJsonElement(serializer, updated))))
        }
    }

    override suspend fun clearContext(sessionId: UUID) = transaction<Unit> {
        val runtime = requireNotNull(repository.lock(sessionId)) { "Runtime not found" }
        require(runtime.deletedAt == null) { "Conversation was deleted" }
        val chat = requireNotNull(history.getSessionForUpdate(sessionId)) { "Conversation not found" }
        val state = chat.state as? JsonObject ?: JsonObject(emptyMap())
        history.updateSessionState(sessionId, JsonObject(state - "harnessSources"))
    }

    override suspend fun context(authentication: AuthenticationContext, sessionId: UUID): KitContextStatus? {
        val session = repository.get(sessionId) ?: return null
        require(authentication.principal()?.id == session.principalId) { "Runtime belongs to another principal" }
        return try {
            val current = context.resolve(session, caller = authentication)
            KitContextStatus(current.references, current.manifest.integrations, current.manifest.omissions, current.selectionCount)
        } catch (unavailable: IllegalArgumentException) {
            log.warn("Kit context is unavailable for session {}", sessionId, unavailable)
            KitContextStatus(emptyList(), emptyList(), listOf("Context is unavailable. Review the agent and prompt configuration."))
        }
    }

    override suspend fun settings(authentication: AuthenticationContext, sessionId: UUID): KitHarnessSettings? {
        val session = repository.get(sessionId) ?: return null
        require(authentication.principal()?.id == session.principalId) { "Runtime belongs to another principal" }
        return try {
            policy.settings(authentication, session, history.getSession(sessionId)?.agentKey ?: "kit")
        } catch (unavailable: IllegalArgumentException) {
            log.warn("Kit harness policy is unavailable for session {}", sessionId, unavailable)
            val effective = session.resolvedSettings?.let { json.decodeFromJsonElement(KitHarnessResolution.serializer(), it) } ?: return null
            KitHarnessSettings(effective.policyVersion, effective.configuration.profile, effective.source, false, emptyList(),
                notice = "Harness policy is unavailable. An administrator must review its configuration.")
        }
    }

    override suspend fun verifySettings(authentication: AuthenticationContext, sessionId: UUID, choice: KitHarnessChoice?) {
        require(delegations.child(sessionId) == null) { "Managed child settings are owned by the parent task" }
        policy.verify(authentication, requireNotNull(repository.get(sessionId)), choice, history.getSession(sessionId)?.agentKey ?: "kit")
    }

    override suspend fun setSettings(sessionId: UUID, choice: KitHarnessChoice?) = transaction<Unit> {
        val session = requireNotNull(repository.lock(sessionId))
        require(session.deletedAt == null) { "Conversation was deleted" }
        check(repository.save(session.copy(requestedSettings = choice?.let { json.encodeToJsonElement(KitHarnessChoice.serializer(), it) })) == 1)
    }

    override suspend fun activate(authentication: AuthenticationContext, sessionId: UUID) {
        require(delegations.child(sessionId) == null) { "Managed children are dispatched by their parent task" }
        val principal = requireNotNull(authentication.principal()) { "Not authenticated" }
        val authority = policy.capture(authentication)
        val resolution = policy.resolve(principal.id, authority, null, history.getSession(sessionId)?.agentKey ?: "kit")
        val config = resolution.configuration
        // Removal of a failed generation is confirmed before the row lock; provider observation may be remote.
        val removedDispatch = repository.get(sessionId)?.takeIf { it.state == "FAILED" }?.let { failed ->
            failed.dispatchId?.takeIf { provider.isStopped(json.decodeFromJsonElement(KitHarnessConfiguration.serializer(), failed.configuration), it) }
        }
        transaction {
            repository.create(KitRuntimeSession(sessionId, principal.id,
                json.encodeToJsonElement(KitHarnessConfiguration.serializer(), config),
                json.encodeToJsonElement(KitRuntimeAuthority.serializer(), authority),
                resolvedSettings = json.encodeToJsonElement(KitHarnessResolution.serializer(), resolution),
                idleAt = OffsetDateTime.now().plusSeconds(config.idleSeconds)))
            val session = requireNotNull(repository.lock(sessionId))
            require(session.principalId == principal.id) { "Runtime belongs to another principal" }
            val existingAuthority = json.decodeFromJsonElement(KitRuntimeAuthority.serializer(), session.authority)
            val currentGroups = security.getPrincipalGroups(principal.id).map { it.id }.toSet()
            require(existingAuthority.scopes.all { (principal as? ScopedAuthenticatedPrincipal)?.hasScope(it) != false } &&
                authority.groups.containsAll(existingAuthority.groups.filter { it in currentGroups })) {
                "This conversation uses a different scoped authority; start a conversation with the current credentials"
            }
            if (session.state == "FAILED") {
                require(session.dispatchId == null || session.dispatchId == removedDispatch) { "Previous runtime is still being removed" }
                check(repository.save(session.freshGeneration().copy(
                    nativeSessionId = if (session.archiveKey == null) null else session.nativeSessionId,
                    needsContext = true)) == 1)
            } else if (session.state == "STOPPED") check(repository.save(session.freshGeneration()) == 1)
            KitRuntimeJob(sessionId).enqueue()
        }
    }

    override suspend fun send(authentication: AuthenticationContext, sessionId: UUID, message: ChatMessageInput) {
        require(message.role == "user" && message.parts.isNotEmpty() && message.parts.all { it.type == "text" }) { "Unsupported Kit message" }
        activate(authentication, sessionId)
        transaction {
            val session = requireNotNull(repository.lock(sessionId))
            require(session.deletedAt == null && session.state != "FAILED") { "Runtime requires recovery: ${session.failure}" }
            val config = json.decodeFromJsonElement(KitHarnessConfiguration.serializer(), session.configuration)
            // Capture the context boundary before this message, including when more turns are queued.
            val turn = KitRuntimeTurn(UUID.random(), sessionId, message = json.encodeToJsonElement(ChatMessageInput.serializer(), message),
                created = OffsetDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS))
            sessions.recordUserMessage(sessionId, message)
            repository.add(turn)
            check(repository.save(session.copy(idleAt = OffsetDateTime.now().plusSeconds(config.idleSeconds))) == 1)
            history.setProcessing(sessionId, true)
            KitRuntimeJob(sessionId).enqueue()
        }
    }

    override suspend fun approvals(sessionId: UUID) = repository.approvals(sessionId)

    override suspend fun decide(authentication: AuthenticationContext, sessionId: UUID, approvalId: UUID, optionId: String) = transaction<Unit> {
        val session = requireNotNull(repository.lock(sessionId))
        val principal = requireNotNull(authentication.principal()) { "Not authenticated" }
        val authority = json.decodeFromJsonElement(KitHarnessResolution.serializer(), requireNotNull(session.resolvedSettings)).authority
        require(session.principalId == principal.id && authority.scopes.all { (principal as? ScopedAuthenticatedPrincipal)?.hasScope(it) != false }) { "Approval authority changed" }
        val currentGroups = security.getPrincipalGroups(principal.id).map { it.id }.toSet()
        require(authority.groups.all { it in currentGroups && principal.hasGroup(it) }) { "Approval authority changed" }
        val approval = repository.approvals(sessionId).firstOrNull { it.id == approvalId }
            ?: error("Approval is no longer pending")
        require(session.deletedAt == null && session.cancelRequestedAt == null && session.activeTurn == approval.turnId &&
            approval.request.jsonObject["generation"]?.jsonPrimitive?.long == session.generation) { "Approval is stale" }
        val turn = requireNotNull(repository.turn(approval.turnId))
        val resolution = json.decodeFromJsonElement(KitHarnessResolution.serializer(), requireNotNull(turn.resolvedSettings))
        require(approval.request.jsonObject["policyVersion"]?.jsonPrimitive?.content == resolution.policyVersion &&
            policy.isApprovalCurrent(session.principalId, resolution)) { "Approval policy or authority changed" }
        require(approval.expires.isAfter(OffsetDateTime.now())) { "Approval expired" }
        val offered = approval.request.jsonObject.getValue("permissions").jsonArray
        require(offered.any { it.jsonObject.getValue("optionId").jsonPrimitive.content == optionId }) { "Unknown permission option" }
        check(repository.decide(approvalId, optionId) == 1)
        afterCommit { telemetry.record(KitRuntimeOperation.APPROVAL, "success", session, turn, approval,
            durationMillis = elapsedSince(approval.created), actor = "principal") }
        val config = json.decodeFromJsonElement(KitHarnessConfiguration.serializer(), session.configuration)
        check(repository.save(session.copy(idleAt = OffsetDateTime.now().plusSeconds(config.idleSeconds))) == 1)
        KitRuntimeJob(sessionId).enqueue()
    }

    override suspend fun state(sessionId: UUID): KitHarnessStatus? = repository.get(sessionId)?.let {
        KitHarnessStatus(if (it.state == "STARTING" && it.archiveKey != null) "RESUMING" else it.state,
            it.cancelRequestedAt != null, it.failure ?: it.archiveFailure)
    }

    override suspend fun cancel(sessionId: UUID): Unit = transaction<Unit> {
        val session = repository.lock(sessionId) ?: return@transaction
        require(session.deletedAt == null) { "Conversation was deleted" }
        delegations.children(sessionId).filter { !it.status.terminal }.forEach { child ->
            repository.get(child.childSessionId)?.takeIf { it.deletedAt == null }?.let { cancel(it.sessionId) }
        }
        repository.cancelQueued(sessionId, session.activeTurn).forEach { turn ->
            history.upsertMessage(requireNotNull(KitRuntimeTranscript(session, turn, json).snapshot("CANCELLED", true)))
        }
        session.activeTurn?.let { repository.closeApprovals(it) }
        val pending = session.activeTurn != null || session.state == "STARTING"
        check(repository.save(session.copy(cancelRequestedAt = if (pending) session.cancelRequestedAt ?: OffsetDateTime.now() else null)) == 1)
        history.setProcessing(sessionId, pending)
        KitRuntimeJob(sessionId).enqueue()
    }

    override suspend fun delete(sessionId: UUID): Unit = transaction<Unit> {
        val session = repository.lock(sessionId) ?: return@transaction
        delegations.children(sessionId).forEach { child -> delete(child.childSessionId) }
        session.credentialId?.let { telemetry.observe(KitRuntimeOperation.CREDENTIAL_REVOKE, session) { tokens.revokeToken(it, session.principalId) } }
        session.dispatchId?.let { provider.stop(json.decodeFromJsonElement(KitHarnessConfiguration.serializer(), session.configuration), it) }
        check(repository.save(session.copy(deletedAt = OffsetDateTime.now())) == 1)
        KitRuntimeJob(sessionId).enqueue()
    }

    override suspend fun egress(sessionId: UUID, token: String): KitEgressDestinations? {
        val session = repository.get(sessionId) ?: return null
        val credentialId = session.credentialId
        if (session.deletedAt != null || session.cancelRequestedAt != null || credentialId == null || session.secrets == null) return null
        val environment = runtimeEnvironment(session, encryption, json)
        val expected = environment["BOSCA_EGRESS_TOKEN"] ?: return null
        if (!MessageDigest.isEqual(expected.encodeToByteArray(), token.encodeToByteArray())) return null
        if (Instant.now().epochSecond >= environment.getValue("BOSCA_RUNTIME_EXPIRES").toLong()) return null
        val credential = tokens.getTokenById(credentialId)?.attributes as? ApiTokenCredentialAttributes ?: return null
        if (credential.revokedAt != null) return null
        val resolution = json.decodeFromJsonElement(KitHarnessResolution.serializer(), requireNotNull(session.resolvedSettings))
        val destinations = resolution.configuration.egressHosts.map(::egressDestination) +
            resolution.mcpConnections.mapNotNull { connection -> connection.url?.let { egressDestination(URI(it)) } } +
            policy.egressHosts()
        return KitEgressDestinations(destinations.distinct().sorted())
    }

    override suspend fun retire(sessionId: UUID) = transaction<Unit> {
        val session = repository.lock(sessionId) ?: return@transaction
        require(session.activeTurn == null && repository.next(sessionId) == null && repository.approvals(sessionId).isEmpty()) { "Conversation has unfinished work" }
        if (session.state == "READY") {
            check(repository.save(session.copy(idleAt = OffsetDateTime.now())) == 1)
            KitRuntimeJob(sessionId).enqueue()
        }
    }
}
