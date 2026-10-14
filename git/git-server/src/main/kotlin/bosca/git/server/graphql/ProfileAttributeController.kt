package bosca.git.server.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.attribute.model.ProfileAttribute

/** Scalar identity fields; the Profile domain filters attributes before returning them. */
@TypeController(type = "ProfileAttribute")
class ProfileAttributeController : GraphQLController<ProfileAttribute> {
    @Field fun id(source: ProfileAttribute) = source.id

    @Field fun typeId(source: ProfileAttribute) = source.typeId

    @Field fun attributes(source: ProfileAttribute) = source.attributes

    @Field fun source(source: ProfileAttribute) = source.source

    @Field fun priority(source: ProfileAttribute) = source.priority

    @Field fun confidence(source: ProfileAttribute) = source.confidence

    @Field fun visibility(source: ProfileAttribute) = source.visibility
}
