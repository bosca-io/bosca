package bosca.git.ui

import kotlinx.serialization.Serializable

/** One repository statistic tile, such as `Commits` and `2`. */
@Serializable
data class RepositoryStatistic(val label: String, val value: String)
