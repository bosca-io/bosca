package bosca.artifacts.ui

import bosca.artifacts.ui.generated.BmlComponents
import bosca.artifacts.ui.generated.BmlIslands
import bosca.artifacts.ui.generated.BmlPages

/** Compiled artifact pages and assets imported by the Atlas BML host. */
object ArtifactsUi {
    val pages = BmlPages.all.sortedBy { page -> page.route.count { it == '{' } }
    val components = BmlComponents.all
    val componentRenderers = BmlComponents.renderers
    val islandDispatchers = BmlIslands.dispatchers
    val componentIslandDispatchers = BmlIslands.componentDispatchers
    val contracts get() = listOf(bosca.artifacts.ui.generated.ArtifactUploadTargetDispatcher(ArtifactUploadTargets()))
    val css: String get() = requireNotNull(javaClass.getResourceAsStream("/artifacts-ui.css")) { "Artifacts UI CSS missing" }.use { it.readAllBytes().decodeToString() }
}
