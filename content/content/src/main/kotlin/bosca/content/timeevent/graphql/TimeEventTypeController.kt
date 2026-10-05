package bosca.content.timeevent.graphql

import bosca.content.attributes.model.TemplateAttribute
import bosca.content.timeevent.model.TimeEventType
import bosca.content.timeevent.service.TimeEventService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController
class TimeEventTypeController(
    private val timeEventService: TimeEventService
) : GraphQLController<TimeEventType> {

    @Field
    fun id(type: TimeEventType) = type.id

    @Field
    fun name(type: TimeEventType) = type.name

    @Field
    fun description(type: TimeEventType) = type.description

    @Field
    fun schema(type: TimeEventType) = type.schema

    @Field
    fun configuration(type: TimeEventType) = type.configuration

    @Field
    suspend fun attributes(type: TimeEventType): List<TemplateAttribute> {
        return timeEventService.getTypeAttributes(type.id)
    }
}
