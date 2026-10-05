package bosca.workops.model.portal

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
enum class PortalAuthMode {
    AUTHENTICATED_ONLY,
    ALLOW_ANONYMOUS_VIA_EMAIL,
    MIXED,
}

/**
 * R28 — comment-on-task visibility split. `INTERNAL` is the
 * default for internal-created tasks; `PORTAL_VISIBLE` makes
 * the comment visible to the portal viewer.
 */
@Serializable
enum class CommentExposure {
    INTERNAL,
    PORTAL_VISIBLE,
}

/**
 * R28 — branded customer portal that submits into a backing
 * Work Ops project.
 */
@Serializable
data class Portal(
    @Contextual
    val id: UUID = UUID.NIL,
    val slug: String,
    val name: String,
    val description: String? = null,
    @ColumnName("project_id")
    @Contextual
    val projectId: UUID,
    @ColumnName("theme_color_hex")
    val themeColorHex: String = "#3b82f6",
    @ColumnName("logo_storage_object_id")
    @Contextual
    val logoStorageObjectId: UUID? = null,
    @ColumnName("welcome_markdown")
    val welcomeMarkdown: String? = null,
    @ColumnName("support_email")
    val supportEmail: String,
    @ColumnName("auth_mode")
    val authMode: PortalAuthMode = PortalAuthMode.AUTHENTICATED_ONLY,
    @ColumnName("anonymous_allowlist_domains")
    val anonymousAllowlistDomains: List<String> = emptyList(),
    @ColumnName("sla_policy_id")
    @Contextual
    val slaPolicyId: UUID? = null,
    val enabled: Boolean = true,
    val version: Long = 0,
)

/**
 * R28 — a curated submission entry-point. Each portal exposes a
 * subset of the project's task types under portal-friendly
 * names. The [formSchemaKey] references a Bosca Forms
 * [bosca.forms.model.FormSchema] by its string key, which
 * defines the intake form via JSON Schema + UI Schema.
 */
@Serializable
data class RequestType(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("portal_id")
    @Contextual
    val portalId: UUID,
    val name: String,
    val description: String? = null,
    @ColumnName("task_type_id")
    @Contextual
    val taskTypeId: UUID,
    @ColumnName("default_priority_id")
    @Contextual
    val defaultPriorityId: UUID? = null,
    @ColumnName("display_order")
    val displayOrder: Int = 0,
    @ColumnName("form_schema_key")
    val formSchemaKey: String? = null,
)

/**
 * R28 — a portal user. Authenticated portal users map to a
 * `core-profile` profile id; anonymous users get a magic-link
 * token-tied identity captured via [email].
 */
@Serializable
data class PortalUser(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("portal_id")
    @Contextual
    val portalId: UUID,
    val email: String,
    @ColumnName("profile_id")
    @Contextual
    val profileId: UUID? = null,
    @ColumnName("display_name")
    val displayName: String? = null,
    @ColumnName("created_at")
    @Contextual
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
)

/**
 * R28 — magic-link / per-task viewer token. Anonymous reporters
 * get one of these emailed; the token grants read access to the
 * specific task and append-comment ability for the originating
 * email's window. Stored hashed.
 */
@Serializable
data class PortalToken(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("portal_user_id")
    @Contextual
    val portalUserId: UUID,
    @ColumnName("task_id")
    @Contextual
    val taskId: UUID? = null,
    @ColumnName("token_hash")
    val tokenHash: String,
    @ColumnName("expires_at")
    @Contextual
    val expiresAt: OffsetDateTime,
    @ColumnName("created_at")
    @Contextual
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
    @ColumnName("revoked_at")
    @Contextual
    val revokedAt: OffsetDateTime? = null,
)
