package bosca.git.service

import bosca.git.model.GitHubUser
import bosca.lock.DistributedLockFactory
import bosca.db.withConnectionManager
import bosca.git.github.GitHubClient
import bosca.git.model.GitHubPush
import bosca.git.model.GitHubRefState
import bosca.git.model.GitHubSyncResult
import bosca.git.model.RefUpdateEvent
import bosca.git.model.GitHubDelivery
import bosca.git.model.dispatch
import bosca.git.model.GitHubDeliveryConflictException
import bosca.git.model.GitHubRepositoryPair
import bosca.git.model.GitHubRepositoryPairInput
import bosca.git.model.GitHubWebhookPayload
import bosca.git.model.GitHubWebhookRejectedException
import bosca.git.model.GitHubWebhookInputException
import bosca.git.model.GitHubWebhookUnavailableException
import bosca.git.repository.GitHubSyncRepository
import bosca.pipelines.service.PipelineSecretService
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.slf4j.LoggerFactory

@ServiceImplementation
class GitHubSyncServiceImpl(
    private val repository: GitHubSyncRepository,
    private val repositoryService: RepositoryService,
    private val secrets: PipelineSecretService,
    private val securityService: SecurityService,
    private val writes: RepositoryWriteService,
    private val github: GitHubClient,
    private val locks: DistributedLockFactory,
) : GitHubSyncService {
    private val log = LoggerFactory.getLogger(GitHubSyncServiceImpl::class.java)

    override suspend fun findPair(repositoryId: UUID): GitHubRepositoryPair? = repository.findPair(repositoryId)

    override suspend fun savePair(input: GitHubRepositoryPairInput): GitHubRepositoryPair {
        require(input.githubRepositoryId > 0 && input.version >= 0) { "Invalid GitHub repository ID or version" }
        require(input.owner.matches(SEGMENT) && input.name.matches(SEGMENT)) { "Invalid GitHub owner or repository name" }
        require(input.webhookSecretName.isNotBlank() && input.tokenSecretName.isNotBlank()) { "Secret names are required" }
        val hosted = repositoryService.findById(input.repositoryId)
            ?: throw NoSuchElementException("Repository not found: ${input.repositoryId}")
        require(!input.enabled || (!hosted.deleted && !hosted.archived)) { "Repository is unavailable for synchronization" }
        if (input.enabled) {
            require(!secrets.resolve(input.webhookSecretName).isNullOrBlank()) { "Webhook secret is not configured" }
            require(!secrets.resolve(input.tokenSecretName).isNullOrBlank()) { "GitHub token is not configured" }
        }
        val pair = GitHubRepositoryPair(
            repositoryId = input.repositoryId, githubRepositoryId = input.githubRepositoryId,
            owner = input.owner, name = input.name, webhookSecretName = input.webhookSecretName,
            tokenSecretName = input.tokenSecretName, enabled = input.enabled, version = input.version,
        )
        val current = repository.findPair(input.repositoryId)
        if (current == null) {
            require(input.version == 0L) { "New pair must have version zero" }
            return repository.createPair(pair)
        }
        require(current.githubRepositoryId == input.githubRepositoryId) { "A repository pair cannot change GitHub repository ID" }
        return repository.updatePair(pair) ?: error("Repository pair changed concurrently")
    }

    override suspend fun findUsers(offset: Long, limit: Int): List<GitHubUser> {
        validatePage(offset, limit)
        return repository.findUsers(offset, limit)
    }

    override suspend fun mapUser(githubUserId: Long, principalId: UUID): GitHubUser {
        require(githubUserId > 0) { "Invalid GitHub user ID" }
        val principal = securityService.getPrincipalById(principalId)
            ?: throw NoSuchElementException("Principal not found: $principalId")
        require(principal.deletedAt == null) { "Principal is deleted" }
        return repository.mapUser(githubUserId, principalId)
    }

    override suspend fun unmapUser(githubUserId: Long) {
        require(githubUserId > 0) { "Invalid GitHub user ID" }
        repository.unmapUser(githubUserId)
    }

    override suspend fun onDelivery(
        repositoryId: UUID, deliveryId: String, event: String, signature: String?, body: ByteArray,
    ): GitHubDelivery {
        // Delivery headers are not covered by the HMAC. Bind them to the persisted occurrence too.
        if (!deliveryId.matches(DELIVERY_ID) || !event.matches(EVENT_NAME)) {
            throw GitHubWebhookInputException("Invalid GitHub delivery headers")
        }
        val pair = repository.findPair(repositoryId)?.takeIf { it.enabled }
            ?: throw GitHubWebhookRejectedException()
        val hosted = repositoryService.findById(repositoryId)
        if (hosted == null || hosted.deleted || hosted.archived) throw GitHubWebhookRejectedException()
        val secret = secrets.resolve(pair.webhookSecretName)?.takeIf { it.isNotBlank() }
            ?: throw GitHubWebhookUnavailableException()
        if (!verifySignature(secret, signature, body)) throw GitHubWebhookRejectedException()
        val text = try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw GitHubWebhookInputException("GitHub payload is not UTF-8")
        }
        val (payload, envelope) = try {
            val payload = json.parseToJsonElement(text)
            payload to json.decodeFromJsonElement(GitHubWebhookPayload.serializer(), payload)
        } catch (e: SerializationException) {
            throw GitHubWebhookInputException("Invalid GitHub payload", e)
        }
        if (envelope.repository.id != pair.githubRepositoryId) throw GitHubWebhookRejectedException()
        val user = envelope.sender?.takeIf { it.id > 0 && it.type == "User" }
        val fork = event.startsWith("pull_request") &&
            envelope.pullRequest?.head?.repo?.id != pair.githubRepositoryId
        val delivery = GitHubDelivery(
            deliveryId = deliveryId.lowercase(), repositoryId = repositoryId, event = event,
            payload = payload, payloadDigest = hex(MessageDigest.getInstance("SHA-256").digest(body)),
            githubUserId = envelope.sender?.id,
            principalId = user?.let { repository.findUser(it.id)?.principalId },
            ignored = event !in SUPPORTED_EVENTS || fork,
        )
        val accepted = repository.createDelivery(delivery)
            ?: repository.findDelivery(delivery.deliveryId) ?: error("Conflicting delivery was not found")
        if (accepted.repositoryId != repositoryId || accepted.event != event || accepted.payloadDigest != delivery.payloadDigest) {
            throw GitHubDeliveryConflictException()
        }
        if (!accepted.ignored) accepted.dispatch()
        return accepted
    }

    override suspend fun findDeliveries(repositoryId: UUID, offset: Long, limit: Int): List<GitHubDelivery> {
        validatePage(offset, limit)
        return repository.findDeliveries(repositoryId, offset, limit)
    }

    override suspend fun synchronizePush(delivery: GitHubDelivery): GitHubSyncResult = withPair(delivery.repositoryId, GitHubSyncResult.IGNORED) { pair ->
        // Use persisted signed input, including its original user mapping, rather than caller-supplied attribution.
        val verified = repository.findDelivery(delivery.deliveryId)
            ?: throw NoSuchElementException("Verified GitHub delivery not found")
        require(verified.repositoryId == pair.repositoryId) { "Delivery belongs to another repository" }
        if (verified.ignored || verified.event != "push") return@withPair GitHubSyncResult.IGNORED
        repository.findPushResult(verified.deliveryId)?.let { return@withPair GitHubSyncResult.valueOf(it) }
        val push = json.decodeFromJsonElement(GitHubPush.serializer(), verified.payload)
        val token = token(pair)
        val url = github.repositoryUrl(pair, token)
        val result = synchronize(pair, push.ref, push.before.nonZeroSha(), push.after.nonZeroSha(),
            RefSynchronizationDirection.INBOUND, verified.principalId, url, token, verifiedPush = true)
        repository.savePushResult(verified.deliveryId, result.name)
        result
    }

    override suspend fun synchronizeRef(event: RefUpdateEvent): GitHubSyncResult = withPair(event.repositoryId, GitHubSyncResult.IGNORED) { pair ->
        val token = token(pair)
        val url = github.repositoryUrl(pair, token)
        synchronize(pair, event.ref, event.beforeSha, event.afterSha, RefSynchronizationDirection.OUTBOUND, null, url, token)
    }

    override suspend fun findRefStates(repositoryId: UUID, offset: Long, limit: Int): List<GitHubRefState> {
        validatePage(offset, limit)
        return repository.findRefStates(repositoryId, offset, limit)
    }

    override suspend fun reconcileRefs(repositoryId: UUID?): List<GitHubRefState> {
        if (repositoryId != null) return reconcilePair(repositoryId)
        // Each pair commits or rolls back on its own, so one failing pair cannot block the others.
        val reconciled = mutableListOf<GitHubRefState>()
        val failures = mutableListOf<Pair<UUID, Exception>>()
        for (pair in repository.findEnabledPairs()) {
            try {
                reconciled += reconcilePair(pair.repositoryId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("GitHub ref reconciliation failed for repository {}", pair.repositoryId, e)
                failures += pair.repositoryId to e
            }
        }
        if (failures.isNotEmpty()) {
            throw IllegalStateException(
                "GitHub ref reconciliation failed for ${failures.size} repositories: ${failures.joinToString { it.first.toString() }}",
                failures.first().second,
            ).apply { failures.drop(1).forEach { addSuppressed(it.second) } }
        }
        return reconciled
    }

    private suspend fun reconcilePair(repositoryId: UUID): List<GitHubRefState> = withConnectionManager {
        val pair = availablePair(repository.findPair(repositoryId)) ?: return@withConnectionManager emptyList()
        val token = token(pair)
        val url = github.repositoryUrl(pair, token)
        val current = writes.compareRefs(repositoryId, url, token).associateBy { it.ref }
        val baselines = repository.findAllRefStates(repositoryId).associateBy { it.ref }
        val reconciled = mutableListOf<GitHubRefState>()
        val pendingRefs = repository.findPendingPushRefs(repositoryId)
        for (ref in (current.keys + baselines.keys + pendingRefs).sorted()) {
            reconciled += withPair(repositoryId, emptyList<GitHubRefState>()) { lockedPair ->
                check(lockedPair.version == pair.version) { "GitHub repository pair changed during reconciliation" }
                val pending = repository.findPendingPushDeliveries(repositoryId, ref)
                if (pending.isNotEmpty()) {
                    // The normal import pipeline owns these verified occurrences and their attribution.
                    pending.forEach { it.dispatch() }
                    return@withPair listOfNotNull(repository.findRefState(repositoryId, ref))
                }
                val local = current[ref]?.localSha
                val remote = current[ref]?.remoteSha
                val baseline = repository.findRefState(repositoryId, ref)
                // Both sides still hold the recorded common value: nothing to transfer or record.
                if (baseline != null && baseline.synchronized && !baseline.conflict && local == baseline.sha && remote == baseline.sha) {
                    return@withPair listOf(baseline)
                }
                val outbound = if (baseline?.synchronized == true) remote == baseline.sha && local != remote
                    else remote == null && local != null
                val direction = if (outbound) RefSynchronizationDirection.OUTBOUND else RefSynchronizationDirection.INBOUND
                var result = synchronize(lockedPair, ref, baseline?.sha, if (outbound) local else remote, direction, null, url, token)
                // Either history may contain the other when both tips changed since the last common ref.
                if (result == GitHubSyncResult.CONFLICT && local != null && remote != null && ref.startsWith("refs/heads/")) {
                    result = synchronize(lockedPair, ref, baseline?.sha, local, RefSynchronizationDirection.OUTBOUND, null, url, token)
                }
                if (result == GitHubSyncResult.STALE) emptyList() else listOfNotNull(repository.findRefState(repositoryId, ref))
            }
        }
        reconciled
    }

    /** Rechecks the pair under the write lock and commits each operation before that lock is released. */
    private suspend fun <T : Any> withPair(
        repositoryId: UUID, unavailable: T, block: suspend (GitHubRepositoryPair) -> T,
    ): T = withConnectionManager {
        if (availablePair(repository.findPair(repositoryId)) == null) return@withConnectionManager unavailable
        withRefSynchronizationTransaction(repositoryId, locks) {
            val pair = availablePair(repository.lockPair(repositoryId)) ?: return@withRefSynchronizationTransaction unavailable
            block(pair)
        }
    }

    private suspend fun availablePair(candidate: GitHubRepositoryPair?): GitHubRepositoryPair? {
        val pair = candidate?.takeIf { it.enabled } ?: return null
        val hosted = repositoryService.findById(pair.repositoryId)?.takeUnless { it.deleted || it.archived } ?: return null
        return pair.takeIf { it.repositoryId == hosted.id }
    }

    private suspend fun synchronize(
        pair: GitHubRepositoryPair, ref: String, before: String?, after: String?,
        direction: RefSynchronizationDirection, principalId: UUID?, remoteUrl: String, token: String,
        verifiedPush: Boolean = false,
    ): GitHubSyncResult {
        val state = repository.findRefState(pair.repositoryId, ref)
        val unattributedBefore = if (verifiedPush && principalId != null && after != null) {
            repository.findUnattributedBeforeSha(pair.repositoryId, ref, after)
        } else null
        val outcome = writes.synchronizeRef(RefSynchronizationInput(
            repositoryId = pair.repositoryId, remoteUrl = remoteUrl,
            token = token, ref = ref, beforeSha = before, afterSha = after, synchronizedSha = state?.sha,
            hasSynchronized = state?.synchronized == true, direction = direction, principalId = principalId,
            hasConflict = state?.conflict == true,
            unattributedBeforeSha = unattributedBefore,
        ))
        val converged = outcome.result == GitHubSyncResult.APPLIED || outcome.result == GitHubSyncResult.UNCHANGED
        val anonymousImport = outcome.result == GitHubSyncResult.APPLIED &&
            direction == RefSynchronizationDirection.INBOUND && !verifiedPush && outcome.boscaSha != null
        val retainAttribution = outcome.result != GitHubSyncResult.APPLIED &&
            !(verifiedPush && outcome.result == GitHubSyncResult.UNCHANGED) && state?.sha == outcome.boscaSha
        repository.saveRefState(GitHubRefState(
            repositoryId = pair.repositoryId, ref = ref,
            sha = if (converged) outcome.boscaSha else state?.sha,
            synchronized = converged || state?.synchronized == true,
            boscaSha = outcome.boscaSha, githubSha = outcome.remoteSha,
            conflict = if (converged) false else outcome.result == GitHubSyncResult.CONFLICT || state?.conflict == true,
            unattributedBeforeSha = when {
                anonymousImport -> outcome.beforeSha ?: "0000000000000000000000000000000000000000"
                retainAttribution -> state?.unattributedBeforeSha
                else -> null
            },
            unattributedRefModified = if (retainAttribution) state?.unattributedRefModified else null,
        ))
        return outcome.result
    }

    private suspend fun token(pair: GitHubRepositoryPair): String =
        secrets.resolve(pair.tokenSecretName)?.takeIf { it.isNotBlank() } ?: throw GitHubWebhookUnavailableException()

    private fun String.nonZeroSha(): String? = takeUnless { it == "0000000000000000000000000000000000000000" }

    private fun validatePage(offset: Long, limit: Int) {
        require(offset >= 0 && limit in 1..100) { "Invalid pagination" }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val SEGMENT = Regex("[A-Za-z0-9_.-]+")
        private val DELIVERY_ID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val EVENT_NAME = Regex("[a-z_]+")
        private val SIGNATURE = Regex("sha256=[0-9a-fA-F]{64}")
        private val SUPPORTED_EVENTS = setOf("push", "pull_request")

        internal fun verifySignature(secret: String, signature: String?, body: ByteArray): Boolean {
            if (signature == null || !signature.matches(SIGNATURE)) return false
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
            val expected = mac.doFinal(body)
            val received = signature.removePrefix("sha256=").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            return MessageDigest.isEqual(expected, received)
        }

        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }
}
