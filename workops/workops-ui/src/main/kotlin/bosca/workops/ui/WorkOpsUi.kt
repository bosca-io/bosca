package bosca.workops.ui

import bosca.workops.ui.generated.BmlComponents
import bosca.workops.ui.generated.BmlIslands
import bosca.workops.ui.generated.BmlPages

/** Compiled WorkOps pages and assets composed by Atlas's embedded and standalone BML hosts. */
object WorkOpsUi {
    /** Installs WorkOps-owned server transports used by the imported document editor. */
    fun install(application: bosca.server.BoscaApplication, graphqlEndpoint: String, pubSub: bosca.pubsub.PubSubService? = null) {
        bosca.workops.ui.collaboration.WorkOpsCollaborationServer(application, graphqlEndpoint, pubSub = pubSub).install()
        WorkOpsContentProxy(graphqlEndpoint).install(application)
    }

    val pages = BmlPages.all.sortedBy { page -> page.route.count { it == '{' } }
    val components = BmlComponents.all
    val componentRenderers = BmlComponents.renderers
    val islandDispatchers = BmlIslands.dispatchers
    val componentIslandDispatchers = BmlIslands.componentDispatchers
    /** Binds caller-authorized lookups, including configured API scripts on Atlas's shared API. */
    fun contracts(graphqlEndpoint: String) = listOf(
        bosca.workops.ui.generated.WorkOpsContextChoicesDispatcher(WorkOpsContextChoicesLookup()),
        bosca.workops.ui.generated.WorkOpsSpecChoicesDispatcher(WorkOpsSpecChoicesLookup()),
        bosca.workops.ui.generated.WorkOpsFormChoicesDispatcher(WorkOpsFormChoicesLookup(graphqlEndpoint)),
        bosca.workops.ui.generated.WorkOpsProfileSearchDispatcher(WorkOpsProfileLookup()),
        bosca.workops.ui.generated.WorkOpsMentionSearchDispatcher(WorkOpsMentionLookup()),
        bosca.workops.ui.generated.WorkOpsDocumentReferencesDispatcher(WorkOpsDocumentReferenceLookup()),
        bosca.workops.ui.generated.WorkOpsImagesDispatcher(WorkOpsImageLookup()),
        bosca.workops.ui.generated.WorkOpsBqlDispatcher(WorkOpsBqlLookup()),
    )
    val css: String
        get() = requireNotNull(javaClass.getResourceAsStream("/workops-ui.css")) {
            "WorkOps UI CSS missing"
        }.use { it.readAllBytes().decodeToString() }
}
