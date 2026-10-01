package bosca.content.metadata.events

import bosca.content.collection.jobs.AutoAssignCollectionsJob
import bosca.content.collection.jobs.MetadataParentItemCacheInvalidationJob
import bosca.content.metadata.jobs.MetadataDeleteFromIndexJob
import bosca.content.metadata.jobs.MetadataIndexJob
import bosca.content.metadata.jobs.MetadataProcessContentJob
import bosca.content.metadata.jobs.MetadataRelationshipAddedJob
import bosca.content.metadata.jobs.MetadataRelationshipMergedJob
import bosca.content.metadata.jobs.MetadataRelationshipRemovedJob
import bosca.content.metadata.jobs.MetadataSyncStateJob
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataRelationship
import bosca.content.transition.jobs.MetadataTransitionJob
import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

interface MetadataEvent : Event {

    val id: UUID
    val version: Int?
    val supplementaryId: UUID?

    override fun identityKey(): Any = Triple(id, version, supplementaryId)
}

const val METADATA_CREATED_CHANNEL = "bosca.content.metadata.created"
const val METADATA_UPDATED_CHANNEL = "bosca.content.metadata.updated"
const val METADATA_RELATIONSHIP_ADDED_CHANNEL = "bosca.content.metadata.relationship.added"
const val METADATA_STATE_CHANNEL = "bosca.content.metadata.state"

@JobEvent(jobs = [MetadataIndexJob::class], pubsubChannel = METADATA_CREATED_CHANNEL)
@Serializable
class MetadataCreated(override val id: UUID, override val version: Int) : MetadataEvent {

    override val supplementaryId: UUID? = null

    constructor(metadata: Metadata) : this(
        id = metadata.id,
        version = metadata.version
    )
}

@JobEvent(jobs = [MetadataIndexJob::class, MetadataProcessContentJob::class, MetadataSyncStateJob::class, MetadataParentItemCacheInvalidationJob::class], pubsubChannel = METADATA_UPDATED_CHANNEL)
@Serializable
class MetadataUpdated(override val id: UUID, override val version: Int = 0) : MetadataEvent {

    override val supplementaryId: UUID? = null

    constructor(metadata: Metadata) : this(
        id = metadata.id,
        version = metadata.version
    )
}

@JobEvent(jobs = [MetadataRelationshipAddedJob::class, MetadataSyncStateJob::class], pubsubChannel = METADATA_RELATIONSHIP_ADDED_CHANNEL)
@Serializable
class MetadataRelationshipAdded(override val id: UUID, override val version: Int = 0, val relationship: MetadataRelationship) : MetadataEvent {

    override val supplementaryId: UUID? = null

    constructor(metadata: Metadata, relationship: MetadataRelationship) : this(
        id = metadata.id,
        version = metadata.version,
        relationship = relationship
    )
}

@JobEvent(jobs = [MetadataRelationshipMergedJob::class, MetadataSyncStateJob::class])
@Serializable
class MetadataRelationshipMerged(override val id: UUID, override val version: Int = 0, val relationship: MetadataRelationship) : MetadataEvent {

    override val supplementaryId: UUID? = null

    constructor(metadata: Metadata, relationship: MetadataRelationship) : this(
        id = metadata.id,
        version = metadata.version,
        relationship = relationship
    )
}

@JobEvent(jobs = [MetadataRelationshipRemovedJob::class])
@Serializable
class MetadataRelationshipRemoved(override val id: UUID, override val version: Int = 0, val relationship: MetadataRelationship) : MetadataEvent {

    override val supplementaryId: UUID? = null

    constructor(metadata: Metadata, relationship: MetadataRelationship) : this(
        id = metadata.id,
        version = metadata.version,
        relationship = relationship
    )
}

@JobEvent(jobs = [MetadataSyncStateJob::class, MetadataParentItemCacheInvalidationJob::class], pubsubChannel = METADATA_STATE_CHANNEL)
@Serializable
class MetadataStateChanged(override val id: UUID, override val version: Int) : MetadataEvent {

    override val supplementaryId: UUID? = null

    constructor(metadata: Metadata) : this(
        id = metadata.id,
        version = metadata.version
    )
}

@JobEvent(jobs = [MetadataIndexJob::class, MetadataSyncStateJob::class, MetadataParentItemCacheInvalidationJob::class], pubsubChannel = METADATA_STATE_CHANNEL)
@Serializable
class MetadataStateChangeComplete(override val id: UUID, override val version: Int) : MetadataEvent {

    override val supplementaryId: UUID? = null

    constructor(metadata: Metadata) : this(
        id = metadata.id,
        version = metadata.version
    )
}

@JobEvent(jobs = [])
@Serializable
class MetadataSetTraits(override val id: UUID, override val version: Int) : MetadataEvent {

    override val supplementaryId: UUID? = null

    constructor(metadata: Metadata) : this(
        id = metadata.id,
        version = metadata.version
    )
}

@JobEvent(jobs = [MetadataIndexJob::class])
@Serializable
class MetadataUploadCleared(override val id: UUID, override val version: Int) : MetadataEvent {

    override val supplementaryId: UUID? = null

    constructor(metadata: Metadata) : this(
        id = metadata.id,
        version = metadata.version
    )
}

@JobEvent(jobs = [MetadataDeleteFromIndexJob::class, MetadataParentItemCacheInvalidationJob::class], pubsubChannel = METADATA_UPDATED_CHANNEL)
@Serializable
class MetadataDeleted(override val id: UUID, override val version: Int) : MetadataEvent {

    override val supplementaryId: UUID? = null

    constructor(metadata: Metadata) : this(
        id = metadata.id,
        version = metadata.version
    )
}

@JobEvent(jobs = [AutoAssignCollectionsJob::class], pubsubChannel = METADATA_UPDATED_CHANNEL)
@Serializable
class MetadataSetReady(override val id: UUID, override val version: Int) : MetadataEvent {

    override val supplementaryId: UUID? = null

    constructor(metadata: Metadata) : this(
        id = metadata.id,
        version = metadata.version
    )
}

@JobEvent(jobs = [])
@Serializable
class MetadataSetNotReady(override val id: UUID, override val version: Int) : MetadataEvent {

    override val supplementaryId: UUID? = null

    constructor(metadata: Metadata) : this(
        id = metadata.id,
        version = metadata.version
    )
}

@JobEvent(jobs = [MetadataIndexJob::class, MetadataProcessContentJob::class], pubsubChannel = METADATA_UPDATED_CHANNEL)
@Serializable
class MetadataUploadedEvent(override val id: UUID, override val version: Int) : MetadataEvent {

    override val supplementaryId: UUID? = null

    constructor(metadata: Metadata) : this(
        id = metadata.id,
        version = metadata.version
    )
}

@JobEvent(jobs = [MetadataIndexJob::class, MetadataSyncStateJob::class], pubsubChannel = METADATA_UPDATED_CHANNEL)
@Serializable
class MetadataSupplementedUpdatedEvent(override val id: UUID, override val version: Int, override val supplementaryId: UUID) : MetadataEvent {

    constructor(metadata: Metadata, supplementaryId: UUID) : this(
        id = metadata.id,
        version = metadata.version,
        supplementaryId = supplementaryId
    )
}

@JobEvent(jobs = [], pubsubChannel = METADATA_UPDATED_CHANNEL)
@Serializable
class MetadataLockedEvent(override val id: UUID, override val version: Int) : MetadataEvent {

    override val supplementaryId: UUID? = null

    constructor(metadata: Metadata) : this(
        id = metadata.id,
        version = metadata.version
    )
}

@JobEvent(jobs = [], pubsubChannel = METADATA_UPDATED_CHANNEL)
@Serializable
class MetadataUnlockedEvent(override val id: UUID, override val version: Int) : MetadataEvent {

    override val supplementaryId: UUID? = null

    constructor(metadata: Metadata) : this(
        id = metadata.id,
        version = metadata.version
    )
}