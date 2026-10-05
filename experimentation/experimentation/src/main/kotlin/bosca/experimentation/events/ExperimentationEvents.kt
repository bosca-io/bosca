package bosca.experimentation.events

import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.experimentation.model.FlagStatus
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@JobEvent(
    jobs = [],
    displayName = "Feature Flag Created",
    description = "Fires when a feature flag is created."
)
@Serializable
class FeatureFlagCreated(val flagId: UUID, val flagKey: String) : Event

@JobEvent(
    jobs = [],
    displayName = "Feature Flag Updated",
    description = "Fires when a feature flag's definition changes, including salt regeneration."
)
@Serializable
class FeatureFlagUpdated(val flagId: UUID, val flagKey: String) : Event {

    override fun identityKey(): Any = flagId
}

@JobEvent(
    jobs = [],
    displayName = "Feature Flag Deleted",
    description = "Fires when a feature flag is deleted."
)
@Serializable
class FeatureFlagDeleted(val flagId: UUID, val flagKey: String) : Event

@JobEvent(
    jobs = [],
    displayName = "Feature Flag Status Changed",
    description = "Fires when a feature flag transitions between statuses (DRAFT, ACTIVE, ...)."
)
@Serializable
class FeatureFlagStatusChanged(val flagId: UUID, val flagKey: String, val status: FlagStatus) : Event

@JobEvent(
    jobs = [],
    displayName = "Experiment Variation Assigned",
    description = "Fires once per installation the first time it is assigned a variation in an experiment. " +
        "principalId identifies the signed-in owner when one is present."
)
@Serializable
class ExperimentVariationAssigned(
    val experimentId: UUID,
    val principalId: UUID?,
    val installationId: String,
    val variationKey: String,
) : Event
