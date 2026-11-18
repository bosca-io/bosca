package bosca.ai.kit.harness.runtime

import ai.koog.agents.core.agent.AIAgentService
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.OpenAILLMProvider
import bosca.ai.chat.model.ChatHistoryMessage
import bosca.ai.chat.model.ChatMessageInput
import bosca.ai.chat.model.ChatSessionStatus
import bosca.ai.chat.service.ChatHistoryService
import bosca.ai.kit.agents.KitResponse
import bosca.ai.kit.agents.session.KitSessionService
import bosca.ai.kit.harness.*
import bosca.ai.kit.harness.acp.*
import bosca.db.transaction
import bosca.db.afterCommit
import bosca.db.withConnectionManager
import bosca.security.encryption.EncryptionService
import bosca.security.service.ApiTokenInput
import bosca.security.service.ApiTokenService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.StringObjectPath
import bosca.sharedqueue.jobs.DelayException
import bosca.telemetry.Tracing
import bosca.ai.kit.harness.runtime.KitRuntimeOperation.*
import com.agentclientprotocol.client.ClientOperationsFactory
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Duration.Companion.seconds

/** Drives immutable generations; PostgreSQL claims and runtime epochs fence every side effect. */
class KitRuntimeDriver(
    private val repository: KitRuntimeRepository,
    private val provider: KitRuntimeProvider,
    private val tokens: ApiTokenService,
    private val encryption: EncryptionService,
    private val storage: ObjectStorageService,
    private val history: ChatHistoryService,
    private val sessions: KitSessionService,
    private val executor: PromptExecutor,
    private val json: Json,
    private val policy: KitHarnessPolicyResolver,
    private val context: KitContextResolver,
    private val telemetry: KitRuntimeTelemetry,
    private val delegations: KitDelegationService,
) {
    private val log = LoggerFactory.getLogger(KitRuntimeDriver::class.java)
    private val models = AcpModelControls(json)
    private val runtime = KitRuntimeClient(json)
    private val http = HttpClient(CIO) { install(WebSockets) }
    private val environmentSerializer = MapSerializer(String.serializer(), String.serializer())
    private fun configuration(session: KitRuntimeSession) = json.decodeFromJsonElement(KitHarnessConfiguration.serializer(), session.configuration)

    /** Claims one session and renews ownership while an inference or archive operation is in progress. */
    suspend fun reconcile(sessionId: UUID) = Tracing.withTrace {
        val owner = UUID.random()
        val claimed = transaction {
            val session = repository.lock(sessionId) ?: return@transaction null
            if (session.leaseUntil?.isAfter(OffsetDateTime.now()) == true) return@transaction null
            session.copy(owner = owner, epoch = session.epoch + 1, leaseUntil = OffsetDateTime.now().plusSeconds(60))
                .also { check(repository.save(it) == 1) }
        } ?: return@withTrace
        val followUp = KitRuntimeFollowUp()
        try {
            withContext(followUp) {
                try {
                    if (claimed.deletedAt != null) {
                        purge(claimed)
                        return@withContext
                    }
                    coroutineScope {
                        val renewal = launch {
                            while (isActive) {
                                delay(15.seconds)
                                val current = withConnectionManager {
                                    check(repository.renew(sessionId, owner, claimed.epoch) == 1) { "Kit runtime ownership lost" }
                                    owned(sessionId, owner, claimed.epoch)
                                }
                                if (current.runtimeInstanceId != null && current.state in setOf("READY", "BUSY", "WAITING_FOR_APPROVAL")) {
                                    // The heartbeat detects a replaced runtime; the turn's own connection reports a lost one.
                                    try {
                                        runtime.status(current, environment(current).getValue("BOSCA_RUNTIME_TOKEN"))
                                    } catch (replaced: KitRuntimeReplacedException) {
                                        throw replaced
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        log.warn("Kit runtime status heartbeat failed for session {}", sessionId, failure)
                                    }
                                }
                            }
                        }
                        try { telemetry.observe(RECONCILE, claimed) { drive(sessionId, owner, claimed.epoch) } } finally { renewal.cancelAndJoin() }
                    }
                } catch (replaced: KitRuntimeReplacedException) {
                    val current = owned(sessionId, owner, claimed.epoch)
                    if (current.generation == replaced.generation) failRuntime(current, requireNotNull(replaced.message))
                }
            }
        } finally {
            withContext(NonCancellable) {
                transaction {
                    val current = repository.lock(sessionId)
                    if (current?.owner == owner && current.epoch == claimed.epoch) {
                        check(repository.save(current.copy(owner = null, leaseUntil = null)) == 1)
                    }
                    // Published at commit, after the lease is released, so the next delivery can claim the session.
                    if (followUp.consume()) KitRuntimeJob(sessionId).enqueue()
                }
            }
        }
    }

    /** Requests another reconcile; inside a reconcile it is deferred until that reconcile releases its lease. */
    private suspend fun requestFollowUp(sessionId: UUID) {
        currentCoroutineContext()[KitRuntimeFollowUp]?.request() ?: KitRuntimeJob(sessionId).enqueue()
    }

    private suspend fun purge(session: KitRuntimeSession) = telemetry.observe(DELETE, session) {
        if (!delegations.childrenStopped(session.sessionId)) throw DelayException(5.seconds)
        retireCredential(session)
        session.dispatchId?.let {
            provider.stop(configuration(session), it)
            if (!provider.isStopped(configuration(session), it)) throw DelayException(5.seconds)
        }
        if (session.dispatchId != null || session.credentialId != null) transaction {
            val current = requireNotNull(repository.lock(session.sessionId))
            check(current.owner == session.owner && current.epoch == session.epoch && current.deletedAt != null)
            check(repository.save(current.copy(dispatchId = null, endpoint = null, secrets = null, credentialId = null)) == 1)
        }
        for (archive in repository.archives(session.sessionId)) {
            storage.delete(StringObjectPath(archive.key))
            transaction {
                val current = requireNotNull(repository.lock(session.sessionId))
                check(current.owner == session.owner && current.epoch == session.epoch && current.deletedAt != null)
                check(repository.deleteArchive(session.sessionId, archive.key) == 1)
            }
        }
        transaction {
            val current = requireNotNull(repository.lock(session.sessionId))
            check(current.owner == session.owner && current.epoch == session.epoch)
            current.activeTurn?.let { repository.turn(it) }?.let { turn ->
                delegations.finish(current, turn, if (turn.status == "SENT") "INTERRUPTED" else "CANCELLED", "")
            }
            delegations.reconcile(current)
            repository.deleteApprovals(session.sessionId)
            repository.deleteTurns(session.sessionId)
            check(repository.deleteSession(session.sessionId, requireNotNull(session.owner), session.epoch) == 1)
            afterCommit { telemetry.record(CLEANUP, "success", session, durationMillis = elapsedSince(session.deletedAt)) }
        }
    }

    /**
     * Revokes and then deletes a generation's ephemeral credential, as its issuance contract requires. Both steps are
     * safe to repeat; a credential its owner already deleted can no longer authenticate.
     */
    private suspend fun retireCredential(session: KitRuntimeSession) {
        val credentialId = session.credentialId ?: return
        telemetry.observe(CREDENTIAL_REVOKE, session) {
            if (tokens.getTokenById(credentialId) != null) {
                tokens.revokeToken(credentialId, session.principalId)
                tokens.deleteToken(credentialId, session.principalId)
            }
        }
    }

    private suspend fun drive(id: UUID, owner: UUID, epoch: Long) {
        var session = maintainArchives(owned(id, owner, epoch))
        delegations.reconcile(session)
        val childDeadline = delegations.deadline(id)
        if (childDeadline?.isBefore(OffsetDateTime.now()) == true && session.state !in setOf("FAILED", "STOPPED", "STOPPING")) {
            failRuntime(session, "The child task reached its fixed deadline. Its submitted work was not replayed.")
            return
        }
        if (childDeadline?.isBefore(OffsetDateTime.now()) == true && session.dispatchId == null && repository.next(id) != null) {
            val queued = requireNotNull(repository.next(id))
            complete(session, queued, "FAILED", null, "The child task expired before dispatch.")
            return
        }
        val activating = session.state == "STARTING" || session.contextReset != null
        if (session.state == "STARTING" && session.cancelRequestedAt != null) {
            cancelStartup(session)
            return
        }
        if (session.failure != null && session.cancelRequestedAt != null) {
            failRuntime(session, requireNotNull(session.failure))
            return
        }
        if (session.activeTurn == null && session.state !in setOf("STOPPING", "FAILED") &&
            !(session.state == "STARTING" && session.dispatchId != null)) {
            session = refreshPolicy(session)
            if (session.dispatchId == null) session = maintainArchives(session)
        }
        if (session.activeTurn == null && session.state !in setOf("STOPPING", "FAILED") &&
            !(session.state == "STARTING" && session.dispatchId != null) && (activating || repository.next(id) != null)) {
            if (session.contextReset != null && session.dispatchId == null) session = resetContext(session)
            val resolved = prepareContext(session)
            session = refreshContext(session, resolved.manifest)
            if (session.contextReset != null && session.dispatchId == null) session = resetContext(session)
        }
        if (session.state == "STOPPED") {
            if (repository.next(id) == null && !activating) return
            session = changeCurrent(session) { freshGeneration() }
        }
        if (session.state == "FAILED") return
        val stopped = session.dispatchId?.let { provider.isStopped(configuration(session), it) } == true
        val committedKey = "kit/runtime/${session.sessionId}/${session.generation}.tar.gz"
        if (stopped && !(session.state == "STOPPING" && session.archiveKey == committedKey)) {
            failRuntime(session, "Runtime ended before archival; local changes since the last archive may be lost.")
            return
        }
        val config = json.decodeFromJsonElement(KitHarnessConfiguration.serializer(), session.configuration)
        if (session.state == "STARTING") {
            session = start(session, config)
            if (session.state in setOf("STOPPED", "FAILED")) { delegations.reconcile(session); return }
            if (session.cancelRequestedAt != null) {
                cancelStartup(session)
                return
            }
            session = refreshPolicy(session)
        }
        val token = environment(session).getValue("BOSCA_RUNTIME_TOKEN")
        if (session.state == "STOPPING" && session.archiveKey == "kit/runtime/${session.sessionId}/${session.generation}.tar.gz") {
            archive(session, config, token)
            return
        }
        val remaining = java.time.Duration.between(java.time.Instant.now(),
            java.time.Instant.ofEpochSecond(environment(session).getValue("BOSCA_RUNTIME_EXPIRES").toLong())).toMillis()
        if (remaining > 0 && withTimeoutOrNull(remaining) { driveRuntime(session, config, token); true } == true) return
        failRuntime(owned(id, owner, epoch), "The harness reached its hard-runtime deadline. Queued messages and the last committed archive have been preserved.")
    }

    private suspend fun driveRuntime(initial: KitRuntimeSession, config: KitHarnessConfiguration, token: String) {
        var session = initial
        val id = session.sessionId
        val owner = requireNotNull(session.owner)
        val epoch = session.epoch
        val status = runtime.claim(session, token)
        check(status.sessionId == id.toString() && status.generation == session.generation) { "Runtime identity mismatch" }
        if (status.instanceId != session.runtimeInstanceId) throw KitRuntimeReplacedException(session.generation)
        if (status.phase == "FAILED") {
            failRuntime(session, status.failure ?: "The native harness exited; the active turn was not replayed.")
            return
        }
        if (session.state == "STOPPING") {
            archive(session, config, token)
            return
        }
        if (session.activeTurn != null) {
            val turn = requireNotNull(repository.turn(requireNotNull(session.activeTurn)))
            if (turn.status == "SENT") {
                val receipt = runtime.turn(session, token, turn.id.toString())
                if (receipt == null) {
                    complete(session, turn, "INTERRUPTED", null, "The runtime could not confirm this turn's outcome. It was not replayed.")
                } else if (receipt.complete) {
                    val failed = receipt.messages.lastOrNull()?.containsKey("error") == true
                    val transcript = transcript(session, turn, receipt)
                    val stop = receipt.messages.lastOrNull()?.get("result")?.jsonObject?.get("stopReason")?.jsonPrimitive?.content
                    complete(session, turn, if (failed) "FAILED" else if (stop == "cancelled") "CANCELLED" else "COMPLETED",
                        json.encodeToJsonElement(RuntimeTurnReceipt.serializer(), receipt),
                        if (failed) transcript.responseText + "\nThe native harness reported a failed turn. It was not replayed." else transcript.responseText, transcript)
                } else {
                    persistTranscript(session, transcript(session, turn, receipt).snapshot(force = true))
                    session = applyCancellation(session, turn, config, token)
                    if (session.cancelRequestedAt == null) reconcilePermissions(session, turn, receipt, token)
                    throw DelayException(if (session.cancelRequestedAt == null) 5.seconds else 1.seconds)
                }
                return
            }
            runTurn(session, turn, config, token)
            return
        }
        val expires = environment(session).getValue("BOSCA_RUNTIME_EXPIRES").toLong()
        val retire = status.retireRequested || java.time.Instant.now().epochSecond >= expires - minOf(300, config.hardRuntimeSeconds / 10)
        val next = transaction {
            val locked = owned(id, owner, epoch, lock = true)
            val turn = repository.next(id)
            if (retire && repository.approvals(id).isEmpty()) {
                check(repository.save(locked.copy(state = "STOPPING")) == 1)
            } else if (turn != null) {
                check(repository.save(locked.copy(state = "BUSY", activeTurn = turn.id)) == 1)
            } else if (locked.idleAt.isBefore(OffsetDateTime.now()) && repository.approvals(id).isEmpty()) {
                check(repository.save(locked.copy(state = "STOPPING")) == 1)
            }
            if (retire) null else turn
        }
        session = owned(id, owner, epoch)
        if (next != null) runTurn(session, next, config, token)
        else if (session.state == "STOPPING") archive(session, config, token)
    }

    private fun sameNativeEnvironment(previous: KitHarnessResolution, current: KitHarnessResolution): Boolean {
        val before = previous.configuration
        val after = current.configuration
        return before.profile == after.profile && before.infrastructure == after.infrastructure &&
            before.versions.statePolicy == after.versions.statePolicy &&
            before.boscaEndpoint == after.boscaEndpoint && before.authenticationMethod == after.authenticationMethod &&
            before.mcpExecutables == after.mcpExecutables && before.mcpSecretEnvironment == after.mcpSecretEnvironment &&
            previous.authority == current.authority && previous.mcpConnections == current.mcpConnections && previous.modelPolicy == current.modelPolicy
    }

    private suspend fun refreshPolicy(session: KitRuntimeSession): KitRuntimeSession {
        var policyFailure: IllegalArgumentException? = null
        val updated = transaction {
            val current = owned(session.sessionId, requireNotNull(session.owner), session.epoch, true)
            val ceiling = json.decodeFromJsonElement(KitRuntimeAuthority.serializer(), current.authority)
            val choice = current.requestedSettings?.let { json.decodeFromJsonElement(KitHarnessChoice.serializer(), it) }
            val resolved = try {
                delegations.resolution(current) ?: policy.resolve(current.principalId, ceiling, choice, history.getSession(current.sessionId)?.agentKey ?: "kit")
            } catch (error: IllegalArgumentException) {
                policyFailure = error
                return@transaction change(current) { copy(state = if (dispatchId == null) "FAILED" else "STOPPING",
                    failure = if (error is KitMcpConfigurationException) error.message else "Harness policy is unavailable or no longer permits this conversation. An administrator must review its configuration.") }
            }
            val previous = current.resolvedSettings?.let { json.decodeFromJsonElement(KitHarnessResolution.serializer(), it) }
            val changed = previous != null && (previous.configuration != resolved.configuration || previous.authority != resolved.authority || previous.mcpConnections != resolved.mcpConnections || previous.modelPolicy != resolved.modelPolicy)
            val retainNative = changed && previous?.let { sameNativeEnvironment(it, resolved) } == true
            val resetNative = changed && !retainNative
            if (changed && current.dispatchId != null) {
                // Drain and verify the old archive before selecting a new runtime profile.
                return@transaction change(current) { copy(state = "STOPPING") }
            }
            val effective = resolved.copy(
                modelCapabilities = if (changed) null else previous?.modelCapabilities,
                mcpNotices = if (changed) resolved.mcpNotices else (resolved.mcpNotices + previous?.mcpNotices.orEmpty()).distinct(),
                notice = resolved.notice ?: if (retainNative) {
                    "The harness runtime settings were updated. Its saved operational directory will be reopened by the native harness."
                } else if (changed) {
                    if (current.archiveKey != null) "Harness settings changed. A new session will use permitted conversation history and the saved workspace."
                    else "Harness settings changed. A new session will use permitted conversation history."
                } else previous?.notice,
                requested = resolved.requested ?: previous?.requested?.takeIf { current.needsContext && previous.notice != null })
            effective.notice?.takeIf { resolved.notice != null || changed }?.let {
                sessions.recordResponse(current.sessionId, KitResponse.Text(it))
            }
            change(current) {
                copy(configuration = json.encodeToJsonElement(KitHarnessConfiguration.serializer(), resolved.configuration),
                    resolvedSettings = json.encodeToJsonElement(KitHarnessResolution.serializer(), effective),
                    requestedSettings = if (resolved.notice == null) requestedSettings else null,
                    nativeSessionId = if (resetNative) null else nativeSessionId,
                    restoreWorkspaceOnly = restoreWorkspaceOnly || resetNative,
                    needsContext = needsContext || resetNative)
            }
        }
        if (updated.state == "FAILED") {
            delegations.reconcile(updated)
            history.setProcessing(session.sessionId, false)
            history.dispatchStatus(session.sessionId, ChatSessionStatus.FAILED)
            throw requireNotNull(policyFailure)
        }
        return updated
    }

    private suspend fun failRuntime(session: KitRuntimeSession, reason: String) {
        delegations.cancelChildren(session.sessionId)
        val stopping = changeCurrent(session) { copy(failure = reason, cancelRequestedAt = cancelRequestedAt ?: OffsetDateTime.now()) }
        retireCredential(stopping)
        stopping.dispatchId?.let {
            provider.stop(configuration(stopping), it)
            if (!provider.isStopped(configuration(stopping), it)) throw DelayException(1.seconds)
        }
        transaction {
            val current = owned(session.sessionId, requireNotNull(session.owner), session.epoch, true)
            current.activeTurn?.let { repository.turn(it) }?.takeIf { it.status == "SENT" }?.let { turn ->
                repository.finish(turn.id, "INTERRUPTED", JsonPrimitive(reason))
                repository.closeApprovals(turn.id)
                history.upsertMessage(terminalMessage(current, turn, "INTERRUPTED"))
                sessions.recordResponse(session.sessionId, KitResponse.Text(reason))
                afterCommit { telemetry.record(TURN, "interrupted", current, turn,
                    durationMillis = turn.deadline?.let { elapsedSince(it.minusSeconds(json.decodeFromJsonElement(KitHarnessConfiguration.serializer(), current.configuration).promptSeconds)) }) }
            }
            check(repository.save(current.copy(state = "FAILED", activeTurn = null, cancelRequestedAt = null, failure = reason)) == 1)
            delegations.reconcile(requireNotNull(repository.get(session.sessionId)))
            history.setProcessing(session.sessionId, false)
            history.dispatchStatus(session.sessionId, ChatSessionStatus.FAILED)
            afterCommit { telemetry.record(CLEANUP, "success", current, durationMillis = elapsedSince(current.cancelRequestedAt)) }
        }
    }

    private suspend fun cancelStartup(session: KitRuntimeSession) {
        val cancelling = if (session.cancelRequestedAt == null) changeCurrent(session) {
            copy(cancelRequestedAt = cancelRequestedAt ?: OffsetDateTime.now())
        } else session
        retireCredential(cancelling)
        cancelling.dispatchId?.let {
            provider.stop(configuration(cancelling), it)
            if (!provider.isStopped(configuration(cancelling), it)) throw DelayException(1.seconds)
        }
        val stopped = changeCurrent(cancelling) { copy(state = if (failure == null) "STOPPED" else "FAILED", cancelRequestedAt = null, dispatchId = null,
            endpoint = null, secrets = null, credentialId = null, nativeSessionId = if (archiveKey == null) null else nativeSessionId) }
        history.setProcessing(session.sessionId, stopped.failure == null && repository.next(session.sessionId) != null)
        if (stopped.failure != null) history.dispatchStatus(session.sessionId, ChatSessionStatus.FAILED)
        delegations.reconcile(stopped)
        telemetry.record(STARTUP, if (stopped.failure == null) "cancelled" else "failure", stopped)
        telemetry.record(CLEANUP, "success", cancelling, durationMillis = elapsedSince(cancelling.cancelRequestedAt))
        requestFollowUp(session.sessionId)
    }

    private suspend fun applyCancellation(session: KitRuntimeSession, turn: KitRuntimeTurn, config: KitHarnessConfiguration, token: String): KitRuntimeSession {
        if (session.activeTurn != turn.id) return session
        val deadline = requireNotNull(turn.deadline)
        val current = if (!deadline.isAfter(OffsetDateTime.now()) &&
            (session.cancelRequestedAt == null || deadline.isBefore(session.cancelRequestedAt))) transaction {
            val locked = owned(session.sessionId, requireNotNull(session.owner), session.epoch, true)
            if (locked.activeTurn != turn.id) return@transaction locked
            repository.closeApprovals(turn.id)
            change(locked) { copy(cancelRequestedAt = minOf(cancelRequestedAt ?: deadline, deadline)) }
        } else session
        val requested = current.cancelRequestedAt ?: return current
        if (requested.plusSeconds(config.cancellationSeconds).isBefore(OffsetDateTime.now())) {
            failRuntime(current, "The harness did not confirm cancellation. The turn was interrupted; completed actions were not undone.")
            error("Native cancellation deadline exceeded")
        }
        runtime.cancel(current, token, turn.id.toString())
        return current
    }

    private suspend fun start(initial: KitRuntimeSession, config: KitHarnessConfiguration): KitRuntimeSession {
        var session = initial
        if (session.secrets == null) {
            val deployment = policy.deployment()
            session = transaction {
                check(repository.lockAdmission())
                val locked = owned(initial.sessionId, requireNotNull(initial.owner), initial.epoch, true)
                if (!repository.hasCapacity(locked.principalId, deployment.maxRuntimes, deployment.maxRuntimesPerPrincipal)) {
                    val limited = change(locked) { copy(state = "FAILED",
                        failure = "Harness capacity is in use. Retry after another session has been archived or stopped.") }
                    history.setProcessing(locked.sessionId, false)
                    history.dispatchStatus(locked.sessionId, ChatSessionStatus.FAILED)
                    afterCommit { telemetry.record(ADMISSION, "denied", limited) }
                    return@transaction limited
                }
                val authority = json.decodeFromJsonElement(KitHarnessResolution.serializer(), requireNotNull(locked.resolvedSettings)).authority
                val issued = OffsetDateTime.now()
                val childDeadline = delegations.deadline(locked.sessionId)
                require(childDeadline?.isAfter(issued) != false) { "Child task expired before credential issuance" }
                val expires = minOf(issued.plusSeconds(config.credentialLifetimeSeconds), childDeadline ?: issued.plusSeconds(config.credentialLifetimeSeconds))
                val apiToken = telemetry.observe(CREDENTIAL_ISSUE, locked) { tokens.createEphemeralToken(locked.principalId,
                    ApiTokenInput("Kit ${locked.sessionId} generation ${locked.generation}", scopes = authority.scopes,
                        allowedGroups = authority.groups, expiresAt = expires.toString()), locked.principalId, sessionBound = true) }
                val controlToken = randomToken()
                val values = mapOf("BOSCA_TOKEN" to apiToken.rawToken, "BOSCA_RUNTIME_TOKEN" to controlToken,
                    "BOSCA_RUNTIME_EXPIRES" to minOf(issued.plusSeconds(config.hardRuntimeSeconds), expires).toEpochSecond().toString()) +
                    if (config.egressProxy == null) emptyMap() else mapOf("BOSCA_EGRESS_TOKEN" to randomToken())
                val encrypted = encryption.encrypt(json.encodeToString(environmentSerializer, values).encodeToByteArray(), locked.sessionId)
                val secret = KitRuntimeSecrets(locked.sessionId, Base64.getEncoder().encodeToString(encrypted.nonce),
                    Base64.getEncoder().encodeToString(encrypted.data), values.keys.toList())
                val dispatch = provider.ensure(config,
                    "kit:${locked.sessionId}:${locked.generation}", environment = mapOf(
                        "BOSCA_CHAT_SESSION_ID" to locked.sessionId.toString(), "BOSCA_RUNTIME_GENERATION" to locked.generation.toString(),
                        "BOSCA_ENDPOINT" to config.boscaEndpoint, "BOSCA_ARCHIVE_MAX_BYTES" to config.maxArchiveBytes.toString(),
                        "BOSCA_JOURNAL_MAX_BYTES" to config.maxJournalBytes.toString(), "BOSCA_JOURNAL_MAX_TURNS" to config.maxJournalTurns.toString(),
                        "BOSCA_MCP_SECRET_NAMES" to json.encodeToString(ListSerializer(String.serializer()), config.mcpSecretEnvironment)) +
                        (config.egressProxy?.let { mapOf("BOSCA_EGRESS_PROXY" to it) } ?: emptyMap()),
                    secrets = secret)
                val updated = locked.copy(dispatchId = dispatch, credentialId = apiToken.credential.id,
                    secrets = json.encodeToJsonElement(KitRuntimeSecrets.serializer(), secret))
                check(repository.save(updated) == 1)
                afterCommit { telemetry.record(ADMISSION, "success", updated) }
                updated.copy(version = updated.version + 1)
            }
            if (session.state == "FAILED") return session
        }
        val execution = provider.status(config, requireNotNull(session.dispatchId)) ?: error("Runtime dispatch disappeared")
        if (execution.terminal) {
            failRuntime(session, execution.failure ?: "Runtime terminated before activation. Queued messages have been preserved.")
            return owned(session.sessionId, requireNotNull(session.owner), session.epoch)
        }
        val startupDeadline = execution.created.plusSeconds(config.startupSeconds).toInstant()
        val hardDeadline = java.time.Instant.ofEpochSecond(environment(session).getValue("BOSCA_RUNTIME_EXPIRES").toLong())
        val deadline = minOf(startupDeadline, hardDeadline)
        val remaining = java.time.Duration.between(java.time.Instant.now(), deadline).toMillis()
        if (remaining > 0) {
            try {
                withTimeoutOrNull(remaining) { activate(session, config, execution) }?.let { return it }
            } catch (failure: AcpConnectionException) {
                currentCoroutineContext().ensureActive()
                // Runtime expiry can close ACP before the Runner observes its own timeout.
                if (java.time.Instant.now().isBefore(deadline)) throw failure
            }
        }
        // The dispatch timestamp survives queue redelivery and Runner replacement. Persist cleanup
        // intent before external calls so a failed revocation/removal cannot restart expired work.
        val current = owned(session.sessionId, requireNotNull(session.owner), session.epoch)
        if (current.state != "STARTING") return current
        val expired = changeCurrent(current) { copy(
            failure = if (hardDeadline <= startupDeadline) "The harness reached its hard-runtime deadline during startup. Queued messages have been preserved."
                else "The harness did not become ready before the startup deadline. Queued messages have been preserved.",
            cancelRequestedAt = OffsetDateTime.now()) }
        cancelStartup(expired)
        return owned(session.sessionId, requireNotNull(session.owner), session.epoch)
    }

    private suspend fun activate(initial: KitRuntimeSession, config: KitHarnessConfiguration, execution: KitRuntimeExecution): KitRuntimeSession {
        var session = initial
        if (execution.endpoint == null) throw DelayException(5.seconds)
        if (execution.image == null || execution.imageId == null) throw DelayException(5.seconds)
        val observedDigest = execution.imageId.substringAfterLast('@').substringAfterLast('/')
        val pinnedDigests = setOf(config.image.substringAfterLast('@'), config.imageId.substringAfterLast('@').substringAfterLast('/'))
        if (execution.image != config.image || observedDigest !in pinnedDigests) {
            cancelStartup(changeCurrent(session) { copy(failure = "The harness image does not match its deployment profile. Queued messages have been preserved.", cancelRequestedAt = OffsetDateTime.now()) })
            return owned(session.sessionId, requireNotNull(session.owner), session.epoch)
        }
        if (session.endpoint == null) session = changeCurrent(session) {
            copy(endpoint = execution.endpoint)
        }
        val original = json.decodeFromJsonElement(KitHarnessResolution.serializer(), requireNotNull(session.resolvedSettings))
        val currentPolicy = try {
            delegations.resolution(session) ?: policy.resolve(session.principalId, json.decodeFromJsonElement(KitRuntimeAuthority.serializer(), session.authority),
                session.requestedSettings?.let { json.decodeFromJsonElement(KitHarnessChoice.serializer(), it) }, history.getSession(session.sessionId)?.agentKey ?: "kit")
        } catch (error: IllegalArgumentException) {
            val reason = if (error is KitMcpConfigurationException) error.message else "Harness policy is unavailable. Review its configuration before resuming."
            cancelStartup(changeCurrent(session) { copy(failure = reason, cancelRequestedAt = OffsetDateTime.now()) })
            return owned(session.sessionId, requireNotNull(session.owner), session.epoch)
        }
        if (original.configuration != currentPolicy.configuration || original.authority != currentPolicy.authority || original.mcpConnections != currentPolicy.mcpConnections || original.modelPolicy != currentPolicy.modelPolicy) {
            cancelStartup(session)
            return owned(session.sessionId, requireNotNull(session.owner), session.epoch)
        }
        // Readiness can take long enough for context access to change. No native files have to be
        // restored to cancel this prepared generation; the previous committed archive stays intact.
        val previousContext = session.contextManifest?.let { json.decodeFromJsonElement(KitContextManifest.serializer(), it) }
        if (previousContext != null && previousContext.revision != prepareContext(session).manifest.revision) {
            val mode = if (context.canRestoreWorkspace(session)) "WORKSPACE" else "FRESH"
            session = changeCurrent(session) { copy(contextReset = mode) }
            cancelStartup(session)
            return owned(session.sessionId, requireNotNull(session.owner), session.epoch)
        }
        val token = environment(session).getValue("BOSCA_RUNTIME_TOKEN")
        val status = runtime.claim(session, token)
        if (status.sessionId != session.sessionId.toString() || status.generation != session.generation || status.epoch != session.epoch || status.versions != config.versions) {
            cancelStartup(changeCurrent(session) { copy(failure = "The harness runtime identity does not match its deployment profile. Queued messages have been preserved.", cancelRequestedAt = OffsetDateTime.now()) })
            return owned(session.sessionId, requireNotNull(session.owner), session.epoch)
        }
        if (session.runtimeInstanceId == null) {
            require(status.instanceId.isNotBlank()) { "Runtime process identity is missing" }
            session = changeCurrent(session) { copy(runtimeInstanceId = status.instanceId) }
        } else if (session.runtimeInstanceId != status.instanceId) throw KitRuntimeReplacedException(session.generation)
        if (session.archiveKey != null && status.phase == "PREPARED") {
            telemetry.observe(RESTORE, session, bytes = session.archiveSize, ageMillis = elapsedSince(session.archiveCreated)) { phase("restore", config.restoreSeconds) {
                withArchiveFile { file ->
                    storage.getInputStream(StringObjectPath(requireNotNull(session.archiveKey))).use { input ->
                        try {
                            runInterruptible(Dispatchers.IO) {
                                Files.newOutputStream(file).use { output -> copyBounded(input, output, config.maxArchiveBytes) }
                            }
                        } catch (failure: java.io.IOException) {
                            currentCoroutineContext().ensureActive()
                            throw failure
                        }
                    }
                    check(hash(file) == session.archiveSha256 && Files.size(file) == session.archiveSize) { "Stored archive verification failed" }
                    runtime.restore(session, token, file)
                }
            } }
        }
        runtime.start(session, token)
        // Readiness includes ACP setup, including native restoration. No prompt is submitted here.
        try {
            withSession(session, token, null) { _, selection ->
                session = changeCurrent(session) { copy(state = "READY", nativeSessionId = selection.session.sessionId.value, idleAt = OffsetDateTime.now().plusSeconds(config.idleSeconds)) }
                telemetry.record(STARTUP, "success", session, durationMillis = elapsedSince(execution.created))
            }
        } catch (error: KitMcpConfigurationException) {
            val failed = changeCurrent(session) { copy(failure = error.message, cancelRequestedAt = OffsetDateTime.now()) }
            cancelStartup(failed)
            session = owned(session.sessionId, requireNotNull(session.owner), session.epoch)
        }
        return session
    }

    private suspend fun runTurn(session: KitRuntimeSession, turn: KitRuntimeTurn, config: KitHarnessConfiguration, token: String) {
        if (owned(session.sessionId, requireNotNull(session.owner), session.epoch).cancelRequestedAt != null) {
            complete(session, turn, "CANCELLED", null, "")
            return
        }
        val resolvedContext = prepareContext(session, turn)
        val checked = refreshContext(session, resolvedContext.manifest)
        if (checked.contextReset != null) return
        val message = json.decodeFromJsonElement(ChatMessageInput.serializer(), turn.message)
        withSession(session, token, turn) { connection, selection ->
            val sent = transaction {
                val current = owned(session.sessionId, requireNotNull(session.owner), session.epoch, true)
                if (current.cancelRequestedAt != null) return@transaction null
                val manifest = resolvedContext.manifest.copy(format = if (connection.agentInfo.capabilities.promptCapabilities.embeddedContext) "resource" else "text")
                val encoded = json.encodeToJsonElement(KitContextManifest.serializer(), manifest)
                check(repository.resolve(turn.id, requireNotNull(current.resolvedSettings)) == 1)
                check(repository.context(turn.id, encoded) == 1)
                check(repository.save(current.copy(contextManifest = encoded)) == 1)
                val deadline = OffsetDateTime.now().plusSeconds(config.promptSeconds)
                check(repository.send(turn.id, minOf(deadline, delegations.deadline(session.sessionId) ?: deadline)) == 1)
                history.dispatchStatus(session.sessionId, ChatSessionStatus.STREAMING)
                requireNotNull(repository.turn(turn.id))
            }
            if (sent == null) {
                complete(session, turn, "CANCELLED", null, "")
                return@withSession
            }
            val transcript = KitRuntimeTranscript(session, turn, json)
            telemetry.record(TURN, "started", session, sent)
            telemetry.record(CONTEXT, "success", session, sent)
            coroutineScope {
                val cancellation = launch {
                    while (isActive) {
                        delay(1.seconds)
                        withConnectionManager {
                            val current = owned(session.sessionId, requireNotNull(session.owner), session.epoch)
                            applyCancellation(current, sent, config, token)
                        }
                        val waiting = withConnectionManager { delegations.waiting(sent.id) }
                        if (waiting) throw KitDelegationWaitException()
                    }
                }
                try {
                    val response = AIAgentService(promptExecutor = executor,
                        agentConfig = AIAgentConfig(prompt = prompt("kit-acp") {},
                            model = LLModel(OpenAILLMProvider, "remote-harness", listOf(LLMCapability.Completion)), maxAgentIterations = 3),
                        strategy = kitAcpStrategy()).createAgentAndRun(KitAcpTurn(connection, selection.session,
                            resolvedContext.content(connection.agentInfo.capabilities.promptCapabilities.embeddedContext) + message.parts.map { ContentBlock.Text(it.text) }, buildJsonObject {
                                put("turnId", turn.id.toString()); put("generation", session.generation); put("epoch", session.epoch)
                            }) { event ->
                            if (event is Event.SessionUpdateEvent) {
                                transcript.accept(event.update)
                                persistTranscript(session, transcript.snapshot())
                            }
                        })
                    complete(session, turn, if (response.stopReason == StopReason.CANCELLED) "CANCELLED" else "COMPLETED",
                        json.encodeToJsonElement(PromptResponse.serializer(), response), transcript.responseText, transcript)
                } finally { cancellation.cancelAndJoin() }
            }
        }
    }

    private suspend fun configureModels(session: KitRuntimeSession, native: com.agentclientprotocol.client.ClientSession): KitRuntimeSession {
        val current = owned(session.sessionId, requireNotNull(session.owner), session.epoch)
        val resolved = json.decodeFromJsonElement(KitHarnessResolution.serializer(), requireNotNull(current.resolvedSettings))
        return try {
            val capabilities = models.apply(native, resolved.modelSettings, resolved.configuration.readinessSeconds * 1000)
            changeCurrent(current) { copy(resolvedSettings = json.encodeToJsonElement(KitHarnessResolution.serializer(),
                resolved.copy(modelCapabilities = capabilities))) }
        } catch (error: KitModelConfigurationException) {
            if (current.state == "STARTING") {
                cancelStartup(changeCurrent(current) { copy(failure = error.message, cancelRequestedAt = OffsetDateTime.now()) })
            } else {
                changeCurrent(current) { copy(state = "STOPPING", activeTurn = null, failure = error.message) }
            }
            history.setProcessing(session.sessionId, false)
            history.dispatchStatus(session.sessionId, ChatSessionStatus.FAILED)
            throw error
        }
    }

    private suspend fun prepareContext(session: KitRuntimeSession, turn: KitRuntimeTurn? = null): KitResolvedContext = try {
        context.resolve(session, turn).also { delegations.validateContext(session.sessionId, it.manifest) }
    } catch (error: IllegalArgumentException) {
        val reason = "Context could not be prepared. Review the configured agent, prompt, and context budget before resuming."
        if (session.state == "STARTING" && session.dispatchId != null) {
            // A prepared generation has no accepted work to archive. Keep the preceding archive,
            // and retain cancellation intent if the controller needs another delivery to remove it.
            val cancelling = changeCurrent(session) { copy(failure = reason, cancelRequestedAt = OffsetDateTime.now()) }
            cancelStartup(cancelling)
        } else {
            changeCurrent(session) { copy(state = if (dispatchId == null) "FAILED" else "STOPPING", activeTurn = null, failure = reason) }
        }
        history.setProcessing(session.sessionId, false)
        history.dispatchStatus(session.sessionId, ChatSessionStatus.FAILED)
        delegations.reconcile(requireNotNull(repository.get(session.sessionId)))
        throw error
    }

    private suspend fun refreshContext(session: KitRuntimeSession, manifest: KitContextManifest): KitRuntimeSession {
        val previous = session.contextManifest?.let { json.decodeFromJsonElement(KitContextManifest.serializer(), it) } ?: return session
        if (previous.revision == manifest.revision) return session
        // Native state and derived workspace files may contain bodies whose source is now unavailable.
        val mode = if (context.canRestoreWorkspace(session)) "WORKSPACE" else "FRESH"
        return changeCurrent(session) { copy(state = if (dispatchId == null) "STOPPED" else "STOPPING",
            activeTurn = null, contextReset = mode) }
    }

    private suspend fun resetContext(session: KitRuntimeSession): KitRuntimeSession = transaction {
        val current = owned(session.sessionId, requireNotNull(session.owner), session.epoch, true)
        val fresh = current.contextReset == "FRESH"
        context.clearMemoryReads(session.sessionId)
        sessions.recordResponse(session.sessionId, KitResponse.Text(if (fresh)
            "Previous context changed or is no longer accessible. Kit is starting a fresh session without the previous workspace or conversation context. The previous workspace will not be restored."
            else if (current.archiveKey != null) "Context changed. Kit is starting a fresh session with the saved workspace and current sources."
            else "Context changed. Kit is starting a fresh session with current sources."))
        change(current) { copy(contextManifest = null, contextReset = null, nativeSessionId = null,
            needsContext = false, restoreWorkspaceOnly = !fresh,
            archiveKey = if (fresh) null else archiveKey, archiveSha256 = if (fresh) null else archiveSha256,
            archiveSize = if (fresh) null else archiveSize, archiveCreated = if (fresh) null else archiveCreated) }
    }

    private fun operations(session: KitRuntimeSession, turn: KitRuntimeTurn?) = ClientOperationsFactory { _, _ ->
        object : ClientSessionOperations {
            override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) = Unit
            override suspend fun requestPermissions(toolCall: SessionUpdate.ToolCallUpdate, permissions: List<PermissionOption>, _meta: JsonElement?): RequestPermissionResponse = withConnectionManager {
                val currentTurn = requireNotNull(turn) { "Permission requested outside a turn" }
                val payload = permissionPayload(toolCall, permissions, _meta)
                val requestId = requireNotNull((_meta as? JsonObject)?.get("boscaRuntimeRequestId")?.jsonPrimitive?.content) {
                    "Runtime permission request has no durable request identity"
                }
                val approval = ensureApproval(session, currentTurn, requestId, payload)
                while (true) {
                    val current = requireNotNull(repository.approval(currentTurn.id, approval.requestId))
                    val decision = approvalDecision(session, current)
                    if (decision != null) {
                        changeCurrent(session) {
                            if (activeTurn == currentTurn.id && cancelRequestedAt == null) copy(state = "BUSY") else this
                        }
                        return@withConnectionManager if (decision == "cancelled") RequestPermissionResponse(RequestPermissionOutcome.Cancelled)
                        else RequestPermissionResponse(RequestPermissionOutcome.Selected(PermissionOptionId(decision)))
                    }
                    delay(1.seconds)
                }
                @Suppress("UNREACHABLE_CODE")
                error("Permission polling ended without an outcome")
            }
        }
    }

    private fun permissionPayload(toolCall: SessionUpdate.ToolCallUpdate, permissions: List<PermissionOption>, meta: JsonElement?) = buildJsonObject {
        put("toolCall", json.encodeToJsonElement(SessionUpdate.ToolCallUpdate.serializer(), toolCall))
        put("permissions", json.encodeToJsonElement(ListSerializer(PermissionOption.serializer()), permissions))
        put("operationDigest", requireNotNull(meta?.jsonObject?.get("boscaOperationDigest")) {
            "Runtime permission request has no operation digest"
        })
    }

    private suspend fun ensureApproval(session: KitRuntimeSession, turn: KitRuntimeTurn, tool: String, payload: JsonElement): KitRuntimeApproval = transaction {
        val current = owned(session.sessionId, requireNotNull(session.owner), session.epoch, true)
        val sent = requireNotNull(repository.turn(turn.id))
        val resolution = json.decodeFromJsonElement(KitHarnessResolution.serializer(), requireNotNull(sent.resolvedSettings))
        val request = JsonObject(payload.jsonObject + mapOf("generation" to JsonPrimitive(session.generation),
            "policyVersion" to JsonPrimitive(resolution.policyVersion),
            "turnId" to JsonPrimitive(turn.id.toString()), "principalId" to JsonPrimitive(session.principalId.toString()),
            "digest" to JsonPrimitive(MessageDigest.getInstance("SHA-256").digest(payload.toString().encodeToByteArray()).joinToString("") { "%02x".format(it) })))
        val previous = repository.approval(turn.id, tool)
        check(previous == null || previous.request == request) { "Permission operation changed" }
        repository.addApproval(KitRuntimeApproval(UUID.random(), session.sessionId, turn.id, tool, request,
            decision = if (current.cancelRequestedAt == null && current.activeTurn == turn.id) null else "cancelled",
            expires = minOf(OffsetDateTime.now().plusSeconds(resolution.configuration.approvalSeconds), requireNotNull(sent.deadline))))
        if (current.cancelRequestedAt != null || current.activeTurn != turn.id) repository.closeApprovals(turn.id)
        if (current.activeTurn == turn.id && current.cancelRequestedAt == null) {
            check(repository.save(current.copy(state = "WAITING_FOR_APPROVAL")) == 1)
        }
        requireNotNull(repository.approval(turn.id, tool)).also { approval ->
            if (previous == null) afterCommit { telemetry.record(APPROVAL, "started", session, sent, approval) }
        }
    }

    private suspend fun approvalDecision(session: KitRuntimeSession, approval: KitRuntimeApproval): String? {
        val current = owned(session.sessionId, requireNotNull(session.owner), session.epoch)
        val turn = requireNotNull(repository.turn(approval.turnId))
        val resolution = json.decodeFromJsonElement(KitHarnessResolution.serializer(), requireNotNull(turn.resolvedSettings))
        val valid = current.activeTurn == approval.turnId && current.cancelRequestedAt == null && turn.status == "SENT" &&
            approval.request.jsonObject["generation"]?.jsonPrimitive?.long == current.generation &&
            approval.request.jsonObject["policyVersion"]?.jsonPrimitive?.content == resolution.policyVersion &&
            approval.expires.isAfter(OffsetDateTime.now()) && policy.isApprovalCurrent(current.principalId, resolution)
        if (valid) return approval.decision
        if (approval.decision == null && repository.decide(approval.id, "cancelled") == 1) {
            afterCommit { telemetry.record(APPROVAL, "cancelled", session, turn, approval, durationMillis = elapsedSince(approval.created)) }
        }
        return "cancelled"
    }

    private suspend fun reconcilePermissions(session: KitRuntimeSession, turn: KitRuntimeTurn, receipt: RuntimeTurnReceipt, token: String) {
        receipt.messages.filter { it["method"]?.jsonPrimitive?.content == "session/request_permission" }.forEach { request ->
            val params = request.getValue("params").jsonObject
            val tool = params.getValue("_meta").jsonObject.getValue("boscaRuntimeRequestId").jsonPrimitive.content
            val payload = permissionPayload(
                json.decodeFromJsonElement(SessionUpdate.ToolCallUpdate.serializer(), params.getValue("toolCall")),
                json.decodeFromJsonElement(ListSerializer(PermissionOption.serializer()), params.getValue("options")),
                params["_meta"])
            val approval = ensureApproval(session, turn, tool, payload)
            val decision = approvalDecision(session, approval)
            if (decision != null) {
                runtime.respond(session, token, buildJsonObject {
                    put("jsonrpc", "2.0"); put("id", request.getValue("id"))
                    putJsonObject("result") { putJsonObject("outcome") {
                        put("outcome", if (decision == "cancelled") "cancelled" else "selected")
                        if (decision != "cancelled") put("optionId", decision)
                    } }
                })
            }
        }
    }

    private suspend fun complete(session: KitRuntimeSession, turn: KitRuntimeTurn, status: String, result: JsonElement?, text: String,
                                 transcript: KitRuntimeTranscript? = null) = transaction {
        val current = owned(session.sessionId, requireNotNull(session.owner), session.epoch, true)
        val durableTurn = requireNotNull(repository.turn(turn.id))
        if (durableTurn.status in setOf("COMPLETED", "CANCELLED", "INTERRUPTED", "FAILED")) return@transaction
        check(repository.finish(turn.id, status, result) == 1)
        repository.closeApprovals(turn.id)
        history.upsertMessage(transcript?.snapshot(status, true) ?: terminalMessage(current, turn, status))
        if (text.isNotBlank()) sessions.recordResponse(session.sessionId, KitResponse.Text(text))
        delegations.finish(current, durableTurn, status, text)
        val config = json.decodeFromJsonElement(KitHarnessConfiguration.serializer(), current.configuration)
        val idleAt = if (delegations.deadline(current.sessionId) != null) OffsetDateTime.now() else OffsetDateTime.now().plusSeconds(config.idleSeconds)
        check(repository.save(current.copy(state = "READY", activeTurn = null, cancelRequestedAt = null, needsContext = if (status == "COMPLETED") false else current.needsContext, idleAt = idleAt)) == 1)
        history.dispatchStatus(session.sessionId, if (status in setOf("COMPLETED", "CANCELLED")) ChatSessionStatus.COMPLETED else ChatSessionStatus.FAILED)
        history.setProcessing(session.sessionId, repository.next(session.sessionId) != null)
        requestFollowUp(session.sessionId)
        afterCommit { telemetry.record(TURN, when (status) { "COMPLETED" -> "success"; "CANCELLED" -> "cancelled"; "INTERRUPTED" -> "interrupted"; else -> "failure" },
            current, durableTurn, durationMillis = durableTurn.deadline?.let { elapsedSince(it.minusSeconds(config.promptSeconds)) }) }
    }

    private suspend fun archive(session: KitRuntimeSession, config: KitHarnessConfiguration, token: String) = telemetry.observe(ARCHIVE, session) {
        // A committed archive is retried through deletion only; never export again after commit.
        val key = "kit/runtime/${session.sessionId}/${session.generation}.tar.gz"
        var committed = session
        if (session.archiveKey != key) {
            try {
                committed = phase("archive", config.archiveSeconds) {
                    val receipt = runtime.archive(session, token)
                    check(receipt.formatVersion == 1 && receipt.size in 1..config.maxArchiveBytes)
                    check(receipt.metadata["sessionId"]?.jsonPrimitive?.content == session.sessionId.toString())
                    check(receipt.metadata["generation"]?.jsonPrimitive?.long == session.generation)
                    reserveArchive(session, key, receipt.size)
                    withArchiveFile { file ->
                        runtime.download(session, token, file, config.maxArchiveBytes)
                        check(Files.size(file) == receipt.size && hash(file) == receipt.sha256) { "Runtime archive verification failed" }
                        Files.newInputStream(file).use { storage.setInputStream(StringObjectPath(key), it, receipt.size) }
                        val storedHash = withContext(Dispatchers.IO) {
                            storage.getInputStream(StringObjectPath(key)).use { input ->
                                val digest = MessageDigest.getInstance("SHA-256")
                                var size = 0L
                                val buffer = ByteArray(1024 * 1024)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    size += count
                                    check(size <= receipt.size) { "Stored archive size mismatch" }
                                    digest.update(buffer, 0, count)
                                }
                                check(size == receipt.size) { "Stored archive size mismatch" }
                                digest.digest().joinToString("") { "%02x".format(it) }
                            }
                        }
                        check(storedHash == receipt.sha256) { "Stored archive checksum mismatch" }
                    }
                    val hasNativeTurns = repository.hasNativeTurns(session.sessionId)
                    changeCurrent(session) { copy(archiveKey = key, archiveSha256 = receipt.sha256, archiveSize = receipt.size, archiveCreated = OffsetDateTime.now(), archiveFailure = null, restoreWorkspaceOnly = false,
                        nativeSessionId = if (hasNativeTurns) nativeSessionId else null) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                changeCurrent(session) { copy(archiveFailure = if (failure is KitArchiveQuotaException) failure.message
                    else "Archiving failed. Saved work and the previous checkpoint have been retained; retry after storage is available.") }
                telemetry.record(CHECKPOINT, "failure", session, failure = failure)
                throw failure
            }
            telemetry.record(CHECKPOINT, "success", committed, bytes = committed.archiveSize)
        }
        // Neither credential retirement nor Job deletion precedes the durable archive pointer.
        retireCredential(committed)
        provider.stop(configuration(committed), requireNotNull(committed.dispatchId))
        if (!provider.isStopped(configuration(committed), requireNotNull(committed.dispatchId))) throw DelayException(5.seconds)
        val stopped = changeCurrent(committed) { copy(state = if (failure == null) "STOPPED" else "FAILED", dispatchId = null, endpoint = null, secrets = null, credentialId = null) }
        telemetry.record(CLEANUP, "success", committed, durationMillis = elapsedSince(committed.archiveCreated))
        maintainArchives(stopped)
        stopped.failure?.let { reason ->
            history.setProcessing(session.sessionId, false)
            history.dispatchStatus(session.sessionId, ChatSessionStatus.FAILED)
            error(reason)
        }
        requestFollowUp(session.sessionId)
    }

    private suspend fun reserveArchive(session: KitRuntimeSession, key: String, bytes: Long) = transaction {
        check(repository.lockAdmission())
        val current = owned(session.sessionId, requireNotNull(session.owner), session.epoch, true)
        check(current.state == "STOPPING" && current.generation == session.generation)
        val existing = repository.archive(key)
        if (existing != null) {
            check(existing.sessionId == session.sessionId && existing.generation == session.generation && existing.bytes == bytes) { "Archive reservation differs from immutable receipt" }
            return@transaction
        }
        val limit = policy.deployment().maxArchiveBytesPerPrincipal
        val used = repository.archiveBytes(session.principalId)
        if (used > limit || bytes > limit - used) throw KitArchiveQuotaException()
        check(repository.reserveArchive(KitRuntimeArchive(key, session.sessionId, session.generation, bytes)) == 1)
    }

    /** Expiration and object collection use the same fenced owner as startup and archival. */
    private suspend fun maintainArchives(initial: KitRuntimeSession): KitRuntimeSession {
        var session = initial
        val config = json.decodeFromJsonElement(KitHarnessConfiguration.serializer(), session.configuration)
        val age = config.maxArchiveAgeSeconds
        val created = session.archiveCreated
        if (session.dispatchId == null && session.archiveKey != null && age != null &&
            (created == null || created.plusSeconds(age).isBefore(OffsetDateTime.now()))) {
            session = transaction {
                val current = owned(session.sessionId, requireNotNull(session.owner), session.epoch, true)
                sessions.recordResponse(current.sessionId, KitResponse.Text(
                    (if (created == null) "Saved harness state has no creation time for the current retention policy. "
                    else "Saved harness state expired under its retention policy. ") +
                    "Kit will start a fresh session with permitted conversation history and memories; the saved workspace is no longer available."))
                change(current) { copy(archiveKey = null, archiveSha256 = null, archiveSize = null, archiveCreated = null,
                    nativeSessionId = null, restoreWorkspaceOnly = false, needsContext = true) }
            }
        }
        try {
            for (archive in repository.obsoleteArchives(session.sessionId)) {
                owned(session.sessionId, requireNotNull(session.owner), session.epoch)
                storage.delete(StringObjectPath(archive.key))
                transaction {
                    owned(session.sessionId, requireNotNull(session.owner), session.epoch, true)
                    check(repository.deleteArchive(session.sessionId, archive.key) == 1)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            changeCurrent(session) { copy(archiveFailure = "Saved harness cleanup failed. Retry after storage is available.") }
            throw failure
        }
        // A stopping generation can still have an archive failure awaiting a successful upload.
        if (session.archiveFailure != null && session.state != "STOPPING") session = changeCurrent(session) { copy(archiveFailure = null) }
        return session
    }

    private suspend fun environment(session: KitRuntimeSession): Map<String, String> {
        return runtimeEnvironment(session, encryption, json)
    }

    private suspend fun owned(id: UUID, owner: UUID, epoch: Long, lock: Boolean = false): KitRuntimeSession {
        val session = requireNotNull(if (lock) repository.lock(id) else repository.get(id))
        check(session.deletedAt == null && session.owner == owner && session.epoch == epoch && session.leaseUntil?.isAfter(OffsetDateTime.now()) == true) { "Stale runtime owner" }
        return session
    }

    private suspend fun changeCurrent(session: KitRuntimeSession, update: KitRuntimeSession.() -> KitRuntimeSession) = transaction {
        change(owned(session.sessionId, requireNotNull(session.owner), session.epoch, true), update)
    }

    private suspend fun change(session: KitRuntimeSession, update: KitRuntimeSession.() -> KitRuntimeSession): KitRuntimeSession {
        val updated = session.update()
        check(repository.save(updated) == 1) { "Runtime version changed" }
        return updated.copy(version = updated.version + 1)
    }

    private suspend fun <T> withSession(session: KitRuntimeSession, token: String, turn: KitRuntimeTurn?,
                                       block: suspend (KitAcpConnection, AcpSessionSelection) -> T): T = coroutineScope {
        val uri = URI.create(requireNotNull(session.endpoint).replaceFirst("http", "ws").trimEnd('/') + "/acp")
        val config = json.decodeFromJsonElement(KitHarnessConfiguration.serializer(), session.configuration)
        val transport = AcpWebSocketTransportFactory(http, uri, session.generation, session.epoch, requireNotNull(session.runtimeInstanceId)) { token }
        var connection: KitAcpConnection? = null
        try {
            val selection = phase("readiness", config.readinessSeconds) {
                val connected = KitAcpConnection.connect(this@coroutineScope,
                    AcpTransportFactory { scope -> phase("connection", config.connectionSeconds) { transport.connect(scope) } },
                    authenticationMethod = config.authenticationMethod, initializeTimeoutMillis = config.readinessSeconds * 1000)
                connection = connected
                val previous = if (turn == null && session.restoreWorkspaceOnly) null else session.nativeSessionId
                val selected = connected.openSession(parameters(session, token, connected), operations(session, turn), previous,
                    timeoutMillis = config.readinessSeconds * 1000)
                check(selected.continuity != AcpSessionContinuity.RECONSTRUCTED ||
                    turn == null && (session.archiveKey == null || session.restoreWorkspaceOnly)) { "Native session continuity was lost" }
                configureModels(session, selected.session)
                selected
            }
            block(requireNotNull(connection), selection)
        } finally { connection?.close() }
    }

    private suspend fun <T : Any> phase(name: String, seconds: Long, action: suspend () -> T): T =
        withTimeoutOrNull(seconds.seconds) { action() } ?: error("Harness $name deadline exceeded")

    private suspend fun parameters(session: KitRuntimeSession, token: String, connection: KitAcpConnection): SessionCreationParameters {
        val resolution = json.decodeFromJsonElement(KitHarnessResolution.serializer(), requireNotNull(session.resolvedSettings))
        val notices = resolution.mcpNotices.toMutableList()
        val supported = resolution.mcpConnections.filter { integration ->
            val supported = when (integration.transport) {
                bosca.ai.agents.model.McpTransportType.STDIO -> true
                bosca.ai.agents.model.McpTransportType.SSE -> connection.agentInfo.capabilities.mcpCapabilities.sse
                bosca.ai.agents.model.McpTransportType.STREAMABLE_HTTP -> connection.agentInfo.capabilities.mcpCapabilities.http
            }
            if (!supported) {
                val reason = "MCP ${integration.key} uses a transport this harness does not support. Select a compatible harness or update the registration."
                if (integration.required) throw KitMcpConfigurationException(reason)
                notices.add(reason)
            }
            supported
        }
        val statuses = runtime.configureMcp(session, token, supported)
        check(statuses.map { it.key }.toSet() == supported.map { it.key }.toSet() && statuses.size == supported.size) { "Runtime returned invalid MCP readiness" }
        val available = statuses.filter { it.ready }.map { it.key }.toSet()
        statuses.forEach { telemetry.record(MCP, if (it.ready) "success" else "unavailable", session, integration = it.key) }
        supported.filter { it.key !in available }.forEach { integration ->
            val reason = "MCP ${integration.key} is unavailable. Review its mounted credentials, packaged executable, and endpoint, then restart the session."
            if (integration.required) throw KitMcpConfigurationException(reason)
            notices.add(reason)
        }
        changeCurrent(session) {
            val current = json.decodeFromJsonElement(KitHarnessResolution.serializer(), requireNotNull(resolvedSettings))
            copy(resolvedSettings = json.encodeToJsonElement(KitHarnessResolution.serializer(), current.copy(mcpNotices = notices.distinct())))
        }
        return SessionCreationParameters("/workspace", buildList {
            add(McpServer.Stdio("bosca", "/usr/local/bin/bosca", listOf("harness", "mcp", "bosca"), emptyList()))
            supported.filter { it.key in available }.forEach { integration ->
                add(when (integration.transport) {
                    bosca.ai.agents.model.McpTransportType.STDIO -> McpServer.Stdio(integration.key, "/usr/local/bin/bosca", listOf("harness", "mcp", integration.key), emptyList())
                    bosca.ai.agents.model.McpTransportType.SSE -> McpServer.Sse(name = integration.key, url = requireNotNull(integration.url), headers = emptyList())
                    bosca.ai.agents.model.McpTransportType.STREAMABLE_HTTP -> McpServer.Http(name = integration.key, url = requireNotNull(integration.url), headers = emptyList())
                })
            }
        })
    }

    private fun transcript(session: KitRuntimeSession, turn: KitRuntimeTurn, receipt: RuntimeTurnReceipt) = KitRuntimeTranscript(session, turn, json).also { transcript ->
        receipt.messages.filter { it["method"]?.jsonPrimitive?.content == "session/update" }.forEach {
            transcript.accept(json.decodeFromJsonElement(SessionUpdate.serializer(), it.getValue("params").jsonObject.getValue("update")))
        }
    }

    private suspend fun persistTranscript(session: KitRuntimeSession, message: ChatHistoryMessage?) {
        if (message == null) return
        transaction {
            owned(session.sessionId, requireNotNull(session.owner), session.epoch, true)
            history.upsertMessage(message)
        }
    }

    private suspend fun terminalMessage(session: KitRuntimeSession, turn: KitRuntimeTurn, status: String): ChatHistoryMessage {
        val previous = history.getMessages(session.sessionId).firstOrNull { it.id == turn.id }
        if (previous == null) return requireNotNull(KitRuntimeTranscript(session, turn, json).snapshot(status, true))
        val event = json.decodeFromJsonElement(KitHarnessTurnEvent.serializer(), previous.event)
        return previous.copy(event = json.encodeToJsonElement(KitHarnessTurnEvent.serializer(), event.copy(
            epoch = session.epoch, revision = event.revision + 1, status = status)))
    }

    private suspend fun hash(file: java.nio.file.Path): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun <T> withArchiveFile(block: suspend (java.nio.file.Path) -> T): T {
        val file = Files.createTempFile("kit-runtime-", ".tar.gz")
        try { return block(file) } finally { Files.deleteIfExists(file) }
    }

    private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, limit: Long) {
        val buffer = ByteArray(1024 * 1024)
        var size = 0L
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Archive copy interrupted")
            val count = input.read(buffer)
            if (count < 0) break
            size += count
            check(size <= limit)
            output.write(buffer, 0, count)
        }
    }
}

internal fun KitRuntimeSession.freshGeneration() = copy(state = "STARTING", generation = generation + 1,
    dispatchId = null, endpoint = null, runtimeInstanceId = null, secrets = null, credentialId = null, cancelRequestedAt = null, failure = null, archiveFailure = null)

private fun randomToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
