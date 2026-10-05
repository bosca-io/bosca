package bosca.profile.persona.repository

import bosca.db.migrations.Migration

/** Flyway migration registry for profile-domain database changes. */
class ProfileMigration : Migration {

    override val schema: String = "profiles"

    override val resources: List<String> = listOf(
        "V1__profiles_schema.sql",
        "V2__studio_personas.sql",
        "V3__guide_progress_analytics_indexes.sql",
        "V4__profile_relationship_distinct_profiles.sql",
        "V5__profile_relationship_requests.sql",
        "V6__profile_searchable.sql",
    )
}
