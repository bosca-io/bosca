package bosca.feeds.migration

import bosca.db.migrations.Migration

/**
 * Flyway migrations for the `feeds` Postgres schema.
 *
 * Two silent-failure traps this module deliberately guards:
 *  - The registering `@Provider` MUST be named (see [bosca.feeds.configuration.Configuration]) — an
 *    unnamed provider clobbers the `Migration` type slot and no migrations run.
 *  - Every `.sql` file MUST be listed in [resources] — an unlisted file simply never runs.
 */
class FeedsMigration : Migration {

    override val schema: String = "feeds"

    override val resources: List<String> = listOf(
        "V1__feeds.sql",
        "V2__feed_items.sql",
        "V3__feed_subscriptions.sql",
        "V4__feed_item_template.sql",
    )
}
