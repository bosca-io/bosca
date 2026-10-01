package bosca.workops.model.artifact

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Records the result of an API surface diff between two versions of a
 * project. Produced by tools like japicmp (JVM bytecode) or GraphQL
 * Inspector (schema diffs). When [breakingChangeLevel] indicates
 * incompatibility, downstream [DependencyDeclaration] entries are
 * automatically flipped to [DependencyStatus.INCOMPATIBLE].
 */
@Serializable
data class ApiSurfaceReport(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("project_id")
    @Contextual
    val projectId: UUID,
    @ColumnName("version_id")
    @Contextual
    val versionId: UUID,
    @ColumnName("previous_version_id")
    @Contextual
    val previousVersionId: UUID,
    @ColumnName("artifact_publication_id")
    @Contextual
    val artifactPublicationId: UUID? = null,
    @ColumnName("breaking_change_level")
    val breakingChangeLevel: BreakingChangeLevel,
    @Contextual
    val changes: kotlinx.serialization.json.JsonElement = kotlinx.serialization.json.JsonArray(emptyList()),
    @ColumnName("analyzed_at")
    @Contextual
    val analyzedAt: OffsetDateTime = OffsetDateTime.now(),
    @ColumnName("analyzer_tool")
    val analyzerTool: String,
    @ColumnName("report_url")
    val reportUrl: String? = null,
    val version: Long = 0,
)

@Serializable
enum class BreakingChangeLevel { NONE, DEPRECATION, MINOR_BREAKING, MAJOR_BREAKING }

/**
 * A single change detected in the API surface diff. Stored as JSONB
 * array elements in [ApiSurfaceReport.changes].
 */
@Serializable
data class ApiChange(
    val changeType: ApiChangeType,
    val severity: ApiChangeSeverity,
    val symbol: String,
    val description: String,
    val module: String? = null,
)

@Serializable
enum class ApiChangeType { ADDED, REMOVED, MODIFIED, DEPRECATED }

@Serializable
enum class ApiChangeSeverity { COMPATIBLE, SOURCE_INCOMPATIBLE, BINARY_INCOMPATIBLE }
