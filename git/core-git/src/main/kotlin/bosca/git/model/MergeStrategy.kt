package bosca.git.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

/**
 * Determines how a pull request's source branch is integrated into the target branch
 * when merged. The allowed strategies for a repository are configured in
 * [RepositoryConfiguration.mergeStrategies].
 */
@DbMapper(MergeStrategyMapper::class)
@Serializable
enum class MergeStrategy {
    MERGE_COMMIT,
    SQUASH,
    REBASE,
    FAST_FORWARD
}

object MergeStrategyMapper : EnumMapper<MergeStrategy>({ MergeStrategy.valueOf(it.uppercase()) })
