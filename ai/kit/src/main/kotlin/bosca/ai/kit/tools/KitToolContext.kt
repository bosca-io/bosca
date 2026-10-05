package bosca.ai.kit.tools

import bosca.security.service.AuthenticationContext
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine context element that carries the caller's identity into Kit tool execution.
 *
 * Kit's agent run is launched inside a [KitToolContext], so every [KitTool] can resolve
 * *who* is calling without that identity ever being a model-visible tool argument. This is
 * how permission-scoped operations (editing a document, running a query) stay enforced no
 * matter whether Kit was invoked from the Bosca GraphQL API or the CLI — the same pattern
 * the V1 agent used (`ToolCallContext`/`ToolSessionContext`), adapted to Koog.
 */
class KitToolContext(
    val authentication: AuthenticationContext,
) : CoroutineContext.Element {

    companion object Key : CoroutineContext.Key<KitToolContext>

    override val key: CoroutineContext.Key<*> get() = Key
}
