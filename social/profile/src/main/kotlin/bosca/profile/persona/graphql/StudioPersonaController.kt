package bosca.profile.persona.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.persona.model.StudioPersona
import bosca.serialization.UUID
import java.time.OffsetDateTime

/** Field resolver for the StudioPersona GraphQL type. */
@TypeController(type = "StudioPersona")
class StudioPersonaController : GraphQLController<StudioPersona> {

    @Field
    fun id(persona: StudioPersona): UUID = persona.id

    @Field
    fun name(persona: StudioPersona): String = persona.name

    @Field
    fun description(persona: StudioPersona): String? = persona.description

    @Field
    fun subsystemIds(persona: StudioPersona): List<String> = persona.subsystemIds

    @Field
    fun enabled(persona: StudioPersona): Boolean = persona.enabled

    @Field
    fun created(persona: StudioPersona): OffsetDateTime = persona.created

    @Field
    fun modified(persona: StudioPersona): OffsetDateTime = persona.modified
}
