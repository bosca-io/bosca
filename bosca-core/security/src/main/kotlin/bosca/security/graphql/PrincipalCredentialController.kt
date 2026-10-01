package bosca.security.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PrincipalCredentialAndType

@TypeController("PrincipalCredential")
class PrincipalCredentialController : GraphQLController<PrincipalCredentialAndType> {

    @Field
    fun identifier(credential: PrincipalCredentialAndType) = credential.identifier

    @Field
    fun type(credential: PrincipalCredentialAndType) = credential.type

    @Field
    fun provider(credential: PrincipalCredentialAndType) = credential.provider

    @Field
    fun originator(credential: PrincipalCredentialAndType) = credential.originator

    @Field
    fun lastOriginator(credential: PrincipalCredentialAndType) = credential.lastOriginator
}
