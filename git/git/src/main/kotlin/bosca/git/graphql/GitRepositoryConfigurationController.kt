package bosca.git.graphql

import bosca.git.model.MergeStrategy
import bosca.git.model.RepositoryConfiguration
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/**
 * Resolves all fields on the [GitRepositoryConfiguration] GraphQL type from the
 * [RepositoryConfiguration] data class stored as JSONB in the repositories table.
 */
@TypeController(type = "GitRepositoryConfiguration")
class GitRepositoryConfigurationController : GraphQLController<RepositoryConfiguration> {

    @Field
    fun mergeStrategies(source: RepositoryConfiguration): List<MergeStrategy> = source.mergeStrategies

    @Field
    fun squashByDefault(source: RepositoryConfiguration): Boolean = source.squashByDefault

    @Field
    fun deleteBranchOnMerge(source: RepositoryConfiguration): Boolean = source.deleteBranchOnMerge

    @Field
    fun requireSignedCommits(source: RepositoryConfiguration): Boolean = source.requireSignedCommits
}
