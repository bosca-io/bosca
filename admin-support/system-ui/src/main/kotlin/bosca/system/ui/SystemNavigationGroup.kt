package bosca.system.ui

import bosca.design.model.NavigationItem

/** System's sidebar destinations, grouped as in Studio. */
data class SystemNavigationGroup(val label: String, val items: List<NavigationItem>)
