package bosca.bible.providers.configuration

import bosca.db.migrations.Migration

class BibleProvidersMigration : Migration {

    override val schema: String = "bible_providers"

    override val dependsOn: List<String> = listOf("public")

    override val resources: List<String> = listOf(
        "V1__bible_providers.sql",
        "V2__bible_source_metadata_deletion.sql",
    )
}
