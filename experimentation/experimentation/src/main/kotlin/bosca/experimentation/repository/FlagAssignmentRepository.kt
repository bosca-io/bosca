package bosca.experimentation.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.experimentation.model.VariationAssignmentCount
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/**
 * Stores one current feature-flag variation assignment per installation.
 *
 * A row may start anonymously and later acquire a principal. Once acquired,
 * that principal is immutable: anonymous evaluations and evaluations for a
 * different principal cannot update the row. The assignment timestamp changes
 * only when evaluation produces a different variation.
 */
@Repository
interface FlagAssignmentRepository {

    /**
     * Creates or updates an installation's assignment. The conflict key is
     * `(flag_id, installation_id)`, so logging in enriches the existing row
     * instead of creating a principal-keyed duplicate.
     *
     * An existing row can be updated only when it has no principal owner or
     * its owner equals [principalId]. A null or different incoming principal
     * can never change an owned row.
     *
     * Returns true for an initial assignment or variation change, false when
     * only principal ownership was attached, and null when nothing changed or
     * the write was rejected.
     */
    @Query("""
        insert into experimentation.flag_assignments
            (flag_id, variation_key, principal_id, installation_id, assigned_at)
        select :flagId, :variationKey, :principalId, :installationId, :assignedAt
        from experimentation.feature_flags
        where id = :flagId and modified = :flagModified
        on conflict (flag_id, installation_id) do update set
            principal_id = coalesce(flag_assignments.principal_id, excluded.principal_id),
            variation_key = excluded.variation_key,
            assigned_at = case
                when flag_assignments.variation_key = excluded.variation_key
                    then flag_assignments.assigned_at
                else excluded.assigned_at
            end
        where (flag_assignments.principal_id is null
               or flag_assignments.principal_id = excluded.principal_id)
          and flag_assignments.assigned_at <= excluded.assigned_at
          and (flag_assignments.variation_key <> excluded.variation_key
               or (flag_assignments.principal_id is null
                   and excluded.principal_id is not null))
        returning assigned_at = :assignedAt
    """)
    suspend fun record(
        flagId: UUID,
        installationId: String,
        principalId: UUID?,
        variationKey: String,
        assignedAt: OffsetDateTime,
        flagModified: OffsetDateTime,
    ): Boolean?

    /** Returns one count for each variation with current assignments. */
    @Query("""
        select variation_key as "variationKey",
               count(*) as "assignmentCount"
        from experimentation.flag_assignments
        where flag_id = :flagId
        group by variation_key
    """)
    suspend fun getDistribution(flagId: UUID): List<VariationAssignmentCount>

    /**
     * Removes assignments whose variation was deleted from the flag palette.
     * Those rows no longer describe a valid current assignment; their history
     * remains available in analytics.
     */
    @Query("""
        delete from experimentation.flag_assignments
        where flag_id = :flagId
          and variation_key not in (
              select unnest(string_to_array(:validKeys, E'\u001f'))
          )
    """)
    suspend fun pruneOrphanedVariations(flagId: UUID, validKeys: String)
}
