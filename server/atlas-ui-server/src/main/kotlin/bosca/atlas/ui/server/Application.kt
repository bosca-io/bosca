package bosca.atlas.ui.server

import bosca.git.ui.server.installGitUiServer
import bosca.server.BoscaApplication
import bosca.server.netty.NettyServerEngine

/** Starts Atlas's standalone BML listener against its configured HTTP API. */
fun main(args: Array<String>) {
    NettyServerEngine.start("atlas-ui-server.yaml", args) { module() }
}

/** Installs Atlas and Git pages, browser authentication, subscriptions, and streamed file proxies. */
fun BoscaApplication.module() {
    val endpoint = environment.config.property("bml.graphqlEndpoint").getString()
    val prefix = environment.config.propertyOrNull("bml.authCookiePrefix")?.getString()?.takeIf(String::isNotBlank)
    installAtlasUiServer(endpoint, prefix)
}

/** Uses the same UI implementation for embedded and standalone Atlas deployments. */
fun BoscaApplication.installAtlasUiServer(graphqlEndpoint: String, authCookiePrefix: String? = null) {
    installGitUiServer(graphqlEndpoint, authCookiePrefix)
}
