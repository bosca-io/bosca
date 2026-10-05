package bosca.workops.model.artifact

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Mobile-store build-number domain. Android counters are global per application identifier; iOS
 * counters restart for each major/minor release line. Allocations are immutable and identify the
 * source that produced a particular app binary.
 */
@DbMapper(AppBuildPlatformMapper::class)
@Serializable
enum class AppBuildPlatform {
    ANDROID,
    IOS;

    /** The greatest durable sequence value representable by this platform's store build field. */
    val maximumNumber: Long
        get() = when (this) {
            ANDROID -> 2_100_000_000L
            IOS -> 9_999L
        }

    /** Formats a durable sequence as the value embedded in the platform binary. */
    fun format(number: Long): String {
        require(number in 1..maximumNumber) {
            "$name app build number must be between 1 and $maximumNumber, got $number"
        }
        return when (this) {
            ANDROID -> number.toString()
            IOS -> number.toString()
        }
    }

    /** Converts a platform value used as a migration floor into its durable sequence number. */
    fun parse(value: String): Long {
        val number = when (this) {
            ANDROID -> value.toLongOrNull()
                ?: throw IllegalArgumentException("Android build-number floor must be an integer, got '$value'")

            IOS -> value.toLongOrNull()
                ?: throw IllegalArgumentException("iOS build-number floor must be an integer, got '$value'")
        }
        require(number in 1..maximumNumber) {
            "$name build-number floor must be between ${format(1)} and ${format(maximumNumber)}, got '$value'"
        }
        return number
    }

    /** Counter partition used to apply each store's required reset behavior. */
    fun versionScope(sourceVersion: String): String = when (this) {
        ANDROID -> GLOBAL_VERSION_SCOPE
        IOS -> {
            val match = IOS_SOURCE_VERSION.matchEntire(sourceVersion.trim())
                ?: throw IllegalArgumentException(
                    "iOS sourceVersion must be a major.minor.patch version, got '$sourceVersion'",
                )
            val major = match.groupValues[1].toLongOrNull()
            val minor = match.groupValues[2].toLongOrNull()
            require(major != null && minor != null) {
                "iOS sourceVersion components are too large, got '$sourceVersion'"
            }
            "$major.$minor"
        }
    }

    companion object {
        private const val GLOBAL_VERSION_SCOPE = "global"
        private val IOS_SOURCE_VERSION = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+].*)?$")
    }
}

object AppBuildPlatformMapper : EnumMapper<AppBuildPlatform>({ AppBuildPlatform.valueOf(it.uppercase()) })

/** One immutable build-number allocation for a source/version identity. */
@Serializable
data class AppBuildNumberAllocation(
    @Contextual
    val id: UUID = UUID.NIL,
    val platform: AppBuildPlatform,
    @ColumnName("application_id")
    val applicationId: String,
    /** `global` for Android; normalized `major.minor` for new iOS allocations. */
    @ColumnName("version_scope")
    val versionScope: String = "global",
    @ColumnName("build_key")
    val buildKey: String = "default",
    @ColumnName("repository_id")
    @Contextual
    val repositoryId: UUID,
    @ColumnName("source_commit_sha")
    val sourceCommitSha: String,
    @ColumnName("source_version")
    val sourceVersion: String,
    @ColumnName("pipeline_run_id")
    @Contextual
    val pipelineRunId: UUID,
    /** Monotonic value within [versionScope], embedded directly as versionCode or CFBundleVersion. */
    val number: Long,
    /** Store value embedded in the binary. */
    val value: String,
    @ColumnName("created_at")
    @Contextual
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
)

/** Request to allocate or idempotently recover an app build number. */
data class AllocateAppBuildNumberInput(
    val platform: AppBuildPlatform,
    val applicationId: String,
    val buildKey: String = "default",
    val repositoryId: UUID,
    val sourceCommitSha: String,
    val sourceVersion: String,
    val pipelineRunId: UUID,
    /** First value that may be allocated, used to migrate above numbers created outside WorkOps. */
    val minimumNumber: Long = 1,
)

data class AppBuildNumberAllocationResult(
    val allocation: AppBuildNumberAllocation,
    val reused: Boolean,
)
