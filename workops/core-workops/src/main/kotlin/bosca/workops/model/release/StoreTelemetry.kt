package bosca.workops.model.release

import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** A store target's live operational state, fetched on demand and never persisted in WorkOps. */
@Serializable
data class ReleaseStoreState(
    @Contextual val projectId: UUID,
    @Contextual val versionId: UUID,
    @Contextual val environmentId: UUID,
    val environmentKey: String,
    val store: String,
    val applicationId: String,
    val appVersion: String,
    val buildNumber: String? = null,
    val track: String? = null,
    val rolloutPercentage: Double? = null,
    val releaseState: String? = null,
    val reviewState: String? = null,
    val betaReviewState: String? = null,
    val buildProcessingState: String? = null,
    val phasedReleaseState: String? = null,
    val testFlightGroups: List<String> = emptyList(),
    val testFlightCrashFeedback: List<ReleaseStoreCrashFeedback> = emptyList(),
)

/** A TestFlight crash report attached to the selected App Store build. */
@Serializable
data class ReleaseStoreCrashFeedback(
    val id: String,
    val comment: String? = null,
    val email: String? = null,
    val deviceModel: String? = null,
    val osVersion: String? = null,
    val createdAt: String? = null,
)

/** One historical store observation ingested by an authored pipeline into analytics. */
@Serializable
data class ReleaseStoreObservation(
    val store: String,
    val applicationId: String,
    val appVersion: String,
    val telemetryType: String,
    @Contextual val observedAt: OffsetDateTime,
    val payload: JsonElement,
)

/** Live vendor state plus release-scoped analytics history for the Studio release cockpit. */
@Serializable
data class ReleaseStoreTelemetry(
    val states: List<ReleaseStoreState>,
    val observations: List<ReleaseStoreObservation>,
)
