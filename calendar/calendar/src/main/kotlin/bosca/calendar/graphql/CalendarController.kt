package bosca.calendar.graphql

import bosca.calendar.model.Calendar
import bosca.calendar.model.EventOccurrence
import bosca.calendar.service.CalendarService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

@TypeController(type = "Calendar")
class CalendarController(
    private val metadataService: MetadataService,
    private val calendarService: CalendarService,
    private val metadataPermissionEvaluator: MetadataPermissionEvaluator,
) : GraphQLController<Calendar> {

    @Field
    fun metadataId(calendar: Calendar): UUID = calendar.metadataId

    @Field
    fun version(calendar: Calendar): Int = calendar.version

    @Field
    fun color(calendar: Calendar): String = calendar.color

    @Field
    fun description(calendar: Calendar): String = calendar.description

    @Field
    fun created(calendar: Calendar): OffsetDateTime = calendar.created

    @Field
    fun modified(calendar: Calendar): OffsetDateTime = calendar.modified

    @Field
    suspend fun metadata(authentication: AuthenticationContext?, calendar: Calendar): Metadata? {
        val metadata = metadataService.getById(calendar.metadataId, calendar.version) ?: return null
        if (!metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.VIEW)) return null
        return metadata
    }

    @Field
    suspend fun events(
        authentication: AuthenticationContext?,
        calendar: Calendar,
        from: OffsetDateTime,
        to: OffsetDateTime
    ): List<EventOccurrence> =
        calendarService.getOccurrences(authentication, calendar.metadataId, calendar.version, from, to)
}
