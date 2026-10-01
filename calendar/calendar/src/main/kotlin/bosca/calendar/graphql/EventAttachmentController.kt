package bosca.calendar.graphql

import bosca.calendar.model.EventAttachment
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

@TypeController(type = "EventAttachment")
class EventAttachmentController : GraphQLController<EventAttachment> {

    @Field
    fun id(attachment: EventAttachment): UUID = attachment.id

    @Field
    fun eventId(attachment: EventAttachment): UUID = attachment.eventId

    @Field
    fun metadataId(attachment: EventAttachment): UUID? = attachment.metadataId

    @Field
    fun collectionId(attachment: EventAttachment): UUID? = attachment.collectionId

    @Field
    fun relationship(attachment: EventAttachment): String = attachment.relationship

    @Field
    fun created(attachment: EventAttachment): OffsetDateTime = attachment.created
}
