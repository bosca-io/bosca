package bosca.profile.organization.events

import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.profile.organization.jobs.OrganizationIndexJob
import bosca.profile.organization.model.Organization
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

interface OrganizationEvent : Event {

    val id: UUID
}

@JobEvent(jobs = [OrganizationIndexJob::class])
@Serializable
class OrganizationCreated(
    override val id: UUID
) : OrganizationEvent {

    constructor(organization: Organization) : this(
        id = organization.id
    )
}

@JobEvent(jobs = [OrganizationIndexJob::class])
@Serializable
class OrganizationUpdated(override val id: UUID) : OrganizationEvent {

    constructor(organization: Organization) : this(
        id = organization.id
    )
}

@JobEvent(jobs = [])
@Serializable
class OrganizationDeleted(override val id: UUID) : OrganizationEvent {

    constructor(organization: Organization) : this(
        id = organization.id
    )
}

@JobEvent(jobs = [])
@Serializable
class OrganizationDomainAdded(override val id: UUID) : OrganizationEvent {

    constructor(organization: Organization) : this(
        id = organization.id
    )
}

// HubSpot member association runs via the seeded "HubSpot: Member Added" pipeline triggered by this
// event (see integrations/hubspot HubSpotPipelinesInstaller) — no job is dispatched here.
@JobEvent(jobs = [])
@Serializable
class OrganizationMemberAdded(override val id: UUID, val memberId: UUID) : OrganizationEvent {

    constructor(organization: Organization, memberId: UUID) : this(
        id = organization.id,
        memberId = memberId
    )
}

// HubSpot member disassociation runs via the seeded "HubSpot: Member Removed" pipeline triggered by
// this event — no job is dispatched here.
@JobEvent(jobs = [])
@Serializable
class OrganizationMemberRemoved(override val id: UUID, val memberId: UUID) : OrganizationEvent {

    constructor(organization: Organization, memberId: UUID) : this(
        id = organization.id,
        memberId = memberId
    )
}