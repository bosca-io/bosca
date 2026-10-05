package bosca.experimentation.model

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Audit trail entry for the rollout controller's decisions.
 *
 * One row is written on every controller wake-up, even when the
 * decision is [RolloutPolicyAction.HELD] — a held event is the only
 * record that the controller actually ran and chose not to act, which
 * is what operators need to see when a rollout "didn't advance but I
 * don't know why". Old/new weight snapshots are stored as opaque
 * JSONB so replaying a decision does not require loading the flag's
 * historical state; the decider's input is self-contained on the row.
 *
 * @property action discriminator — which of the four controller
 *           actions fired (see [RolloutPolicyAction]).
 * @property reason human-readable description of the decision. The
 *           controller writes strings like
 *           "verdict=SHIP advanced 25% → 50%" or
 *           "verdict=HALT guardrail 'page-load' regressed -2.3%".
 *           Not parsed — the machine-readable counterpart is
 *           [oldWeights] / [newWeights].
 * @property oldWeights snapshot of `variationKey -> weight` as they
 *           were before the controller's write. Null logically when
 *           the action is HELD without a write, but we persist the
 *           current weights there too so the audit row is always a
 *           complete snapshot.
 * @property newWeights snapshot of `variationKey -> weight` after the
 *           controller's write. Equals [oldWeights] for HELD events.
 */
@BatchKey("id")
@Serializable
data class RolloutPolicyEvent(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("experiment_id")
    @Contextual
    val experimentId: UUID,
    val action: RolloutPolicyAction,
    val reason: String,
    @ColumnName("old_weights")
    @Contextual
    val oldWeights: JsonElement,
    @ColumnName("new_weights")
    @Contextual
    val newWeights: JsonElement,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
)
