package bosca.cli.swarm

import kotlinx.serialization.Serializable

/** Local image retention; registry content and persistent service data are never removed. */
@Serializable
data class SwarmImageRetention(
    /** Reclaim retired images and their stopped Swarm containers after a successful deployment. */
    val enabled: Boolean = true,
    /** Required free space on Docker, containerd and deployment filesystems before pulling images. */
    val minimumFreeGiB: Int = 4,
)
