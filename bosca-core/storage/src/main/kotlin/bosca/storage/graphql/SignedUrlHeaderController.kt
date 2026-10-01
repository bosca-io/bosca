package bosca.storage.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.storage.service.SignedUrlHeader

@TypeController
class SignedUrlHeaderController : GraphQLController<SignedUrlHeader> {

    @Field
    fun name(signedUrlHeader: SignedUrlHeader) = signedUrlHeader.name

    @Field
    fun value(signedUrlHeader: SignedUrlHeader) = signedUrlHeader.value
}