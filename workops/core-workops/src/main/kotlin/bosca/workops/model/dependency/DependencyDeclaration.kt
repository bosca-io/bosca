package bosca.workops.model.dependency

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Declares that one project consumes artifacts published by another,
 * optionally pinned to a specific consumer version and constrained
 * to a semver range on the provider side. Status is maintained by
 * automation rules that react to [ArtifactPublication] and
 * [ApiSurfaceReport] events.
 */
@Serializable
data class DependencyDeclaration(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("consumer_project_id")
    @Contextual
    val consumerProjectId: UUID,
    @ColumnName("consumer_version_id")
    @Contextual
    val consumerVersionId: UUID? = null,
    @ColumnName("provider_project_id")
    @Contextual
    val providerProjectId: UUID,
    @ColumnName("provider_version_constraint")
    val providerVersionConstraint: String,
    @ColumnName("resolved_provider_version_id")
    @Contextual
    val resolvedProviderVersionId: UUID? = null,
    @ColumnName("dependency_type")
    val dependencyType: DependencyType,
    @ColumnName("artifact_coordinates")
    val artifactCoordinates: String? = null,
    val status: DependencyStatus = DependencyStatus.CURRENT,
    val version: Long = 0,
)

@Serializable
enum class DependencyType { BUILD, RUNTIME, CONTRACT }

@Serializable
enum class DependencyStatus { CURRENT, OUTDATED, INCOMPATIBLE }

/**
 * Input for creating or updating a dependency declaration between
 * two projects in the WorkOps dependency graph.
 */
@Serializable
data class CreateDependencyDeclarationInput(
    @Contextual
    val consumerProjectId: UUID,
    @Contextual
    val consumerVersionId: UUID? = null,
    @Contextual
    val providerProjectId: UUID,
    val providerVersionConstraint: String,
    @Contextual
    val resolvedProviderVersionId: UUID? = null,
    val dependencyType: DependencyType,
    val artifactCoordinates: String? = null,
)
