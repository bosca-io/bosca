package bosca.profile.profile.events

import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.profile.model.Profile
import bosca.profile.profile.jobs.ProfileCleanupJob
import bosca.profile.profile.jobs.ProfileIndexJob
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

interface ProfileEvent : Event {

    val id: UUID
}

@JobEvent(jobs = [ProfileIndexJob::class])
@Serializable
class ProfileCreatedEvent(override val id: UUID) : ProfileEvent {

    constructor(profile: Profile) : this(profile.id)
}

@JobEvent(jobs = [ProfileIndexJob::class])
@Serializable
class ProfileUpdatedEvent(override val id: UUID) : ProfileEvent {

    constructor(profile: Profile) : this(profile.id)
}

@JobEvent(jobs = [ProfileIndexJob::class, ProfileCleanupJob::class])
@Serializable
class ProfileDeletedEvent(
    override val id: UUID,
    @Contextual val principalId: UUID? = null,
) : ProfileEvent {

    constructor(profile: Profile) : this(profile.id, profile.principal)
}

/**
 * Requests durable domain cleanup after an administrator removes a profile's principal.
 */
@JobEvent(jobs = [ProfileCleanupJob::class])
@Serializable
class ProfileUnlinkedEvent(
    override val id: UUID,
    @Contextual val principalId: UUID,
) : ProfileEvent
