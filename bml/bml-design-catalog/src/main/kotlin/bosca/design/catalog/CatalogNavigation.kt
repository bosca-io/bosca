package bosca.design.catalog

import bosca.design.model.NavigationItem
import bosca.design.model.SubsystemNavigationItem
import kotlinx.serialization.Serializable

/** Caller-owned destinations and preservation revisions for the route navigation examples. */
@Serializable
class CatalogNavigation(val id: String) {
    var narrow: Boolean = true
        private set
    var visible: Boolean = true
        private set
    var changed: Boolean = false
        private set
    var longLabel: Boolean = false
        private set
    var alternate: Boolean = false
        private set
    var revision: Int = 0
        private set
    var notice: String = ""
        private set
    var noticeSequence: Int = 0
        private set

    fun items(): List<NavigationItem> {
        val routes = if (alternate) destinations.take(4) else destinations
        val selected = if (changed) 2 else routes.lastIndex
        return routes.mapIndexed { index, item ->
            item.copy(
                label = if (longLabel && index == selected) "A deliberately long current destination that remains reachable on narrow screens" else item.label,
                selected = index == selected,
            )
        }
    }

    fun toggleNarrow() { narrow = !narrow }
    fun toggleVisible() { visible = !visible }
    fun changeCurrent() { changed = !changed; revision++ }
    fun toggleLong() { longLabel = !longLabel; revision++ }
    fun replace() { alternate = !alternate; revision++ }
    fun refresh() { notice = "Navigation example refreshed."; noticeSequence++ }

    companion object {
        /** Actual catalog destinations demonstrating distinct subsystem identities and categories. */
        fun subsystems(): List<SubsystemNavigationItem> = listOf(
            SubsystemNavigationItem("catalog-controls", "Controls", "Inputs, choices, and actions", "code", "#64748b", "/components/controls", "Build"),
            SubsystemNavigationItem("catalog-content", "Content", "Documents and source presentation", "file", "#f764f6", "/components/content", "Build"),
            SubsystemNavigationItem("catalog-overlays", "Overlays", "Dialogs and contextual navigation", "boxes", "#268e71", "/overlays", "Explore"),
        )

        private val destinations = listOf(
            NavigationItem("Foundation", "/foundation", false),
            NavigationItem("Controls", "/components/controls", false),
            NavigationItem("Layout", "/components/layout", false),
            NavigationItem("Feedback", "/components/feedback", false),
            NavigationItem("Content", "/components/content", false),
            NavigationItem("Selection", "/selection", false),
            NavigationItem("Overlays", "/overlays", false),
            NavigationItem("Navigation", "/navigation", false),
        )

        /** Actual catalog reference destinations for the overflow and header examples. */
        fun examples(): List<NavigationItem> = CatalogChoiceSearch.options.filterNot { it.disabled }.map { choice ->
            val group = when {
                choice.label.endsWith("· layout") -> "layout"
                choice.label.endsWith("· overlay") -> "overlays"
                else -> "controls"
            }
            NavigationItem(choice.label, "/components/$group#ref-${choice.value}", choice.value == "popover")
        }
    }
}
