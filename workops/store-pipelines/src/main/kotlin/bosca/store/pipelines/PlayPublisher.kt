package bosca.store.pipelines

import java.time.Duration

/** The outcome of a Google Play publishing operation. */
data class PlayDeployResult(
    val versionCode: Long,
    val track: String,
    val userFraction: Double,
)

/** The current release selected on a Google Play track. */
data class PlayTrackStateResult(
    val track: String,
    val versionCodes: List<Long>,
    val status: String,
    val userFraction: Double,
)

/** A point-in-time Google Play vitals observation. Rates use Play's percentage unit. */
data class PlayVitalsResult(
    val crashRate: Double,
    val window: Duration,
    val versionCode: Long?,
    val anrRate: Double = 0.0,
)

/** One user-authored Google Play review. */
data class PlayReviewResult(
    val id: String,
    val rating: Int,
    val text: String,
    val author: String?,
    val locale: String?,
    val createdEpochSeconds: Long?,
)

/**
 * a seam over the Google Play Developer API (Android Publisher) so the
 * [GooglePlayDeployTarget] adapter's logic is fully unit-testable without the concrete API. The
 * concrete implementation ([AndroidPublisherPlayPublisher]) performs the real edit / upload / track /
 * commit calls; it is the one class that can't be exercised without live Play credentials.
 */
interface PlayPublisher {

    /** Uploads [bundlePath]'s `.aab` and rolls it out to [track] at [userFraction] (0.0–1.0), committing the edit. */
    suspend fun deployBundle(
        credentialJson: String,
        packageName: String,
        bundlePath: String,
        track: String,
        userFraction: Double,
        expectedVersionCode: Long? = null,
    ): PlayDeployResult

    /** Uploads a bundle and attaches required BCP-47 localized Play release notes to its track release. */
    suspend fun deployBundleWithReleaseNotes(
        credentialJson: String,
        packageName: String,
        bundlePath: String,
        track: String,
        userFraction: Double,
        expectedVersionCode: Long? = null,
        releaseNotes: Map<String, String>,
    ): PlayDeployResult

    /** Sets the current release on [track] to [userFraction] (1.0 completes the rollout) without re-uploading a bundle. */
    suspend fun setRollout(
        credentialJson: String,
        packageName: String,
        track: String,
        userFraction: Double,
        versionCode: Long? = null,
    ): PlayDeployResult

    /** Halts the in-progress staged rollout on [track]. */
    suspend fun halt(
        credentialJson: String,
        packageName: String,
        track: String,
        versionCode: Long? = null,
    ): PlayDeployResult

    /** Reads the currently selected release on [track] without changing or committing an edit. */
    suspend fun trackState(
        credentialJson: String,
        packageName: String,
        track: String,
        versionCode: Long? = null,
    ): PlayTrackStateResult

    /** Reads crash and ANR rates over [window], optionally scoped to [versionCode]. */
    suspend fun vitals(
        credentialJson: String,
        packageName: String,
        versionCode: Long?,
        window: Duration,
    ): PlayVitalsResult

    /** Reads the highest crash-rate sample in [window], optionally scoped to [versionCode]. */
    suspend fun crashRate(
        credentialJson: String,
        packageName: String,
        versionCode: Long?,
        window: Duration,
    ): PlayVitalsResult = vitals(credentialJson, packageName, versionCode, window)

    /** Reads the latest user reviews for [packageName]. */
    suspend fun reviews(
        credentialJson: String,
        packageName: String,
        maxResults: Int = 50,
    ): List<PlayReviewResult>
}

/** The only conversion from the release YAML's 0–100 percentage to Play's 0–1 fraction. */
fun playUserFraction(rolloutPercentage: Double): Double {
    require(rolloutPercentage.isFinite() && rolloutPercentage in 0.0..100.0) {
        "rolloutPercentage must be between 0 and 100, got $rolloutPercentage"
    }
    return rolloutPercentage / 100.0
}
