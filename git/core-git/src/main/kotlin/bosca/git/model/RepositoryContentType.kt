package bosca.git.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

/**
 * Classifies the intended content of a repository, enabling specialized behaviour
 * such as script-source watching on push.
 */
@DbMapper(RepositoryContentTypeMapper::class)
@Serializable
enum class RepositoryContentType {
    GENERAL,
    SCRIPT_PROJECT,
    DOCUMENTATION,
    ANALYTIC_QUERY_PROJECT,
    AGENT_PROJECT,
    PIPELINE_PROJECT
}

object RepositoryContentTypeMapper : EnumMapper<RepositoryContentType>({
    RepositoryContentType.valueOf(it.uppercase())
})
