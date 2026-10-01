package bosca.content.transition.model

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Request to initiate a workflow state transition on a content item (either a collection or a metadata item).
 *
 * Exactly one of [collectionId] or [metadataId] must be provided to identify the target content item.
 * The transition moves the item to the workflow state identified by [stateId] with the given [status].
 *
 * @property collectionId identifier of the collection to transition; mutually exclusive with [metadataId]
 * @property metadataId identifier of the metadata item to transition; mutually exclusive with [collectionId]
 * @property version version of the metadata item to transition; only applicable when [metadataId] is set
 * @property supplementaryId optional identifier of a supplementary resource associated with this transition
 * @property restart whether to restart the transition if it has already been attempted
 * @property stateId the target workflow state to transition the content item into
 * @property status descriptive status string recorded for this transition step
 * @property stateValid optional timestamp indicating when the target state becomes valid; allows scheduling future transitions
 * @property configuration optional JSON payload providing transition-specific parameters to the transition jobs
 * @property languageTag BCP 47 language tag used to select a specific language variant of a collection for transition
 * @property allowProcessing when true, permits the transition to proceed even if the item is currently being processed
 */
@Serializable
data class BeginTransitionInput(
    /** Identifier of the collection to transition; mutually exclusive with [metadataId]. */
    @Contextual
    val collectionId: UUID? = null,
    /** Identifier of the metadata item to transition; mutually exclusive with [collectionId]. */
    @Contextual
    val metadataId: UUID? = null,
    /** Version of the metadata item to transition; only applicable when [metadataId] is set. */
    val version: Int? = null,
    /** Optional identifier of a supplementary resource associated with this transition. */
    @Contextual
    val supplementaryId: UUID? = null,
    /** Whether to restart the transition if it has already been attempted. */
    val restart: Boolean? = null,
    /** The target workflow state to transition the content item into. */
    val stateId: String,
    /** Descriptive status string recorded for this transition step. */
    val status: String,
    /** Optional timestamp indicating when the target state becomes valid; allows scheduling future transitions. */
    @Contextual
    val stateValid: OffsetDateTime? = null,
    /** Optional JSON payload providing transition-specific parameters to the transition jobs. */
    @Contextual
    val configuration: JsonElement? = null,
    /** BCP 47 language tag used to select a specific language variant of a collection for transition. */
    val languageTag: String? = null,
    /** When true, permits the transition to proceed even if the item is currently being processed. */
    val allowProcessing: Boolean = false
)