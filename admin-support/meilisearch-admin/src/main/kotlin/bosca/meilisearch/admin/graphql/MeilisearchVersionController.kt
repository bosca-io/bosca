package bosca.meilisearch.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.meilisearch.admin.model.MeilisearchVersion

/**
 * Resolves fields on the MeilisearchVersion GraphQL type.
 */
@TypeController
class MeilisearchVersionController : GraphQLController<MeilisearchVersion> {

    @Field
    fun pkgVersion(version: MeilisearchVersion) = version.pkgVersion

    @Field
    fun commitDate(version: MeilisearchVersion) = version.commitDate

    @Field
    fun commitSha(version: MeilisearchVersion) = version.commitSha
}
