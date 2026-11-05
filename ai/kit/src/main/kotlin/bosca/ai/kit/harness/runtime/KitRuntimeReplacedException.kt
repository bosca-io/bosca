package bosca.ai.kit.harness.runtime

/** A control endpoint reached a supervisor other than the one admitted for this generation. */
class KitRuntimeReplacedException(val generation: Long) : IllegalStateException("The harness process was replaced. Local changes since the last archive may be lost. Any uncertain turn will not be replayed.")
