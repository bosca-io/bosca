package bosca.design.example

import bosca.design.model.NavigationItem
import kotlinx.serialization.Serializable

/** Caller-owned destinations, selection and list revisions for the route-tab consumer. */
@Serializable
class RouteTabsState(val id: String) {
    var updates: Int = 0
        private set
    var revision: Int = 0
        private set
    var mounted: Boolean = true
        private set
    var narrow: Boolean = false
        private set
    var changed: Boolean = false
        private set
    var longLabel: Boolean = false
        private set

    val items: List<NavigationItem>
        get() = listOf("Overview", "Activity", "History", "Members", "Settings", "Branches", "Pipelines", "Releases")
            .mapIndexed { index, label ->
                val selected = index == if (changed) 3 else 7
                NavigationItem(
                    if (longLabel && selected) "A very long current destination that still needs to remain reachable" else label,
                    "/navigation-target?item=${index + 1}",
                    selected,
                )
            }

    fun update() {
        updates++
    }

    fun resize() {
        narrow = !narrow
    }

    fun change() {
        changed = !changed
        revision++
    }

    fun lengthen() {
        longLabel = !longLabel
        revision++
    }

    fun toggle() {
        mounted = !mounted
    }
}
