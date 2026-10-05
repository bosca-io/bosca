package bosca.security.configuration

import bosca.graphql.annotations.Schema
import bosca.graphql.annotations.Schemas

@Schemas
interface SchemaRegistrar {

    @Schema("security/security.graphqls")
    val security: String

    @Schema("security/tokens.graphqls")
    val tokens: String

    @Schema("security/api-tokens.graphqls")
    val apiTokens: String

    @Schema("security/passkeys.graphqls")
    val passkeys: String
}