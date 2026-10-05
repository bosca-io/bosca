package bosca.recommendations.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.recommendations.model.PersonalizationSignalDefinition
import bosca.recommendations.model.PersonalizationSignalSourceType
import bosca.recommendations.model.PersonalizationSignalValueType
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/**
 * Resolves the scalar fields of the PersonalizationSignalDefinition GraphQL type, passed through
 * from the model.
 */
@TypeController
class PersonalizationSignalController : GraphQLController<PersonalizationSignalDefinition> {

    @Field
    fun id(signal: PersonalizationSignalDefinition): UUID = signal.id

    @Field
    fun key(signal: PersonalizationSignalDefinition): String = signal.key

    @Field
    fun sourceType(signal: PersonalizationSignalDefinition): PersonalizationSignalSourceType = signal.sourceType

    @Field
    fun sourceId(signal: PersonalizationSignalDefinition): String = signal.sourceId

    @Field
    fun expression(signal: PersonalizationSignalDefinition): String = signal.expression

    @Field
    fun valueType(signal: PersonalizationSignalDefinition): PersonalizationSignalValueType = signal.valueType

    @Field
    fun priority(signal: PersonalizationSignalDefinition): Int = signal.priority

    @Field
    fun useAsFeature(signal: PersonalizationSignalDefinition): Boolean = signal.useAsFeature

    @Field
    fun useAsCohort(signal: PersonalizationSignalDefinition): Boolean = signal.useAsCohort

    @Field
    fun enabled(signal: PersonalizationSignalDefinition): Boolean = signal.enabled

    @Field
    fun created(signal: PersonalizationSignalDefinition): OffsetDateTime = signal.created

    @Field
    fun modified(signal: PersonalizationSignalDefinition): OffsetDateTime = signal.modified
}
