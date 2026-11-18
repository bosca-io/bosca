package bosca.system.ui

import bosca.system.ui.generated.BmlComponents
import bosca.system.ui.generated.BmlIslands
import bosca.system.ui.generated.BmlPages

/** Compiled system pages and assets imported by the Atlas BML host. */
object SystemUi {
    val pages = BmlPages.all.sortedBy { page -> page.route.count { it == '{' } }
    val components = BmlComponents.all
    val componentRenderers = BmlComponents.renderers
    val islandDispatchers = BmlIslands.dispatchers
    val componentIslandDispatchers = BmlIslands.componentDispatchers
    val contracts get() = listOf(bosca.system.ui.generated.SystemTokenOwnerSearchDispatcher(TokenOwnerSearch()))
    val css: String get() = requireNotNull(javaClass.getResourceAsStream("/system-ui.css")) { "System UI CSS missing" }.use { it.readAllBytes().decodeToString() }
}
