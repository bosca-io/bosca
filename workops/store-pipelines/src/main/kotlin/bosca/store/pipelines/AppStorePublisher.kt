package bosca.store.pipelines

/** The outcome of an App Store Connect operation. */
data class AppStoreSubmitResult(
    val appVersion: String,
    val buildNumber: String,
    /** App Store Connect review/version state, e.g. `WAITING_FOR_REVIEW`, `PENDING_DEVELOPER_RELEASE`. */
    val state: String,
)

/** Live App Store Connect state for one marketing version and its selected build. */
data class AppStoreStateResult(
    val appVersion: String,
    val buildNumber: String?,
    val reviewState: String,
    val betaReviewState: String?,
    val buildProcessingState: String?,
    val phasedReleaseState: String?,
    val testFlightGroups: List<String>,
    val testFlightCrashFeedback: List<AppStoreCrashFeedbackResult> = emptyList(),
)

/** One TestFlight beta-tester crash report exposed by App Store Connect. */
data class AppStoreCrashFeedbackResult(
    val id: String,
    val comment: String?,
    val email: String?,
    val deviceModel: String?,
    val osVersion: String?,
    val createdAt: String?,
)

/** One App Store customer review. */
data class AppStoreReviewResult(
    val id: String,
    val rating: Int,
    val title: String?,
    val body: String,
    val reviewer: String?,
    val territory: String?,
    val createdAt: String?,
)

enum class AppStoreReviewMode { BETA, APP_STORE }

/**
 * a seam over the App Store Connect API so the [AppStoreDeployTarget] adapter's
 * logic is fully unit-testable without the concrete API. The concrete implementation
 * ([AppStoreConnectPublisher]) does the real JWT-authenticated REST calls and its request paths are
 * tested against a mock server; only live-credential verification remains external. Note: Apple's
 * **review** is not started by [reviewState] — that method feeds the ordinary review gate (R9).
 */
interface AppStorePublisher {

    /** Attaches [buildNumber] to a [appVersion] version and submits it for review, optionally enabling phased release. */
    suspend fun submitForReview(
        credentialJson: String,
        bundleId: String,
        appVersion: String,
        buildNumber: String,
        phasedRelease: Boolean,
    ): AppStoreSubmitResult

    /** Writes localized App Store "What's New" fields before attaching and submitting the build. */
    suspend fun submitForReviewWithReleaseNotes(
        credentialJson: String,
        bundleId: String,
        appVersion: String,
        buildNumber: String,
        phasedRelease: Boolean,
        whatsNew: Map<String, String>,
    ): AppStoreSubmitResult

    /** Assigns an already-processed build to every named TestFlight group. */
    suspend fun assignBetaGroups(
        credentialJson: String,
        bundleId: String,
        appVersion: String,
        buildNumber: String,
        groupNames: List<String>,
    ): AppStoreSubmitResult

    /** Writes localized TestFlight "What to Test" metadata before assigning the build to groups. */
    suspend fun assignBetaGroupsWithReleaseNotes(
        credentialJson: String,
        bundleId: String,
        appVersion: String,
        buildNumber: String,
        groupNames: List<String>,
        whatToTest: Map<String, String>,
    ): AppStoreSubmitResult

    /** Removes an already-processed build from every named TestFlight group. */
    suspend fun removeBetaGroups(
        credentialJson: String,
        bundleId: String,
        appVersion: String,
        buildNumber: String,
        groupNames: List<String>,
    ): AppStoreSubmitResult

    /** Halts (pauses) the phased release of [appVersion] — the App Store's closest thing to a rollback. */
    suspend fun haltPhasedRelease(
        credentialJson: String,
        bundleId: String,
        appVersion: String,
    ): AppStoreSubmitResult

    /** The current beta- or full-App-Store review state of the selected build/version. */
    suspend fun reviewState(
        credentialJson: String,
        bundleId: String,
        appVersion: String,
        buildNumber: String,
        mode: AppStoreReviewMode,
    ): String

    /** Reads the current review, phased-release, processing, and TestFlight-group state. */
    suspend fun state(
        credentialJson: String,
        bundleId: String,
        appVersion: String,
        buildNumber: String? = null,
    ): AppStoreStateResult

    /** Reads the latest customer reviews for [bundleId]. */
    suspend fun reviews(
        credentialJson: String,
        bundleId: String,
        maxResults: Int = 50,
    ): List<AppStoreReviewResult>
}
