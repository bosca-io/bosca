package bosca.cli.swarm.runtime

import org.testcontainers.containers.GenericContainer
import java.util.concurrent.Future

internal class SwarmTestDaemon(image: Future<String>) : GenericContainer<SwarmTestDaemon>(image)
