package bosca.profile.attribute.events

import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@JobEvent(
    jobs = [],
    displayName = "Profile Attributes Added",
    description = "Fires when one or more new attributes are added to a profile. " +
        "`typeIds` lists the distinct attribute types added (e.g. bosca.profiles.email)."
)
@Serializable
class ProfileAttributesAdded(
    val profileId: UUID,
    val attributeIds: List<UUID>,
    val typeIds: List<String>,
) : Event {

    override fun identityKey(): Any = profileId
}

@JobEvent(
    jobs = [],
    displayName = "Profile Attributes Updated",
    description = "Fires when existing profile attributes are edited. " +
        "`typeIds` lists the distinct attribute types touched."
)
@Serializable
class ProfileAttributesUpdated(
    val profileId: UUID,
    val attributeIds: List<UUID>,
    val typeIds: List<String>,
) : Event {

    override fun identityKey(): Any = profileId
}

@JobEvent(
    jobs = [],
    displayName = "Profile Attribute Deleted",
    description = "Fires when a profile attribute is deleted."
)
@Serializable
class ProfileAttributeDeleted(
    val profileId: UUID,
    val attributeId: UUID,
    val typeId: String,
) : Event

@JobEvent(
    jobs = [],
    displayName = "Profile Attributes Verified",
    description = "Fires when a profile proves control of an attribute value (e.g. its email). " +
        "`source` names the proof method (email, google, admin, ...)."
)
@Serializable
class ProfileAttributesVerified(
    val profileId: UUID,
    val typeId: String,
    val source: String,
) : Event

@JobEvent(
    jobs = [],
    displayName = "Profile Attribute Type Created",
    description = "Fires when a new profile attribute type is defined."
)
@Serializable
class ProfileAttributeTypeCreated(val typeId: String) : Event

@JobEvent(
    jobs = [],
    displayName = "Profile Attribute Type Updated",
    description = "Fires when a profile attribute type definition is edited."
)
@Serializable
class ProfileAttributeTypeUpdated(val typeId: String) : Event

@JobEvent(
    jobs = [],
    displayName = "Profile Attribute Type Deleted",
    description = "Fires when a profile attribute type is deleted."
)
@Serializable
class ProfileAttributeTypeDeleted(val typeId: String) : Event
