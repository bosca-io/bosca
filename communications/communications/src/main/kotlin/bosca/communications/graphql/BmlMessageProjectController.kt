package bosca.communications.graphql

import bosca.communications.model.BmlMessageProject
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

@TypeController(type = "BmlMessageProject")
class BmlMessageProjectController : GraphQLController<BmlMessageProject> {

    @Field fun key(source: BmlMessageProject): String = source.key
    @Field fun description(source: BmlMessageProject): String? = source.description
    @Field fun repositoryId(source: BmlMessageProject): UUID? = source.repositoryId
    @Field fun pinnedVersion(source: BmlMessageProject): String? = source.pinnedVersion
    @Field fun created(source: BmlMessageProject): OffsetDateTime = source.created
    @Field fun modified(source: BmlMessageProject): OffsetDateTime = source.modified
}
