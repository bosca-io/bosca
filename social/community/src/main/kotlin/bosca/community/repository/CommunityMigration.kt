package bosca.community.repository

import bosca.db.migrations.Migration

/**
 * Flyway migration registry for the community schema, managing
 * prayer wall, tracking, and social interaction table evolution.
 */
class CommunityMigration : Migration {

    override val schema: String = "community"

    override val resources: List<String> = listOf(
        "V1__prayer_wall_columns.sql",
        "V2__prayer_prayed_tracking.sql",
        "V3__prayer_likes.sql",
        "V4__prayer_comment_count.sql",
        "V5__prayer_shares.sql",
        "V6__prayer_anniversaries.sql",
        "V7__move_prayer_tables_to_community.sql",
    )
}
