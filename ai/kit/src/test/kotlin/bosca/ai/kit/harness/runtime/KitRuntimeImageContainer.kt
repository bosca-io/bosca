package bosca.ai.kit.harness.runtime

import org.testcontainers.containers.GenericContainer
import java.util.concurrent.Future

internal class KitRuntimeImageContainer(image: Future<String>) : GenericContainer<KitRuntimeImageContainer>(image)
