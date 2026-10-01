package bosca.profiles.web

import bosca.bml.project.CompiledProject
import bosca.bml.server.BmlServer
import java.io.File
import java.net.URI
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun main(args: Array<String>) {
    val development = devMode()
    buildServer(args, System.getProperty("bml.clientDir"), graphqlEndpoint(), development).start()
}

internal fun graphqlEndpoint(): String =
    System.getenv("BML_GRAPHQL_ENDPOINT")?.ifBlank { null } ?: "http://localhost:8080/graphql"

internal fun profilesWebPublicUrl(environment: Map<String, String> = System.getenv()): String {
    val configured = environment["PROFILES_WEB_PUBLIC_URL"]?.trim()?.trimEnd('/')
        ?.takeIf(String::isNotEmpty)
        ?: "http://localhost:9095"
    val uri = URI(configured)
    require(uri.scheme == "http" || uri.scheme == "https") {
        "PROFILES_WEB_PUBLIC_URL must use http or https"
    }
    require(uri.host != null && uri.rawPath.orEmpty().isEmpty() && uri.rawQuery == null && uri.rawFragment == null) {
        "PROFILES_WEB_PUBLIC_URL must be an origin without a path, query, or fragment"
    }
    return configured
}

internal fun profilesWebRedirect(path: String, publicUrl: String = profilesWebPublicUrl()): String {
    val safePath = path.takeIf { it.startsWith('/') && !it.startsWith("//") } ?: "/"
    return publicUrl.trimEnd('/') + safePath
}

internal fun profilesWebCookieDomain(environment: Map<String, String> = System.getenv()): String? {
    val configured = environment["PROFILES_WEB_COOKIE_DOMAIN"]?.trim()?.removePrefix(".")
        ?.takeIf(String::isNotEmpty)
        ?: return null
    require(COOKIE_DOMAIN.matches(configured)) {
        "PROFILES_WEB_COOKIE_DOMAIN must be a hostname without a scheme, port, or path"
    }
    return configured.lowercase()
}

internal fun resolvePort(args: Array<String>): Int = args.firstOrNull()?.toIntOrNull() ?: 9095

internal fun devMode(): Boolean =
    (System.getProperty("bml.dev") ?: System.getenv("BML_DEV"))?.equals("true", ignoreCase = true) == true

internal fun resolveClientDir(path: String?, development: Boolean, projectDirectory: File = File(".")): File? =
    (path?.let(::File)
        ?: projectDirectory.resolve(if (development) "build/generated/bml/js" else "build/generated/bml/js-prod"))
        .takeIf { it.isDirectory }

internal fun profilesCss(branding: Branding = branding()): String {
    val base = requireNotNull(object {}.javaClass.getResourceAsStream("/profiles.css")) {
        "profiles.css resource missing"
    }.readAllBytes().toString(Charsets.UTF_8)
    return """
        $base

        :root {
          --primary: ${branding.primaryColor};
          --accent: ${branding.accentColor};
          --brand-1: ${branding.primaryColor};
          --brand-2: ${branding.accentColor};
          --brand-accent: ${branding.accentColor};
        }
    """.trimIndent()
}

internal fun profilesJs(environment: Map<String, String> = System.getenv()): String {
    val bundle = requireNotNull(object {}.javaClass.getResourceAsStream("/profiles.js")) {
        "profiles.js resource missing"
    }.readAllBytes().toString(Charsets.UTF_8)
    val config = buildJsonObject {
        profilesWebCookieDomain(environment)?.let { put("cookieDomain", it) }
    }
    return "globalThis.profilesWebConfig = $config;\n$bundle"
}

internal fun buildServer(
    args: Array<String>,
    clientDirProp: String?,
    graphqlEndpoint: String?,
    development: Boolean = devMode(),
    branding: Branding = branding(),
): BmlServer = BmlServer(
    project = CompiledProject(name = "profiles-web", version = "0.0.1"),
    pages = bml.generated.BmlPages.all,
    components = bml.generated.BmlComponents.all,
    componentRenderers = bml.generated.BmlComponents.renderers,
    islandDispatchers = bml.generated.BmlIslands.dispatchers,
    componentIslandDispatchers = bml.generated.BmlIslands.componentDispatchers,
    port = resolvePort(args),
    dev = development,
    clientDir = resolveClientDir(clientDirProp, development),
    notFoundRoute = "/404",
    errorRoute = "/500",
    signInRoute = "/login",
    graphqlEndpoint = graphqlEndpoint,
    globalCss = profilesCss(branding),
    globalJs = profilesJs(),
)

private val COOKIE_DOMAIN = Regex(
    "^(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)*[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$",
)
