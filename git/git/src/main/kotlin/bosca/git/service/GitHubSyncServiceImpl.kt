package bosca.git.service

import bosca.git.model.GitHubUser
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@ServiceImplementation
class GitHubSyncServiceImpl(
    private val repository: GitHubSyncRepository,
    private val repositoryService: RepositoryService,
    private val secrets: PipelineSecretService,
    private val securityService: SecurityService,
) : GitHubSyncService {
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
