package bosca.segmentation.service

import bosca.segmentation.model.Segment
import bosca.segmentation.model.SegmentInput
import bosca.segmentation.model.SegmentMember
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Service for managing audience segments used to target groups of profiles
 * for banners, email blasts, and push notifications.
 *
 * Provides CRUD operations for segment definitions and membership management,
 * as well as audience evaluation for dynamic segments that execute analytics
 * queries against the data warehouse.
 */
interface SegmentService : Service {

    /**
     * Retrieves a paginated list of all segments.
     *
     * @param offset the number of segments to skip for pagination
     * @param limit the maximum number of segments to return
     * @return the list of [Segment] instances
     */
    suspend fun getAll(offset: Long, limit: Int): List<Segment>

    /**
     * Retrieves a single segment by its identifier.
     *
     * @param id the UUID of the segment
     * @return the [Segment] matching the given [id], or `null` if not found
     */
    suspend fun getById(id: UUID): Segment?

    /**
     * Retrieves multiple segments by their identifiers in a single query,
     * avoiding N+1 query patterns when resolving lists of segments.
     *
     * @param ids the UUIDs of the segments to retrieve
     * @return the list of [Segment] instances found (missing IDs are omitted)
     */
    suspend fun getByIds(ids: List<UUID>): List<Segment>

    /**
     * Creates a new segment from the provided input specification.
     *
     * @param input the segment definition including name, type, and optional analytics query binding
     * @return the newly created [Segment]
     */
    suspend fun add(input: SegmentInput): Segment

    /**
     * Updates an existing segment with the provided specification.
     *
     * @param id the UUID of the segment to update
     * @param input the updated segment definition
     * @return the modified [Segment]
     */
    suspend fun edit(id: UUID, input: SegmentInput): Segment

    /**
     * Deletes a segment and all associated membership data.
     *
     * @param id the UUID of the segment to delete
     */
    suspend fun delete(id: UUID)

    /**
     * Retrieves the profile IDs that are members of the specified segment.
     *
     * @param segmentId the UUID of the segment
     * @param offset the number of members to skip for pagination
     * @param limit the maximum number of members to return
     * @return the list of [SegmentMember] entries
     */
    suspend fun getMembers(segmentId: UUID, offset: Long, limit: Int): List<SegmentMember>

    /**
     * Returns the total number of profiles that are members of the specified segment.
     *
     * @param segmentId the UUID of the segment
     * @return the member count
     */
    suspend fun getMemberCount(segmentId: UUID): Long

    /**
     * Adds one or more profiles to a static segment's membership.
     *
     * @param segmentId the UUID of the segment
     * @param profileIds the profile UUIDs to add as members
     */
    suspend fun addMembers(segmentId: UUID, profileIds: List<UUID>)

    /**
     * Removes one or more profiles from a segment's membership.
     *
     * @param segmentId the UUID of the segment
     * @param profileIds the profile UUIDs to remove
     */
    suspend fun removeMembers(segmentId: UUID, profileIds: List<UUID>)

    /**
     * Evaluates a dynamic segment by executing its associated analytics query
     * and refreshing the membership table with the resulting profile IDs.
     *
     * @param segmentId the UUID of the dynamic segment to evaluate
     * @return the updated [Segment] with refreshed member count and evaluation timestamp
     */
    suspend fun evaluate(segmentId: UUID): Segment

    /**
     * Executes the specified analytics query and appends the resulting profile IDs
     * to a static segment's membership. Existing members are preserved and duplicate
     * profile IDs are silently ignored.
     *
     * @param segmentId the UUID of the static segment to populate
     * @param analyticsQueryId the UUID of the analytics query to execute against the data warehouse
     * @return the updated [Segment] reflecting the new member count
     * @throws IllegalStateException if the segment is not found
     * @throws IllegalArgumentException if the segment is not of type STATIC
     */
    suspend fun populateFromQuery(segmentId: UUID, analyticsQueryId: UUID): Segment

    /**
     * Executes the specified analytics query and removes the resulting profile IDs
     * from a static segment's membership. Profile IDs not present in the segment
     * are silently ignored.
     *
     * @param segmentId the UUID of the static segment to remove members from
     * @param analyticsQueryId the UUID of the analytics query to execute against the data warehouse
     * @return the updated [Segment] reflecting the new member count
     * @throws IllegalStateException if the segment is not found
     * @throws IllegalArgumentException if the segment is not of type STATIC
     */
    suspend fun removeFromQuery(segmentId: UUID, analyticsQueryId: UUID): Segment

    /**
     * Removes all profiles from a static segment that are also members of another
     * segment. This performs a set subtraction at the database level without loading
     * all member IDs into memory.
     *
     * @param segmentId the UUID of the static segment to remove members from
     * @param removeSegmentId the UUID of the segment whose members should be removed
     * @return the updated [Segment] reflecting the new member count
     * @throws IllegalStateException if the target segment is not found
     * @throws IllegalArgumentException if the target segment is not of type STATIC
     */
    suspend fun removeFromSegment(segmentId: UUID, removeSegmentId: UUID): Segment

    /**
     * Retrieves all profile IDs that belong to any of the specified segments,
     * returning a deduplicated set across all segments.
     *
     * @param segmentIds the UUIDs of the segments whose audiences should be merged
     * @return the deduplicated list of profile UUIDs
     */
    suspend fun getAudienceProfileIds(segmentIds: List<UUID>): List<UUID>

    /**
     * Retrieves a paginated slice of profile IDs belonging to any of the specified
     * segments, with stable ordering for safe batch iteration.
     *
     * @param segmentIds the UUIDs of the segments whose audiences should be merged
     * @param offset the number of profile IDs to skip
     * @param limit the maximum number of profile IDs to return
     * @return the deduplicated, ordered list of profile UUIDs
     */
    suspend fun getAudienceProfileIdsPaged(segmentIds: List<UUID>, offset: Long, limit: Int): List<UUID>

    /**
     * Returns the total number of distinct profiles across the specified segments.
     *
     * @param segmentIds the UUIDs of the segments
     * @return the total audience count
     */
    suspend fun getAudienceCount(segmentIds: List<UUID>): Long

    /**
     * Retrieves all segments that a specific profile belongs to, ordered by
     * when the profile was added (most recent first).
     *
     * @param profileId the UUID of the profile
     * @return the list of [Segment] instances the profile is a member of
     */
    suspend fun getSegmentsByProfileId(profileId: UUID): List<Segment>
}
