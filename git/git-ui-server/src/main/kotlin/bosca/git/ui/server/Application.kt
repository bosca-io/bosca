package bosca.git.ui.server

import bosca.git.ui.installGitUi
import bosca.server.BoscaApplication
import bosca.server.netty.NettyServerEngine

/** Hosts the BML application; Git's API owns authentication and all domain operations. */
fun main(args: Array<String>) {
    NettyServerEngine.start("git-ui-server.yaml", args) { module() }
}

fun BoscaApplication.module() {
    val endpoint = environment.config.property("bml.graphqlEndpoint").getString()
    val cookiePrefix = environment.config.propertyOrNull("bml.authCookiePrefix")
        ?.getString()?.takeIf(String::isNotBlank)
    installGitUiServer(endpoint, cookiePrefix)
}

/** Installs UI routes using an HTTP GraphQL endpoint owned by the Git API listener. */
fun BoscaApplication.installGitUiServer(graphqlEndpoint: String, authCookiePrefix: String? = null) {
    installGitUi(this, graphqlEndpoint, authCookiePrefix)
    GitFileProxy(graphqlEndpoint).install(this)
    router.get("/welcome") { call.respondRedirect("/repositories") }
    router.get("/api/v1/ready") { call.respond("ready") }
}
