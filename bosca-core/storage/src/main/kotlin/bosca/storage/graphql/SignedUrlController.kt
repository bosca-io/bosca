package bosca.storage.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.storage.service.SignedUrl

@TypeController
class SignedUrlController : GraphQLController<SignedUrl> {

    @Field
    fun url(signedUrl: SignedUrl) = signedUrl.url

    @Field
    fun headers(signedUrl: SignedUrl) = signedUrl.headers
}