package bosca.segmentation.configuration

import bosca.db.migrations.Migration

/**
 * Flyway migration definition for the segmentation schema, which manages audience segments and campaigns.
 */
class SegmentationMigration : Migration {

    override val schema: String = "segmentation"

    override val resources: List<String> = listOf(
        "V1__segmentation.sql",
        "V2__segment_evaluation_schedule.sql",
        "V3__campaign_scheduled_job_id.sql",
        "V4__campaign_ended_at.sql",
        "V5__campaign_placement_weight.sql"
    )
}
