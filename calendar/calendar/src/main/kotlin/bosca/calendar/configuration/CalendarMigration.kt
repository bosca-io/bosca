package bosca.calendar.configuration

import bosca.db.migrations.Migration

/**
 * Flyway migration definition for the `calendar` schema, which stores user
 * calendars and ad-hoc events.
 */
class CalendarMigration : Migration {

    override val schema: String = "calendar"

    override val resources: List<String> = listOf(
        "V1__calendar.sql",
        "V2__event_participants_and_attachments.sql"
    )
}
