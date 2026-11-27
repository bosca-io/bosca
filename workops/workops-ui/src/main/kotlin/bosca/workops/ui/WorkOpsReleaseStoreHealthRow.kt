package bosca.workops.ui

import bosca.workops.ui.graphql.WorkOpsReleaseStoreTelemetryData.WorkOps.MultiRepo.ReleaseStoreTelemetry.States as StoreState
import kotlinx.serialization.Serializable
import java.util.Locale

/** A release target joins history by store, application and marketing version, never by position. */
@Serializable
data class WorkOpsReleaseStoreHealthRow(
    val state: StoreState,
    val projectLabel: String,
    val observations: List<WorkOpsStoreObservation>,
) {
    val key get() = listOf(state.projectId, state.versionId, state.environmentId, state.store, state.applicationId).joinToString(":")
    val storeLabel get() = when (state.store) { "GOOGLE_PLAY" -> "Google Play"; "APP_STORE" -> "App Store"; else -> state.store }
    val vitals get() = observations.firstOrNull { it.type == "store.vitals" }
    val reviewsObservation get() = observations.firstOrNull { it.type == "store.reviews" }
    val reviews get() = reviewsObservation?.reviews.orEmpty()
    val crashRateLabel get() = vitals?.number("crashRate")?.let { workOpsStoreNumber(it, 3) + "%" } ?: "Unavailable"
    val anrRateLabel get() = vitals?.number("anrRate")?.let { workOpsStoreNumber(it, 3) + "%" } ?: "Unavailable"
    val rolloutLabel get() = state.rolloutPercentage?.takeIf { it.isFinite() }?.let { workOpsStoreNumber(it, 2) + "%" } ?: "Unavailable"
    val crashPoints get() = observations.filter { it.type == "store.vitals" }.mapNotNull { observation -> observation.number("crashRate")?.let { observation.instant to it } }
    val ratingPoints get() = observations.filter { it.type == "store.reviews" }.mapNotNull { observation ->
        observation.reviews.mapNotNull { it.rating }.takeIf { it.isNotEmpty() }?.let { observation.instant to it.average() }
    }
    val ratingLabel get() = ratingPoints.maxByOrNull { it.first }?.second?.let { workOpsStoreNumber(it, 2) } ?: "Unavailable"
    val ratingTime get() = ratingPoints.maxByOrNull { it.first }?.first?.toString().orEmpty()
    val crashChart get() = workOpsHealthChart("Crash rate", crashPoints, percent = true, color = "var(--err)")
    val ratingChart get() = workOpsHealthChart("Average review rating", ratingPoints, color = "var(--warn)")
}

internal fun workOpsStoreNumber(value: Double, places: Int) = String.format(Locale.ROOT, "%.$places" + "f", value)
