package bosca.workops.ui

import kotlinx.serialization.Serializable

/** A staged version retains its reviewed identity and the result of its independent bundle write. */
@Serializable
data class WorkOpsReleaseComponentChoice(
    val projectId: String,
    val projectKey: String,
    val projectName: String,
    val versionId: String,
    val versionName: String,
    val bundled: Boolean = false,
    val error: String = "",
)
