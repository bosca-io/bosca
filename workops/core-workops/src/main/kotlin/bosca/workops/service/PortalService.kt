package bosca.workops.service

import bosca.serialization.UUID
import bosca.service.Service
import bosca.workops.model.portal.Portal
import bosca.workops.model.portal.PortalAuthMode
import bosca.workops.model.portal.PortalToken
import bosca.workops.model.portal.PortalUser
import bosca.workops.model.portal.RequestType
import bosca.workops.model.task.Task

interface PortalService : Service {
    suspend fun list(): List<Portal>
    suspend fun listForProject(projectId: UUID): List<Portal>
    suspend fun getById(id: UUID): Portal?
    suspend fun getBySlug(slug: String): Portal?
    suspend fun create(input: CreatePortalInput): Portal
    suspend fun listRequestTypes(portalId: UUID): List<RequestType>
    suspend fun addRequestType(
        portalId: UUID, name: String, description: String?,
        taskTypeId: UUID, defaultPriorityId: UUID?, displayOrder: Int,
        formSchemaKey: String? = null,
    ): RequestType
}

data class CreatePortalInput(
    val slug: String,
    val name: String,
    val description: String?,
    val projectId: UUID,
    val themeColorHex: String,
    val welcomeMarkdown: String?,
    val supportEmail: String,
    val authMode: PortalAuthMode,
    val anonymousAllowlistDomains: List<String>,
    val slaPolicyId: UUID?,
    val enabled: Boolean,
)

/**
 * R28 — submit a portal request. Authenticated submissions
 * resolve the reporter to a profile id; anonymous submissions
 * mint a magic-link token tied to a portal_user row.
 */
data class PortalSubmissionInput(
    val requestTypeId: UUID,
    val summary: String,
    val description: String?,
    val reporterEmail: String,
    val reporterDisplayName: String?,
    /** Authenticated submissions populate this; anonymous leaves null. */
    val reporterProfileId: UUID? = null,
)

data class PortalSubmissionResult(
    val task: Task,
    val portalUser: PortalUser,
    /**
     * Plain-text magic-link token. Returned on anonymous submission;
     * null when the reporter authenticates. Surfaced in the
     * response exactly once — never persisted in plain text.
     */
    val magicLinkToken: String?,
)

interface PortalSubmissionService : Service {
    suspend fun submit(portalId: UUID, input: PortalSubmissionInput): PortalSubmissionResult
}

interface PortalTokenService : Service {
    /**
     * R28 — verifies a magic-link token. Returns the active
     * `PortalToken` row when the hash matches an unrevoked,
     * unexpired entry; null otherwise.
     */
    suspend fun verify(plainText: String): PortalToken?
}
