package bosca.workops.service

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.portal.PortalAuthMode
import bosca.workops.model.portal.PortalToken
import bosca.workops.model.portal.RequestType
import bosca.workops.model.task.CreateTaskInput
import bosca.workops.repository.PortalInsertParams
import bosca.workops.repository.PortalRepository
import bosca.workops.repository.PortalTokenRepository
import bosca.workops.repository.PortalUserRepository
import bosca.workops.repository.RequestTypeRepository
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.uuid.ExperimentalUuidApi

@ServiceImplementation
class PortalServiceImpl(
    private val repository: PortalRepository,
    private val requestTypeRepository: RequestTypeRepository,
) : PortalService {

    override suspend fun list() = repository.listAll()
    override suspend fun listForProject(projectId: UUID) = repository.listForProject(projectId)
    override suspend fun getById(id: UUID) = repository.getById(id)
    override suspend fun getBySlug(slug: String) = repository.getBySlug(slug)

    override suspend fun create(input: CreatePortalInput) = repository.add(
        PortalInsertParams(
            slug = input.slug,
            name = input.name,
            description = input.description,
            projectId = input.projectId,
            themeColorHex = input.themeColorHex,
            welcomeMarkdown = input.welcomeMarkdown,
            supportEmail = input.supportEmail,
            authMode = input.authMode.name,
            anonymousAllowlistDomains = input.anonymousAllowlistDomains,
            slaPolicyId = input.slaPolicyId,
            enabled = input.enabled,
        )
    )

    override suspend fun listRequestTypes(portalId: UUID) =
        requestTypeRepository.listForPortal(portalId)

    override suspend fun addRequestType(
        portalId: UUID, name: String, description: String?,
        taskTypeId: UUID, defaultPriorityId: UUID?, displayOrder: Int,
        formSchemaKey: String?,
    ): RequestType = requestTypeRepository.add(
        portalId, name, description, taskTypeId, defaultPriorityId, displayOrder, formSchemaKey,
    )
}

@ServiceImplementation
class PortalSubmissionServiceImpl(
    private val portalRepository: PortalRepository,
    private val requestTypeRepository: RequestTypeRepository,
    private val portalUserRepository: PortalUserRepository,
    private val portalTokenRepository: PortalTokenRepository,
    private val taskService: TaskService,
) : PortalSubmissionService {

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun submit(portalId: UUID, input: PortalSubmissionInput): PortalSubmissionResult {
        val portal = portalRepository.getById(portalId)
            ?: throw WorkOpsNotFoundException("Portal", portalId.toString())
        if (!portal.enabled) {
            throw WorkOpsValidationException("portal", "portal is disabled")
        }
        val requestType = requestTypeRepository.getById(input.requestTypeId)
            ?: throw WorkOpsNotFoundException("RequestType", input.requestTypeId.toString())
        if (requestType.portalId != portalId) {
            throw WorkOpsValidationException(
                "requestType", "requestType ${requestType.id} does not belong to portal ${portalId}",
            )
        }
        // Auth mode gate.
        val isAnonymous = input.reporterProfileId == null
        when (portal.authMode) {
            PortalAuthMode.AUTHENTICATED_ONLY ->
                if (isAnonymous) throw WorkOpsValidationException(
                    "auth", "this portal requires an authenticated reporter",
                )
            PortalAuthMode.ALLOW_ANONYMOUS_VIA_EMAIL,
            PortalAuthMode.MIXED -> {
                if (isAnonymous && portal.anonymousAllowlistDomains.isNotEmpty()) {
                    val domain = input.reporterEmail.substringAfter('@', "").lowercase()
                    val allowed = portal.anonymousAllowlistDomains
                        .map { it.lowercase() }
                        .any { it == domain || domain.endsWith(".$it") }
                    if (!allowed) {
                        throw WorkOpsValidationException(
                            "reporterEmail",
                            "domain not in portal allow-list",
                        )
                    }
                }
            }
        }
        // Upsert portal_user.
        val portalUser = portalUserRepository.upsert(
            portalId = portalId,
            email = input.reporterEmail,
            profileId = input.reporterProfileId,
            displayName = input.reporterDisplayName,
        )
        val reporterProfileId = input.reporterProfileId
            ?: portalUser.profileId
            // Anonymous reporters get the portal user id used as the
            // reporter — Phase 14 doesn't auto-create a profile.
            ?: portalUser.id
        val task = taskService.create(
            input = CreateTaskInput(
                projectId = portal.projectId,
                summary = input.summary,
                descriptionMarkdown = input.description,
                priorityId = requestType.defaultPriorityId,
                taskTypeId = requestType.taskTypeId,
            ),
            actingPrincipalId = UUID.NIL,
            actingProfileId = reporterProfileId,
            reporterProfileId = reporterProfileId,
        )
        val (token, hash, expires) = if (isAnonymous) {
            val plain = newToken()
            Triple(plain, sha256Hex(plain), OffsetDateTime.now().plusDays(30))
        } else {
            Triple(null, null, null)
        }
        if (hash != null && expires != null) {
            portalTokenRepository.add(
                portalUserId = portalUser.id,
                taskId = task.id,
                tokenHash = hash,
                expiresAt = expires,
            )
        }
        return PortalSubmissionResult(
            task = task,
            portalUser = portalUser,
            magicLinkToken = token,
        )
    }

    private fun newToken(): String {
        val rand = SecureRandom()
        val buf = ByteArray(32)
        rand.nextBytes(buf)
        return buf.joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

@ServiceImplementation
class PortalTokenServiceImpl(
    private val repository: PortalTokenRepository,
) : PortalTokenService {
    override suspend fun verify(plainText: String): PortalToken? {
        val hash = sha256Hex(plainText)
        return repository.getByHash(hash)
    }

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
