package bosca.design.model

/** A presentation link whose destination and selected state are supplied by the consuming application. */
data class NavigationItem(val label: String, val href: String, val selected: Boolean)
