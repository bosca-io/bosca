package bosca.workops.ui

import kotlinx.serialization.Serializable

/** The three existing localized store release-note fields. */
@Serializable
data class WorkOpsReleaseNoteVariant(
    val locale: String,
    val playReleaseNotes: String,
    val appStoreWhatsNew: String,
    val testFlightWhatToTest: String,
)
