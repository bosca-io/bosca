package bosca.git.jobs

import bosca.queue.annotations.IJobDefinition
import kotlinx.serialization.Serializable

/**
 * Job payload for physically reaping DFS packs that GC or thin-pack compaction
 * soft-deleted. The replaced packs' object-storage files are removed only after
 * their grace window elapses, so an in-flight clone/fetch never loses a pack out
 * from under it. Dispatched by an hourly scheduled trigger.
 */
@Serializable
class DfsPackReapJob : IJobDefinition
