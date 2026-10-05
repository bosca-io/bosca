package bosca.segmentation.events

import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@JobEvent(
    jobs = [],
    displayName = "Segment Created",
    description = "Fires when a segment is created."
)
@Serializable
class SegmentCreated(val segmentId: UUID, val name: String) : Event

@JobEvent(
    jobs = [],
    displayName = "Segment Updated",
    description = "Fires when a segment's definition is edited."
)
@Serializable
class SegmentUpdated(val segmentId: UUID, val name: String) : Event {

    override fun identityKey(): Any = segmentId
}

@JobEvent(
    jobs = [],
    displayName = "Segment Deleted",
    description = "Fires when a segment and its membership are deleted."
)
@Serializable
class SegmentDeleted(val segmentId: UUID) : Event

@JobEvent(
    jobs = [],
    displayName = "Segment Members Added",
    description = "Fires when profiles are explicitly added to a static segment; carries the exact profile ids. " +
        "Query-driven membership changes fire Segment Evaluated instead."
)
@Serializable
class SegmentMembersAdded(val segmentId: UUID, val profileIds: List<UUID>) : Event {

    override fun identityKey(): Any = segmentId
}

@JobEvent(
    jobs = [],
    displayName = "Segment Members Removed",
    description = "Fires when profiles are explicitly removed from a segment; carries the exact profile ids. " +
        "Query-driven membership changes fire Segment Evaluated instead."
)
@Serializable
class SegmentMembersRemoved(val segmentId: UUID, val profileIds: List<UUID>) : Event {

    override fun identityKey(): Any = segmentId
}

@JobEvent(
    jobs = [],
    displayName = "Segment Evaluated",
    description = "Fires when a segment's membership is recomputed in bulk — dynamic evaluation (including the " +
        "scheduled refresh job), populate-from-query, remove-from-query, and segment subtraction. " +
        "Carries the resulting member count; read the membership by segmentId for the actual profiles."
)
@Serializable
class SegmentEvaluated(val segmentId: UUID, val memberCount: Long) : Event {

    override fun identityKey(): Any = segmentId
}
