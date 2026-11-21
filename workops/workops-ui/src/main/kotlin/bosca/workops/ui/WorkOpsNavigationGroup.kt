package bosca.workops.ui

import bosca.design.model.NavigationItem

/** WorkOps destinations grouped by the activity they support. */
data class WorkOpsNavigationGroup(val label: String, val items: List<NavigationItem>)
