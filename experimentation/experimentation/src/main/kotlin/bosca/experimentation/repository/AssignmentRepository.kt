package bosca.experimentation.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.experimentation.model.Assignment
import bosca.serialization.UUID
import java.time.OffsetDateTime

@Repository
interface AssignmentRepository {

    /**
     * Resolves an assignment by authenticated principal or installation.
     *
     * Principal identity takes precedence when both identifiers already point
     * at rows. Otherwise the installation lookup allows an anonymous row to be
     * found and claimed when that installation subsequently logs in.
     */
    @Query("""
        select * from experimentation.assignments
        where experiment_id = :experimentId
          and (principal_id = :principalId or installation_id = :installationId)
        order by case when principal_id = :principalId then 0 else 1 end
        limit 1
    """)
    suspend fun getByIdentity(
        experimentId: UUID,
        principalId: UUID?,
        installationId: String,
    ): Assignment?

    /**
     * Bulk-loads the variation assignments for a batch of authenticated
     * converters in this experiment. Used by the result aggregation job
     * to attribute conversions in a single round-trip, bounded by the
     * number of *converting* principals rather than total assignments,
     * so an experiment with 100M rows but 10k authenticated converters
     * reads back ~10k rows, not the whole table.
     *
     * UUIDs bind as a PostgreSQL array.
     */
    @Query("""
        select * from experimentation.assignments
        where experiment_id = :experimentId
          and principal_id = any(:principalIds)
    """)
    suspend fun getByConvertingPrincipals(
        experimentId: UUID,
        principalIds: List<UUID>,
    ): List<Assignment>

    /**
     * Bulk-loads the variation assignments for a batch of anonymous
     * converters (identified by installation id) in this experiment.
     * Installation ids bind as a PostgreSQL text array.
     */
    @Query("""
        select * from experimentation.assignments
        where experiment_id = :experimentId
          and installation_id = any(:installationIds)
    """)
    suspend fun getByConvertingInstallations(
        experimentId: UUID,
        installationIds: List<String>,
    ): List<Assignment>

    @Query("select * from experimentation.assignments where experiment_id = :experimentId and principal_id = :principalId")
    suspend fun getByPrincipal(experimentId: UUID, principalId: UUID): Assignment?

    @Query("select * from experimentation.assignments where principal_id = :principalId")
    suspend fun getAllByPrincipal(principalId: UUID): List<Assignment>

    @Query("select * from experimentation.assignments where experiment_id = :experimentId and installation_id = :installationId")
    suspend fun getByInstallation(experimentId: UUID, installationId: String): Assignment?

    @Query("select * from experimentation.assignments where installation_id = :installationId")
    suspend fun getAllByInstallation(installationId: String): List<Assignment>

    @Query("""
        select * from experimentation.assignments
        where principal_id = :principalId
          and experiment_id in (
              select id from experimentation.experiments where exclusion_layer_id = :layerId
          )
        limit 1
    """)
    suspend fun getByPrincipalInLayer(principalId: UUID, layerId: UUID): Assignment?

    @Query("""
        select * from experimentation.assignments
        where installation_id = :installationId
          and experiment_id in (
              select id from experimentation.experiments where exclusion_layer_id = :layerId
          )
        limit 1
    """)
    suspend fun getByInstallationInLayer(installationId: String, layerId: UUID): Assignment?

    /**
     * True when the user already has an assignment to *some other* experiment
     * in the supplied exclusion layer. The whole point of an exclusion layer
     * is "a user is in at most one experiment in this layer at a time", so
     * the flag evaluator uses this to decide whether a targeting rule with
     * an attached layered experiment matches. The `experiment_id != :experimentId`
     * clause excludes the current experiment from the check so a user already
     * in this experiment keeps their bucket.
     */
    @Query("""
        select exists(
            select 1 from experimentation.assignments
            where principal_id = :principalId
              and experiment_id != :experimentId
              and experiment_id in (
                  select id from experimentation.experiments where exclusion_layer_id = :layerId
              )
        )
    """)
    suspend fun existsForOtherExperimentInLayerByPrincipal(
        layerId: UUID,
        experimentId: UUID,
        principalId: UUID,
    ): Boolean

    @Query("""
        select exists(
            select 1 from experimentation.assignments
            where installation_id = :installationId
              and experiment_id != :experimentId
              and experiment_id in (
                  select id from experimentation.experiments where exclusion_layer_id = :layerId
              )
        )
    """)
    suspend fun existsForOtherExperimentInLayerByInstallation(
        layerId: UUID,
        experimentId: UUID,
        installationId: String,
    ): Boolean

    @Query("select count(*) from experimentation.assignments where experiment_id = :experimentId and variation_key = :variationKey")
    suspend fun countByVariation(experimentId: UUID, variationKey: String): Long

    /**
     * Resolves every installation currently owned by an excluded principal for this experiment's
     * flag. Flag assignments retain installation ownership after sign-out, so this also finds an
     * experiment assignment that remained anonymous because the principal already owned a
     * different assignment row.
     */
    @Query("""
        select distinct fa.installation_id
        from experimentation.experiments e
        join experimentation.flag_assignments fa on fa.flag_id = e.feature_flag_id
        where e.id = :experimentId
          and fa.principal_id = any(:excludedPrincipalIds)
    """)
    suspend fun getExcludedInstallationIds(
        experimentId: UUID,
        excludedPrincipalIds: List<UUID>,
    ): List<String>

    /**
     * Counts an arm's assignments after omitting rows owned by an excluded principal or one of
     * that principal's resolved installations. Assignments made after the aggregation cutoff
     * cannot contribute to either the denominator or the observed outcomes.
     */
    @Query("""
        select count(*) from experimentation.assignments
        where experiment_id = :experimentId
          and variation_key = :variationKey
          and assigned_at <= :assignedBefore
          and (
              principal_id is null
              or not (principal_id = any(:excludedPrincipalIds))
          )
          and (
              installation_id is null
              or not (installation_id = any(:excludedInstallationIds))
          )
    """)
    suspend fun countEligibleByVariation(
        experimentId: UUID,
        variationKey: String,
        excludedPrincipalIds: List<UUID>,
        excludedInstallationIds: List<String>,
        assignedBefore: OffsetDateTime,
    ): Long

    @Query("""
        select installation_id from experimentation.assignments
        where experiment_id = :experimentId and variation_key = :variationKey and installation_id is not null
    """)
    suspend fun getInstallationIdsByVariation(experimentId: UUID, variationKey: String): List<String>

    @Query("""
        select cast(principal_id as text) from experimentation.assignments
        where experiment_id = :experimentId and variation_key = :variationKey and principal_id is not null
    """)
    suspend fun getPrincipalIdsByVariation(experimentId: UUID, variationKey: String): List<String>

    /**
     * Inserts a new assignment carrying its required installation id and its
     * optional authenticated owner. Either identity conflict loses the race
     * without changing the existing assignment.
     */
    @Query("""
        insert into experimentation.assignments
            (experiment_id, variation_key, principal_id, installation_id)
        values (:experimentId, :variationKey, :principalId, :installationId)
        on conflict do nothing
        returning *
    """)
    suspend fun addIfAbsent(
        experimentId: UUID,
        variationKey: String,
        principalId: UUID?,
        installationId: String,
    ): Assignment?

    /**
     * Claims an anonymous installation assignment for [principalId]. The
     * variation and original assignment timestamp are deliberately unchanged.
     * A row already owned by any principal cannot be modified by this query.
     */
    @Query("""
        update experimentation.assignments
        set principal_id = :principalId
        where experiment_id = :experimentId
          and installation_id = :installationId
          and principal_id is null
        returning *
    """)
    suspend fun claimPrincipal(
        experimentId: UUID,
        installationId: String,
        principalId: UUID,
    ): Assignment?
}
