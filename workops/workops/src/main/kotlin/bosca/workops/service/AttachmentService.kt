package bosca.workops.service

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.attachment.Attachment
import bosca.workops.model.attachment.PresignedUpload
import bosca.workops.repository.AttachmentInsertParams
import bosca.workops.repository.AttachmentRepository
import bosca.workops.repository.ProjectAttachmentLimitsRepository
import bosca.workops.repository.TaskRepository
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.uuid.ExperimentalUuidApi

/**
 * Built-in deny list per R16. Project-level deny entries
 * concatenate to this set.
 */
private val DEFAULT_DENYLIST = setOf(
    "application/x-msdownload",
    "application/x-msdos-program",
    "application/x-sh",
    "application/x-bat",
)

@ServiceImplementation
class AttachmentServiceImpl(
    private val repository: AttachmentRepository,
    private val taskRepository: TaskRepository,
    private val limitsRepository: ProjectAttachmentLimitsRepository,
    private val signingSecret: AttachmentSigningSecret,
) : AttachmentService {

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun requestUpload(
        taskId: UUID,
        profileId: UUID,
        filename: String,
        contentType: String,
        sizeBytes: Long,
    ): PresignedUpload {
        val task = taskRepository.getActiveById(taskId)
            ?: throw WorkOpsNotFoundException("Task", taskId.toString())
        val limits = limitsRepository.get(task.projectId)
            ?: throw WorkOpsNotFoundException("Project", task.projectId.toString())
        if (sizeBytes <= 0) {
            throw WorkOpsValidationException("sizeBytes", "must be positive")
        }
        if (sizeBytes > limits.maxAttachmentBytes) {
            throw WorkOpsValidationException(
                "sizeBytes",
                "$sizeBytes exceeds per-attachment limit ${limits.maxAttachmentBytes}",
            )
        }
        val deny = DEFAULT_DENYLIST + limits.denylist.toSet()
        if (contentType in deny) {
            throw WorkOpsValidationException("contentType", "$contentType is not allowed")
        }
        val totalNow = repository.totalForTask(taskId)
        if (totalNow + sizeBytes > limits.maxTotalAttachmentBytes) {
            throw WorkOpsValidationException(
                "sizeBytes",
                "would exceed per-task aggregate ${limits.maxTotalAttachmentBytes}",
            )
        }
        val storageObjectId = UUID.random()
        val expiresAt = OffsetDateTime.now().plusMinutes(15)
        val payload = "$taskId|$profileId|$filename|$contentType|$sizeBytes|$storageObjectId|${expiresAt.toEpochSecond()}"
        val token = "${storageObjectId}:${expiresAt.toEpochSecond()}:${signingSecret.sign(payload)}"
        signingSecret.remember(
            token,
            AttachmentDescriptor(
                taskId = taskId, profileId = profileId, filename = filename,
                contentType = contentType, sizeBytes = sizeBytes,
                storageObjectId = storageObjectId, expiresAt = expiresAt,
            )
        )
        // Phase 8.4 doesn't bind core-storage's S3 presigner; the
        // upload URL is a placeholder the storage worker resolves
        // on confirmation. Phase 11 swaps to a real presign call.
        val uploadUrl = "workops://upload/${storageObjectId}?expires=${expiresAt.toEpochSecond()}"
        return PresignedUpload(
            uploadUrl = uploadUrl,
            storageObjectId = storageObjectId,
            confirmationToken = token,
            expiresAt = expiresAt,
        )
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun confirmUpload(token: String, description: String?): Attachment {
        val parts = token.split(":")
        if (parts.size != 3) throw WorkOpsValidationException("token", "malformed confirmation token")
        val (storageObjectIdRaw, expiresRaw, signature) = parts
        val storageObjectId = runCatching { UUID.parse(storageObjectIdRaw) }.getOrNull()
            ?: throw WorkOpsValidationException("token", "invalid storage object id")
        val expiresEpoch = runCatching { expiresRaw.toLong() }.getOrNull()
            ?: throw WorkOpsValidationException("token", "invalid expiry")
        if (expiresEpoch < System.currentTimeMillis() / 1000) {
            throw WorkOpsValidationException("token", "token expired")
        }
        // The descriptor that produced the token is committed to memory in
        // [requestUpload]; in this v1 flow the caller re-supplies the
        // descriptor through the GraphQL `confirmAttachmentUpload(input)`
        // surface. The token integrity check still gates the call so a
        // forged client can't write arbitrary rows.
        // For determinism we use a deterministic 'verify' that the
        // signature matches *some* valid descriptor — see signingSecret.verifyEnvelope.
        val descriptor = signingSecret.verify(token)
            ?: throw WorkOpsValidationException("token", "invalid signature")
        return repository.add(
            AttachmentInsertParams(
                taskId = descriptor.taskId,
                storageObjectId = storageObjectId,
                filename = descriptor.filename,
                contentType = descriptor.contentType,
                sizeBytes = descriptor.sizeBytes,
                uploadedByProfileId = descriptor.profileId,
                description = description,
            )
        )
    }

    override suspend fun listForTask(taskId: UUID): List<Attachment> = repository.listForTask(taskId)

    override suspend fun getById(id: UUID): Attachment? = repository.getById(id)

    override suspend fun delete(id: UUID): Boolean = repository.softDelete(id) != null
}

/**
 * Wraps the raw HMAC key bytes so the DI container can resolve it
 * as a typed dependency instead of a bare [ByteArray], which has
 * no unique class identity at runtime.
 */
class HmacSecret(val bytes: ByteArray) {
    companion object {
        /** Generates a cryptographically random 256-bit secret. */
        fun random(): HmacSecret {
            val b = ByteArray(32)
            java.security.SecureRandom().nextBytes(b)
            return HmacSecret(b)
        }
    }
}

/**
 * In-process implementation backed by a random secret per JVM.
 * Production wiring may supply a stable secret from configuration;
 * the default is suitable for single-node deployments and tests.
 */
@ServiceImplementation
class AttachmentSigningSecretImpl(secret: HmacSecret) : AttachmentSigningSecret {

    private val key = SecretKeySpec(secret.bytes, "HmacSHA256")

    override fun sign(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        val raw = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        return raw.joinToString("") { "%02x".format(it) }
    }

    private val pendingDescriptors = mutableMapOf<String, AttachmentDescriptor>()

    @Synchronized
    override fun remember(token: String, descriptor: AttachmentDescriptor) {
        pendingDescriptors[token] = descriptor
        if (pendingDescriptors.size > 4096) {
            val toRemove = pendingDescriptors.keys.take(pendingDescriptors.size - 2048)
            toRemove.forEach { pendingDescriptors.remove(it) }
        }
    }

    @Synchronized
    override fun verify(token: String): AttachmentDescriptor? = pendingDescriptors.remove(token)
}
