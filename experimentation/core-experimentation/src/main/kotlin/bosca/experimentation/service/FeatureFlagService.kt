package bosca.experimentation.service

import bosca.experimentation.model.FeatureFlag
import bosca.experimentation.model.FeatureFlagInput
import bosca.experimentation.model.FlagEvaluation
import bosca.experimentation.model.FlagStatus
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Service for managing feature flags and evaluating them against user/device context.
 *
 * Feature flags control the availability of features across server and client
 * applications. Each flag has an ordered set of targeting rules evaluated
 * top-to-bottom; the first matching rule determines the resolved value.
 * Flags can be linked to experiments for A/B testing, and include a kill
 * switch for emergency deactivation.
 */
interface FeatureFlagService : Service {

    /**
     * Retrieves a paginated list of all feature flags, ordered by creation date descending.
     *
     * @param offset the number of flags to skip for pagination
     * @param limit the maximum number of flags to return
     * @return the list of feature flags
     */
    suspend fun getAll(offset: Long, limit: Int): List<FeatureFlag>

    /**
     * Retrieves a single feature flag by its unique identifier.
     *
     * @param id the UUID of the feature flag
     * @return the matching flag, or `null` if not found
     */
    suspend fun getById(id: UUID): FeatureFlag?

    /**
     * Retrieves a single feature flag by its unique string key.
     *
     * @param key the flag key (e.g., "new-checkout-flow")
     * @return the matching flag, or `null` if not found
     */
    suspend fun getByKey(key: String): FeatureFlag?

    /**
     * Creates a new feature flag from the provided input specification.
     *
     * @param input the flag definition
     * @return the newly created feature flag
     */
    suspend fun add(input: FeatureFlagInput): FeatureFlag

    /**
     * Updates an existing feature flag with the provided specification.
     * Publishes a flag update event for cache invalidation and client notification.
     *
     * @param id the UUID of the flag to update
     * @param input the updated flag definition
     * @return the modified feature flag
     */
    suspend fun edit(id: UUID, input: FeatureFlagInput): FeatureFlag

    /**
     * Permanently deletes a feature flag and all associated experiments,
     * variants, and assignments. Publishes a flag deletion event.
     *
     * @param id the UUID of the flag to delete
     */
    suspend fun delete(id: UUID)

    /**
     * Updates the lifecycle status of a feature flag and publishes an update event.
     *
     * @param id the UUID of the flag
     * @param status the new status
     * @return the modified feature flag
     */
    suspend fun setStatus(id: UUID, status: FlagStatus): FeatureFlag

    /**
     * Generates a new random salt for the specified feature flag, reshuffling
     * all percentage-based targeting rule bucket assignments. Existing users
     * may be moved into or out of percentage rollouts after this operation.
     *
     * @param id the UUID of the flag to resalt
     * @return the modified feature flag with its new salt
     */
    suspend fun regenerateSalt(id: UUID): FeatureFlag

    /**
     * Returns the current assignment distribution for a feature flag.
     * Each installation contributes to exactly one variation. A principal
     * may own an installation's row, but ownership never creates a second
     * assignment. Historical transitions are stored in analytics rather than
     * mixed into this operational snapshot.
     *
     * Variations with no current assignments are absent from the result.
     *
     * @param flagId feature flag whose assignments should be aggregated
     */
    suspend fun getVariationAssignments(
        flagId: UUID,
    ): List<bosca.experimentation.model.VariationAssignmentCount>

    /**
     * Evaluates a single feature flag for the given user/device context,
     * resolving targeting rules and experiment assignments. Flags whose
     * status is not [FlagStatus.ENABLED] short-circuit to the default
     * variation regardless of rules or assignments.
     *
     * @param flagKey the flag key to evaluate
     * @param principalId the authenticated user's principal ID, or null for anonymous
     * @param installationId the required device installation ID
     * @param device typed analytics [Device] snapshot used by `DeviceAttribute`
     *        targeting conditions; null when the caller has no device context
     * Evaluation infrastructure failures are reported to observability and fail
     * closed to a degraded result instead of propagating through page rendering.
     *
     * @return the resolved flag evaluation containing the value and optional variant info
     */
    suspend fun evaluate(
        flagKey: String,
        principalId: UUID?,
        installationId: String,
        device: bosca.analytics.model.Device?
    ): FlagEvaluation

    /**
     * Evaluates all active feature flags for the given user/device context.
     *
     * @param principalId the authenticated user's principal ID, or null for anonymous
     * @param installationId the device installation ID
     * @param device typed analytics [Device] snapshot used by `DeviceAttribute`
     *        targeting conditions; null when the caller has no device context
     * Evaluation infrastructure failures are reported to observability. A failure
     * to load the active set returns an empty snapshot; a failure isolated to one
     * known flag returns that flag's degraded default.
     *
     * @return evaluations for all active flags
     */
    suspend fun evaluateAll(
        principalId: UUID?,
        installationId: String,
        device: bosca.analytics.model.Device?
    ): List<FlagEvaluation>
}
