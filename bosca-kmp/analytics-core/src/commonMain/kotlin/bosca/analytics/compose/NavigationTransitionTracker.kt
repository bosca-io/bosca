package bosca.analytics.compose

internal data class NavigationTransition(
    val from: String?,
    val to: String,
    val action: String,
    val depth: Int,
)

internal class NavigationTransitionTracker {
    private var paths = emptyList<String>()

    fun move(newPaths: List<String>): NavigationTransition? {
        if (newPaths.isEmpty() || paths == newPaths) return null
        val previous = paths
        val to = newPaths.last()
        val transition = NavigationTransition(
            from = previous.lastOrNull(),
            to = to,
            action = when {
                previous.isEmpty() -> "initial"
                newPaths.size == previous.size + 1 && newPaths.dropLast(1) == previous -> "push"
                newPaths.size < previous.size && previous.take(newPaths.size) == newPaths -> "pop"
                newPaths.size == previous.size && newPaths.dropLast(1) == previous.dropLast(1) -> "replace"
                else -> "reset"
            },
            depth = newPaths.size,
        )
        paths = newPaths.toList()
        return transition
    }
}
