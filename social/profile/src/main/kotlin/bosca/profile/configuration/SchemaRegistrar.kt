package bosca.profile.configuration

import bosca.graphql.annotations.Schema
import bosca.graphql.annotations.Schemas

@Schemas
interface SchemaRegistrar {

    @Schema("profiles.graphqls")
    val profiles: String

    @Schema("organizations.graphqls")
    val organizations: String

    @Schema("personas.graphqls")
    val personas: String
}