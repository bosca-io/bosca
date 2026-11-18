package bosca.git.ui

import bosca.design.model.SubsystemNavigationItem
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Optional host navigation for an imported Git UI; standalone Git has no subsystem catalog. */
data class GitUiNavigation(
    val items: List<SubsystemNavigationItem> = emptyList(),
    val product: String = "Git",
    val homeHref: String = "/",
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<GitUiNavigation>
}

/** Resolves the current host's navigation without sharing configuration between BML instances. */
suspend fun currentGitUiNavigation(): GitUiNavigation =
    currentCoroutineContext()[GitUiNavigation] ?: GitUiNavigation()
