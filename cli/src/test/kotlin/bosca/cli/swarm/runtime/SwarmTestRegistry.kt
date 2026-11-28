package bosca.cli.swarm.runtime

import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

internal class SwarmTestRegistry : GenericContainer<SwarmTestRegistry>(DockerImageName.parse("registry:2.8.3"))
