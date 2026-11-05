package bosca.ai.kit.harness.runtime

/** HTTP rejection without copying potentially sensitive controller response bodies into history. */
class KitSwarmControllerException(val status: Int) : IllegalStateException("Swarm controller request failed: HTTP $status. Review its connection and approved profile.")
