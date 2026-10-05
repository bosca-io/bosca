package bosca.content.collection.events

import bosca.content.collection.jobs.AutoAssignCollectionsJob
import bosca.content.collection.jobs.CollectionDeleteFromIndexJob
import bosca.content.collection.jobs.CollectionIndexJob
import bosca.content.collection.jobs.CollectionParentItemCacheInvalidationJob
import bosca.content.collection.jobs.CollectionLanguageVariantMetadataRelationshipAddedJob
import bosca.content.collection.jobs.CollectionLanguageVariantMetadataRelationshipMergedJob
import bosca.content.collection.jobs.CollectionLanguageVariantMetadataRelationshipRemovedJob
import bosca.content.collection.jobs.CollectionMetadataItemAddedJob
import bosca.content.collection.jobs.CollectionMetadataItemRemovedJob
import bosca.content.collection.jobs.CollectionMetadataRelationshipAddedJob
import bosca.content.collection.jobs.CollectionMetadataRelationshipMergedJob
import bosca.content.collection.jobs.CollectionMetadataRelationshipRemovedJob
import bosca.content.collection.jobs.CollectionProcessContentJob
import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionLanguageVariant
import bosca.content.collection.model.CollectionLanguageVariantMetadataRelationship
import bosca.content.collection.model.CollectionMetadataRelationship
import bosca.content.collection.model.CollectionSupplementary
import bosca.content.collection.model.ICollection
import bosca.content.metadata.model.Metadata
import bosca.content.transition.jobs.CollectionTransitionJob
import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

interface CollectionEvent : Event {

    val id: UUID
    val supplementaryId: UUID?

    override fun identityKey(): Any = id to supplementaryId
}

const val COLLECTION_CREATED_CHANNEL = "bosca.content.collection.created"
const val COLLECTION_UPDATED_CHANNEL = "bosca.content.collection.updated"
const val COLLECTION_STATE_CHANNEL = "bosca.content.collection.state"

@JobEvent(jobs = [CollectionIndexJob::class, CollectionProcessContentJob::class], pubsubChannel = COLLECTION_CREATED_CHANNEL)
@Serializable
class CollectionCreated(override val id: UUID) : CollectionEvent {

    override val supplementaryId: UUID? = null

    constructor(collection: Collection) : this(
        id = collection.id,
    )
}

@JobEvent(jobs = [CollectionIndexJob::class, CollectionProcessContentJob::class, CollectionParentItemCacheInvalidationJob::class], pubsubChannel = COLLECTION_UPDATED_CHANNEL)
@Serializable
class CollectionUpdated(override val id: UUID, val languageTag: String? = null) : CollectionEvent {

    override val supplementaryId: UUID? = null

    constructor(collection: ICollection) : this(
        id = collection.id,
        languageTag = if (collection is CollectionLanguageVariant) collection.languageTag else null
    )
}

@JobEvent(jobs = [CollectionMetadataRelationshipAddedJob::class])
@Serializable
class CollectionMetadataRelationshipAdded(override val id: UUID, val relationship: CollectionMetadataRelationship) : CollectionEvent {

    override val supplementaryId: UUID? = null

    constructor(collection: Collection, relationship: CollectionMetadataRelationship) : this(
        id = collection.id,
        relationship = relationship
    )
}

@JobEvent(jobs = [CollectionLanguageVariantMetadataRelationshipAddedJob::class])
@Serializable
class CollectionLanguageVariantMetadataRelationshipAdded(
    override val id: UUID,
    val languageTag: String,
    val relationship: CollectionLanguageVariantMetadataRelationship
) : CollectionEvent {

    override val supplementaryId: UUID? = null

    constructor(variant: CollectionLanguageVariant, relationship: CollectionLanguageVariantMetadataRelationship) : this(
        id = variant.id,
        languageTag = variant.languageTag,
        relationship = relationship
    )
}

@JobEvent(jobs = [CollectionMetadataRelationshipMergedJob::class])
@Serializable
class CollectionMetadataRelationshipMerged(override val id: UUID, val relationship: CollectionMetadataRelationship) : CollectionEvent {

    override val supplementaryId: UUID? = null

    constructor(collection: Collection, relationship: CollectionMetadataRelationship) : this(
        id = collection.id,
        relationship = relationship
    )
}

@JobEvent(jobs = [CollectionLanguageVariantMetadataRelationshipMergedJob::class])
@Serializable
class CollectionLanguageVariantMetadataRelationshipMerged(
    override val id: UUID,
    val languageTag: String,
    val relationship: CollectionLanguageVariantMetadataRelationship
) : CollectionEvent {

    override val supplementaryId: UUID? = null

    constructor(variant: CollectionLanguageVariant, relationship: CollectionLanguageVariantMetadataRelationship) : this(
        id = variant.id,
        languageTag = variant.languageTag,
        relationship = relationship
    )
}

@JobEvent(jobs = [CollectionMetadataRelationshipRemovedJob::class])
@Serializable
class CollectionMetadataRelationshipRemoved(override val id: UUID, val relationship: CollectionMetadataRelationship) : CollectionEvent {

    override val supplementaryId: UUID? = null

    constructor(collection: Collection, relationship: CollectionMetadataRelationship) : this(
        id = collection.id,
        relationship = relationship
    )
}

@JobEvent(jobs = [CollectionLanguageVariantMetadataRelationshipRemovedJob::class])
@Serializable
class CollectionLanguageVariantMetadataRelationshipRemoved(
    override val id: UUID,
    val languageTag: String,
    val relationship: CollectionLanguageVariantMetadataRelationship
) : CollectionEvent {

    override val supplementaryId: UUID? = null

    constructor(variant: CollectionLanguageVariant, relationship: CollectionLanguageVariantMetadataRelationship) : this(
        id = variant.id,
        languageTag = variant.languageTag,
        relationship = relationship
    )
}

@JobEvent(jobs = [CollectionMetadataItemAddedJob::class])
@Serializable
class CollectionMetadataItemAdded(override val id: UUID, val metadataId: UUID) : CollectionEvent {

    override val supplementaryId: UUID? = null

    constructor(collection: Collection, metadata: Metadata) : this(
        id = collection.id,
        metadataId = metadata.id
    )
}

@JobEvent(jobs = [CollectionMetadataItemRemovedJob::class])
@Serializable
class CollectionMetadataItemRemoved(override val id: UUID, val metadataId: UUID) : CollectionEvent {

    override val supplementaryId: UUID? = null

    constructor(collection: Collection, metadata: Metadata) : this(
        id = collection.id,
        metadataId = metadata.id
    )
}

@JobEvent(jobs = [CollectionIndexJob::class], pubsubChannel = COLLECTION_UPDATED_CHANNEL)
@Serializable
class CollectionSupplementaryAdded(override val id: UUID, override val supplementaryId: UUID) : CollectionEvent {

    constructor(supplementary: CollectionSupplementary) : this(
        id = supplementary.collectionId,
        supplementaryId = supplementary.id
    )
}

@JobEvent(jobs = [CollectionIndexJob::class], pubsubChannel = COLLECTION_UPDATED_CHANNEL)
@Serializable
class CollectionSupplementaryUpdated(override val id: UUID, override val supplementaryId: UUID) : CollectionEvent {

    constructor(supplementary: CollectionSupplementary) : this(
        id = supplementary.collectionId,
        supplementaryId = supplementary.id
    )
}

@JobEvent(jobs = [CollectionIndexJob::class, CollectionParentItemCacheInvalidationJob::class], pubsubChannel = COLLECTION_STATE_CHANNEL)
@Serializable
class CollectionStateChangedComplete(override val id: UUID, val languageTag: String? = null) : CollectionEvent {

    override val supplementaryId: UUID? = null

    constructor(collection: ICollection) : this(
        id = collection.id,
        languageTag = if (collection is CollectionLanguageVariant) collection.languageTag else null
    )
}

@JobEvent(jobs = [CollectionParentItemCacheInvalidationJob::class], pubsubChannel = COLLECTION_STATE_CHANNEL)
@Serializable
class CollectionStateChanged(override val id: UUID, val languageTag: String? = null) : CollectionEvent {

    override val supplementaryId: UUID? = null

    constructor(collection: ICollection) : this(
        id = collection.id,
        languageTag = if (collection is CollectionLanguageVariant) collection.languageTag else null
    )
}

@JobEvent(jobs = [CollectionDeleteFromIndexJob::class, CollectionParentItemCacheInvalidationJob::class], pubsubChannel = COLLECTION_UPDATED_CHANNEL)
@Serializable
class CollectionDeleted(override val id: UUID) : CollectionEvent {

    override val supplementaryId: UUID? = null

    constructor(collection: Collection) : this(
        id = collection.id
    )
}

@JobEvent(jobs = [CollectionDeleteFromIndexJob::class], pubsubChannel = COLLECTION_UPDATED_CHANNEL)
@Serializable
class CollectionLanguageVariantDeleted(override val id: UUID, val languageTag: String) : CollectionEvent {

    override val supplementaryId: UUID? = null
}

@JobEvent(jobs = [CollectionTransitionJob::class, AutoAssignCollectionsJob::class], pubsubChannel = COLLECTION_UPDATED_CHANNEL)
@Serializable
class CollectionSetReady(override val id: UUID, val languageTag: String? = null) : CollectionEvent {

    override val supplementaryId: UUID? = null

    constructor(collection: ICollection) : this(
        id = collection.id,
        languageTag = if (collection is CollectionLanguageVariant) collection.languageTag else null
    )
}

@JobEvent(jobs = [CollectionTransitionJob::class], pubsubChannel = COLLECTION_UPDATED_CHANNEL)
@Serializable
class CollectionSetNotReady(override val id: UUID, val languageTag: String? = null) : CollectionEvent {

    override val supplementaryId: UUID? = null

    constructor(collection: ICollection) : this(
        id = collection.id,
        languageTag = if (collection is CollectionLanguageVariant) collection.languageTag else null
    )
}

@JobEvent(jobs = [CollectionIndexJob::class], pubsubChannel = COLLECTION_UPDATED_CHANNEL)
@Serializable
class CollectionSupplementedUpdatedEvent(override val id: UUID, override val supplementaryId: UUID) : CollectionEvent {

    constructor(collection: Collection, supplementaryId: UUID) : this(
        id = collection.id,
        supplementaryId = supplementaryId
    )
}

@JobEvent(jobs = [], pubsubChannel = COLLECTION_UPDATED_CHANNEL)
@Serializable
class CollectionLockedEvent(override val id: UUID) : CollectionEvent {

    override val supplementaryId: UUID? = null

    constructor(collection: Collection) : this(
        id = collection.id
    )
}

@JobEvent(jobs = [], pubsubChannel = COLLECTION_UPDATED_CHANNEL)
@Serializable
class CollectionUnlockedEvent(override val id: UUID) : CollectionEvent {

    override val supplementaryId: UUID? = null

    constructor(collection: Collection) : this(
        id = collection.id
    )
}