package bosca.workops.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.portal.Portal
import bosca.workops.model.portal.PortalToken
import bosca.workops.model.portal.PortalUser
import bosca.workops.model.portal.RequestType

@Repository
interface PortalRepository {

    @Query("select * from workops.portal where id = :id")
    suspend fun getById(id: UUID): Portal?

    @Query("select * from workops.portal where slug = :slug and enabled = true")
    suspend fun getBySlug(slug: String): Portal?

    @Query("select * from workops.portal where project_id = :projectId order by name")
    suspend fun listForProject(projectId: UUID): List<Portal>

    @Query("select * from workops.portal order by name")
    suspend fun listAll(): List<Portal>

    @Query(
        """
        insert into workops.portal
            (slug, name, description, project_id, theme_color_hex,
             welcome_markdown, support_email, auth_mode,
             anonymous_allowlist_domains, sla_policy_id, enabled)
        values
            (:slug, :name, :description, :projectId, :themeColorHex,
             :welcomeMarkdown, :supportEmail, :authMode,
             :anonymousAllowlistDomains, :slaPolicyId, :enabled)
        returning *
        """
    )
    suspend fun add(input: PortalInsertParams): Portal
}

data class PortalInsertParams(
    val slug: String,
    val name: String,
    val description: String?,
    val projectId: UUID,
    val themeColorHex: String,
    val welcomeMarkdown: String?,
    val supportEmail: String,
    val authMode: String,
    val anonymousAllowlistDomains: List<String>,
    val slaPolicyId: UUID?,
    val enabled: Boolean,
)

@Repository
interface RequestTypeRepository {

    @Query("select * from workops.portal_request_type where id = :id")
    suspend fun getById(id: UUID): RequestType?

    @Query("select * from workops.portal_request_type where portal_id = :portalId order by display_order, name")
    suspend fun listForPortal(portalId: UUID): List<RequestType>

    @Query(
        """
        insert into workops.portal_request_type
            (portal_id, name, description, task_type_id, default_priority_id, display_order, form_schema_key)
        values
            (:portalId, :name, :description, :taskTypeId, :defaultPriorityId, :displayOrder, :formSchemaKey)
        returning *
        """
    )
    suspend fun add(
        portalId: UUID,
        name: String,
        description: String?,
        taskTypeId: UUID,
        defaultPriorityId: UUID?,
        displayOrder: Int,
        formSchemaKey: String?,
    ): RequestType
}

@Repository
interface PortalUserRepository {

    @Query("select * from workops.portal_user where id = :id")
    suspend fun getById(id: UUID): PortalUser?

    @Query("select * from workops.portal_user where portal_id = :portalId and email = :email")
    suspend fun getByEmail(portalId: UUID, email: String): PortalUser?

    @Query(
        """
        insert into workops.portal_user (portal_id, email, profile_id, display_name)
        values (:portalId, :email, :profileId, :displayName)
        on conflict (portal_id, email) do update set
            profile_id = coalesce(excluded.profile_id, workops.portal_user.profile_id),
            display_name = coalesce(excluded.display_name, workops.portal_user.display_name)
        returning *
        """
    )
    suspend fun upsert(
        portalId: UUID,
        email: String,
        profileId: UUID?,
        displayName: String?,
    ): PortalUser
}

@Repository
interface PortalTokenRepository {

    @Query(
        """
        select * from workops.portal_token
        where token_hash = :tokenHash
              and revoked_at is null
              and expires_at > now()
        """
    )
    suspend fun getByHash(tokenHash: String): PortalToken?

    @Query(
        """
        insert into workops.portal_token (portal_user_id, task_id, token_hash, expires_at)
        values (:portalUserId, :taskId, :tokenHash, :expiresAt)
        returning *
        """
    )
    suspend fun add(
        portalUserId: UUID,
        taskId: UUID?,
        tokenHash: String,
        expiresAt: OffsetDateTime,
    ): PortalToken

    @Query(
        """
        update workops.portal_token set revoked_at = now()
        where id = :id and revoked_at is null
        """
    )
    suspend fun revoke(id: UUID)
}
