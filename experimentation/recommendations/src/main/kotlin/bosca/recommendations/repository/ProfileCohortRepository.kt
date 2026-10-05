package bosca.recommendations.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

/**
 * Reads a profile's "people like you" cohort memberships (Phase 2) from the
 * `recommendations.profile_cohort` view. Each distinct value emitted by an enabled useAsCohort
 * Personalization Signal is an independent membership, so multi-valued attributes such as learned interests
 * are preserved. Serving reads the same view as the Trino materializer, keeping membership keys identical.
 * An empty list means the viewer is uncohorted and naturally falls back to whole-crowd co-engagement.
 */
@Repository
interface ProfileCohortRepository {

    @Query("select cohort_key from recommendations.profile_cohort where user_id = :profileId order by cohort_key")
    suspend fun getCohortKeys(profileId: UUID): List<String>
}
