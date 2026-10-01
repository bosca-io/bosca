package bosca.workops.model.environment

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import bosca.security.model.PermissibleEntity
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.deploy.DeployTargetKind
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * A **global, user-managed** lifecycle stage in the environment taxonomy (development / staging /
 * production / preview / …). Environments reference a type; a relay's Get Environment node names a
 * type and resolves the concrete [Environment] within the run's program — the type catalog is what
 * makes that name portable across programs that don't share environment names.
 */
@Serializable
data class EnvironmentType(
    @Contextual
    val id: UUID = UUID.NIL,
    /** Globally unique — the portable identifier pipelines reference. */
    val name: String,
    val description: String? = null,
    @ColumnName("display_order")
    val displayOrder: Int = 0,
    val version: Long = 0,
)

/**
 * A deployment target within a program's promotion graph (e.g.,
 * dev → staging → prod). An environment can be promoted from **multiple**
 * sources, so the promotion edges live in the many-to-many
 * `environment_promotion_source` table ([EnvironmentPromotionSource]),
 * not as a column on this row.
 *
 * The [key] is the program-unique, stable identifier release-pipeline YAML links to:
 * the YAML declares environments by key and is the source of truth for their topology; WorkOps owns
 * the display [name]. A [PermissibleEntity]: environment-targeting actions (approve, deploy) check
 * per-environment permission rows exactly like a Metadata, with the parent Program as fallback.
 */
@Serializable
data class Environment(
    @Contextual
    override val id: UUID = UUID.NIL,
    @ColumnName("program_id")
    @Contextual
    val programId: UUID,
    /** Program-unique stable identifier (e.g. `production`) — the YAML link, never displayed. */
    val key: String,
    val name: String,
    val description: String? = null,
    @ColumnName("display_order")
    val displayOrder: Int = 0,
    @ColumnName("requires_approval")
    val requiresApproval: Boolean = false,
    @ColumnName("auto_promote")
    val autoPromote: Boolean = false,
    /** The global [EnvironmentType] this environment is a program-local instance of. */
    @ColumnName("type_id")
    @Contextual
    val typeId: UUID = UUID.NIL,
    /** The external distribution channel this environment deploys to (Play track, TestFlight, API …). */
    @ColumnName("target_type")
    val targetType: EnvironmentTargetType = EnvironmentTargetType.GENERIC,
    /** The specific channel within [targetType] — e.g. a Play track name ("production"), or `null`. */
    @ColumnName("target_ref")
    val targetRef: String? = null,
    /** An on-demand preview environment (per-branch/PR), created and torn down dynamically. */
    val ephemeral: Boolean = false,
    override val public: Boolean = false,
    @ColumnName("public_content")
    override val publicContent: Boolean = false,
    @ColumnName("public_list")
    override val publicList: Boolean = false,
    @ColumnName("public_supplementary")
    override val publicSupplementary: Boolean = false,
    val version: Long = 0,
) : PermissibleEntity<UUID> {

    @Transient
    override val isPublished: Boolean = true

    @Transient
    override val isAdvertised: Boolean = false

    @Transient
    override val isDeleted: Boolean = false
}

/**
 * The external distribution channel an [Environment] deploys to. [Environment.targetRef] names the
 * specific channel within the type — e.g. a [PLAY_TRACK] environment's `targetRef` is the track name
 * ("internal" / "alpha" / "beta" / "production"). [GENERIC] is a server/API deployment slot.
 */
@DbMapper(EnvironmentTargetTypeMapper::class)
@Serializable
enum class EnvironmentTargetType { GENERIC, PLAY_TRACK, TESTFLIGHT, APP_STORE }

object EnvironmentTargetTypeMapper : EnumMapper<EnvironmentTargetType>({ EnvironmentTargetType.valueOf(it.uppercase()) })

/** A promotion edge: [environmentId] can be promoted from [sourceEnvironmentId]. */
@Serializable
data class EnvironmentPromotionSource(
    @ColumnName("environment_id")
    @Contextual
    val environmentId: UUID,
    @ColumnName("source_environment_id")
    @Contextual
    val sourceEnvironmentId: UUID,
)

/**
 * Tracks which version of a project is deployed to an environment,
 * when, by whom, and its health status. The [previousDeploymentId]
 * link enables rollback.
 */
@Serializable
data class EnvironmentDeployment(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("environment_id")
    @Contextual
    val environmentId: UUID,
    @ColumnName("project_id")
    @Contextual
    val projectId: UUID,
    /** Deploy adapter kind; current state is tracked independently for each target kind. */
    @ColumnName("target_kind")
    val targetKind: DeployTargetKind = DeployTargetKind.HELM,
    @ColumnName("version_id")
    @Contextual
    val versionId: UUID,
    @ColumnName("release_id")
    @Contextual
    val releaseId: UUID? = null,
    @ColumnName("artifact_publication_id")
    @Contextual
    val artifactPublicationId: UUID? = null,
    @ColumnName("app_build_number_allocation_id")
    @Contextual
    val appBuildNumberAllocationId: UUID? = null,
    val status: EnvironmentDeploymentStatus = EnvironmentDeploymentStatus.PENDING,
    @ColumnName("deployed_at")
    @Contextual
    val deployedAt: OffsetDateTime? = null,
    @ColumnName("deployed_by_principal_id")
    @Contextual
    val deployedByPrincipalId: UUID? = null,
    @ColumnName("health_check_status")
    val healthCheckStatus: HealthCheckStatus = HealthCheckStatus.UNKNOWN,
    @ColumnName("health_check_url")
    val healthCheckUrl: String? = null,
    @ColumnName("last_health_check_at")
    @Contextual
    val lastHealthCheckAt: OffsetDateTime? = null,
    @ColumnName("previous_deployment_id")
    @Contextual
    val previousDeploymentId: UUID? = null,
    val version: Long = 0,
)

@Serializable
enum class EnvironmentDeploymentStatus { PENDING, DEPLOYING, DEPLOYED, FAILED, ROLLED_BACK }

@Serializable
enum class HealthCheckStatus { UNKNOWN, HEALTHY, DEGRADED, UNHEALTHY }

@Serializable
data class CreateEnvironmentInput(
    @Contextual
    val programId: UUID,
    /** Program-unique stable identifier the release-pipeline YAML links to; immutable after create. */
    val key: String,
    val name: String,
    val description: String? = null,
    val displayOrder: Int = 0,
    /** Environments this one can be promoted from (many-to-many). */
    val promotionSourceIds: List<@Contextual UUID> = emptyList(),
    val requiresApproval: Boolean = false,
    val autoPromote: Boolean = false,
    /** The global [EnvironmentType] this environment instantiates. */
    @Contextual
    val typeId: UUID,
    val targetType: EnvironmentTargetType = EnvironmentTargetType.GENERIC,
    val targetRef: String? = null,
    val ephemeral: Boolean = false,
)

/** Mutable fields of an environment; [programId] is immutable so it is not here. */
@Serializable
data class UpdateEnvironmentInput(
    val name: String,
    val description: String? = null,
    val displayOrder: Int = 0,
    /** Environments this one can be promoted from (many-to-many); replaces the existing set. */
    val promotionSourceIds: List<@Contextual UUID> = emptyList(),
    val requiresApproval: Boolean = false,
    val autoPromote: Boolean = false,
    /** The global [EnvironmentType] this environment instantiates. */
    @Contextual
    val typeId: UUID,
    val targetType: EnvironmentTargetType = EnvironmentTargetType.GENERIC,
    val targetRef: String? = null,
    val ephemeral: Boolean = false,
)

@Serializable
data class DeployInput(
    @Contextual
    val environmentId: UUID,
    @Contextual
    val projectId: UUID,
    val targetKind: DeployTargetKind = DeployTargetKind.HELM,
    @Contextual
    val versionId: UUID,
    @Contextual
    val releaseId: UUID? = null,
    @Contextual
    val artifactPublicationId: UUID? = null,
    @Contextual
    val appBuildNumberAllocationId: UUID? = null,
    val healthCheckUrl: String? = null,
)
