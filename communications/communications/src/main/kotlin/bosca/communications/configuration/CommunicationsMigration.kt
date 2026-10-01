package bosca.communications.configuration

import bosca.db.migrations.Migration

/**
 * Flyway migration definition for the ``communications`` schema, which stores
 * delivery events, delivery status, email preferences, and the
 * suppression list.
 */
class CommunicationsMigration : Migration {

    override val schema: String = "communications"

    override val resources: List<String> = listOf(
        "V1__communications.sql",
        "V2__notification_preferences.sql",
        "V3__drop_email_preferences.sql",
        "V4__bml_email_registry.sql",
        "V5__drop_email_event_templates.sql",
        "V6__notification_preference_mappings.sql",
        "V7__make_optional_notification_types_deletable.sql",
        "V8__delivery_tracking_paging_and_idempotency.sql",
        "V9__hide_notification_types.sql",
        "V10__lowercase_communications_enums.sql",
        "V11__git_activity_notification_type.sql",
        "V12__workops_activity_notification_type.sql",
        "V13__delivery_email_template_render.sql",
        "V14__social_activity_notification_type.sql",
        "V15__message_template_names.sql",
        "V16__message_outbox.sql",
        "V17__message_project_keys.sql",
        "V18__notification_type_defaults.sql",
        "V19__notification_type_channel_defaults.sql",
    )
}
