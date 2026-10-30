package bosca.cli.swarm

import kotlinx.serialization.Serializable

/** Atlas topology; the platform runner already consumes Git and pipeline queues by default. */
@Serializable
data class SwarmAtlas(
    /** Optional hostname under the site’s shared cookie domain. */
    val domain: String = "",
    val separateUi: Boolean = false,
    /** Uses the backend-only Git service for Git protocols at either public hostname. */
    val separateGit: Boolean = false,
    /** Uses the existing registry service for artifact protocols and UI file transfers. */
    val separateArtifacts: Boolean = false,
    val runnerEnabled: Boolean = false,
)
