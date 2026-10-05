package bosca.artifacts.repository

import bosca.db.migrations.Migration

/**
 * Flyway migration definition for the `artifacts` schema, registering all SQL
 * migration scripts that define the artifact registry's database structure.
 */
class ArtifactsMigration : Migration {

    override val schema: String = "artifacts"

    override val resources: List<String> = listOf(
        "V1__artifacts.sql",
        "V2__ref_count_constraint.sql",
        "V3__add_helm_raw_types.sql",
        "V4__namespace_permissions.sql",
        "V5__add_ml_type.sql",
        "V6__multipart_blob_uploads.sql",
        "V7__artifact_publication.sql",
    )
}
