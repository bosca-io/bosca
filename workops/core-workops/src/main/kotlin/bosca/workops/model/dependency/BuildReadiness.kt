package bosca.workops.model.dependency

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Aggregated readiness assessment for building a project right now.
 * Computed from [DependencyDeclaration] statuses, compile-check
 * results, and artifact publication state — not stored in a table.
 */
@Serializable
data class BuildReadiness(
    val ready: Boolean,
    val blockers: List<BuildBlocker>,
)

@Serializable
data class BuildBlocker(
    val blockerType: BuildBlockerType,
    @Contextual
    val providerProjectId: UUID,
    @Contextual
    val providerVersionId: UUID? = null,
    val description: String,
    @Contextual
    val apiSurfaceReportId: UUID? = null,
    @Contextual
    val compatibilityTestId: UUID? = null,
)

@Serializable
enum class BuildBlockerType {
    INCOMPATIBLE_DEPENDENCY,
    COMPILE_CHECK_FAILED,
    COMPILE_CHECK_PENDING,
    PROVIDER_ARTIFACTS_MISSING,
}
