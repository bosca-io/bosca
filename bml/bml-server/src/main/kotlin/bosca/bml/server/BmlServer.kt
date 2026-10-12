package bosca.bml.server

import bosca.bml.render.BML_INSTALLATION_COOKIE
import bosca.bml.graphql.GraphQLClientFactory
import bosca.bml.i18n.toClientJson
import bosca.bml.project.CompiledProject
import bosca.bml.project.ContentDigest
import bosca.bml.render.BmlPageRenderer
import bosca.bml.render.BmlSharedCacheRequest
import bosca.bml.render.BmlSharedCacheRevision
import bosca.bml.render.BmlSharedCacheRevisionProvider
import bosca.bml.render.BmlSession
import bosca.bml.render.InvalidBmlClientStateException
import bosca.bml.render.RenderContext
import bosca.bml.render.withRenderContext
import bosca.server.BoscaApplication
import bosca.server.ContentType
import bosca.server.HttpHeaders
import bosca.server.config.ApplicationConfig
import bosca.server.http.await
import bosca.server.netty.NettyServerEngine
import bosca.server.routing.Router
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

/**
 * The dedicated BML SSR server runs a lean
 * [BoscaApplication] over bosca-core's Netty engine without domain registrars.
 * Serves a [BmlRouteTable] (route -> page render fn) plus the project's
 * discovery files (sitemap/robots/llms). Data for pages is fetched via the Bosca
 * GraphQL API at a configurable endpoint; the caller's token is
 * forwarded (passthrough). Same code in dev and prod — only the endpoint differs.
 */
class BmlServer(
    project: CompiledProject,
    pages: List<BmlPageRenderer>,
    private val port: Int = 8080,
    private val dev: Boolean = bmlDevelopmentMode(),
    /** Directory of bundled client JS (the `bmlBundleClient` output) served under `/_bml/js/`. */
    clientDir: java.io.File? = null,
    /** Bosca GraphQL endpoint pages fetch from (configurable local/remote). Null = no client. */
    graphqlEndpoint: String? = null,
    /**
     * The site's locale policy — **defined by the site's Bosca localization
     * project** (its source language is the default; its declared target languages are the
     * rest), not by server configuration. The project-backed policy lands with the GraphQL
     * catalog source; the default here is a fixed single-locale "en" policy for sites with no
     * localization project (negotiation inert, existing sites unchanged) and for tests.
     */
    localePolicy: bosca.bml.render.BmlLocalePolicy = bosca.bml.render.BmlLocalePolicy.static(),
    /**
     * Localized strings for every render — normally the GraphQL-backed source bound
     * to the same localization project as [localePolicy]. The empty default means an unlocalized
     * site: `t()` renders keys, `t`-attributed markup renders its authored text.
     */
    messageSource: bosca.bml.i18n.MessageSource = bosca.bml.i18n.MessageSource.Empty,
    /** Directory of static assets (CSS, images, favicon) served at the site root. */
    publicDir: java.io.File? = null,
    /**
     * Optional `Cache-Control` for everything under [publicDir] (fonts, images). Deploy-bundled
     * assets that only change with a redeploy can use a long-lived policy
     * (e.g. `public, max-age=31536000, immutable`) so repeat page loads skip refetching them.
     */
    publicCacheControl: String? = null,
    /**
     * Where `requireAuth` pages send token-less callers (the site's sign-in page). The requested
     * relative URL is appended as the `redirect` query parameter so sign-in can return the caller.
     */
    signInRoute: String = "/",
    /**
     * The route of the page rendered — with a 404 status — when a loader sets
     * [RenderContext.notFound] (the requested entity does not exist). Null (the default) answers
     * such renders with a plain 404.
     */
    notFoundRoute: String? = null,
    /**
     * The route of the page rendered — with a 500 status — when a page render throws (a loader's
     * upstream failure). Null (the default) answers such renders with a plain 500. The failure is
     * always logged with its route.
     */
    errorRoute: String? = null,
    /**
     * The compiled component registry (`bml.generated.BmlComponents.all`). When provided, the server
     * serves each component's scoped CSS as a cached per-component stylesheet and links only those a
     * page renders (the three-tier asset model) instead of inlining — see [bosca.bml.render.BmlComponentInfo].
     */
    components: List<bosca.bml.render.BmlComponentInfo> = emptyList(),
    /** Global, every-page CSS (the `app.css` tier). Served at `/_bml/app.css` and linked on every page. */
    globalCss: String? = null,
    /** Global, every-page client JS (the `app.js` tier). Served at `/_bml/app.js` and linked on every page. */
    globalJs: String? = null,
    /**
     * Tag -> component renderer (`bml.generated.BmlComponents.renderers`). Enables the **sliver
     * re-render** endpoint: a client POSTs new state to `/_bml/render/<tag>`, the server re-renders
     * that component server-side, and the island swaps the returned HTML in (no websockets).
     */
    componentRenderers: Map<String, bosca.bml.render.BmlComponentRenderer> = emptyMap(),
    /**
     * Live-island action dispatchers (`bml.generated.BmlIslands.dispatchers`), keyed by page route then
     * state key — state keys are unique per page, not globally. Enables the **live-island action** endpoint:
     * a `@click` POSTs `{page, stateKey, method, state}` to `/_bml/action`, and the matching page's
     * dispatcher runs the method on the model and re-renders the view.
     */
    islandDispatchers: Map<String, Map<String, bosca.bml.render.BmlIslandActionDispatcher>> = emptyMap(),
    /**
     * Component live-state dispatchers (`bml.generated.BmlIslands.componentDispatchers`), keyed by their
     * `"<tag>.<provides>"` prefix — a posted component state key (`"<tag>.<provides>:<instance>"`)
     * resolves here when the page map misses and the component belongs to the requested page.
     */
    componentIslandDispatchers: Map<String, bosca.bml.render.BmlIslandActionDispatcher> = emptyMap(),
    /**
     * Store of `scope="server-session"` live-island sessions (the model JSON never reaches the client). Defaults to
     * [InMemoryBmlSessionStore] (single-instance / dev); for a multi-instance deployment inject a distributed
     * store — [CacheManagerBmlSessionStore] (Redis / NATS KV via bosca-core's `CacheManager`) — so sessions
     * are shared across instances.
     */
    private val sessions: bosca.bml.render.BmlSessionStore = InMemoryBmlSessionStore(),
    /**
     * `<contract>` dispatchers, each pairing a generated `…Dispatcher` with the site's
     * implementation — e.g. `listOf(GroupChatOpsDispatcher(GroupChatOpsImpl()))`. Enables the typed
     * client↔server RPC endpoint `POST /_bml/contract/{name}/{method}`.
     */
    contracts: List<bosca.bml.render.BmlContractDispatcher> = emptyList(),
    /** Exact custom auth cookie prefix. Null retains the default `_bat` behavior. */
    authCookiePrefix: String? = System.getenv("BML_AUTH_COOKIE_PREFIX")?.trim()?.takeIf(String::isNotEmpty),
    /**
     * Full analytics installation-registration URL. This endpoint is independent of
     * [graphqlEndpoint] because GraphQL and analytics may be served by different hosts.
     * Null disables server-side installation registration. When omitted, the value comes from
     * `BML_INSTALLATION_ENDPOINT`.
     */
    installationEndpoint: String? = System.getenv("BML_INSTALLATION_ENDPOINT")
        ?.trim()
        ?.takeIf(String::isNotEmpty),
    /**
     * Optional cheap revision lookup for shared shells. Applications normally back this with the
     * same in-memory public-data cache advanced by Bosca change events. A matching conditional
     * request can then return 304 before page rendering or GraphQL work begins when the site has no
     * localization source. Localized shells use a completed-body validator so catalog changes are
     * included.
     */
    sharedCacheRevisionProvider: BmlSharedCacheRevisionProvider? = null,
) {
    private data class RuntimeState(
        val project: CompiledProject,
        val pages: List<BmlPageRenderer>,
        val clientDir: java.io.File?,
        val graphqlEndpoint: String?,
        val authCookiePrefix: String?,
        val installationEndpoint: String?,
        val localePolicy: bosca.bml.render.BmlLocalePolicy,
        val messageSource: bosca.bml.i18n.MessageSource,
        val publicDir: java.io.File?,
        val publicCacheControl: String?,
        val sharedCacheRevisionProvider: BmlSharedCacheRevisionProvider?,
        val signInRoute: String,
        val notFoundRoute: String?,
        val errorRoute: String?,
        val components: List<bosca.bml.render.BmlComponentInfo>,
        val globalCss: String?,
        val globalJs: String?,
        val assetCacheToken: String,
        val componentRenderers: Map<String, bosca.bml.render.BmlComponentRenderer>,
        val islandDispatchers: Map<String, Map<String, bosca.bml.render.BmlIslandActionDispatcher>>,
        val componentIslandDispatchers: Map<String, bosca.bml.render.BmlIslandActionDispatcher>,
        val contracts: List<bosca.bml.render.BmlContractDispatcher>,
        val development: Boolean,
        val componentsByTag: Map<String, bosca.bml.render.BmlComponentInfo> = components.associateBy { it.tag },
        val pagesByRoute: Map<String, BmlPageRenderer> = pages.associateBy { it.route },
        val componentTagsByPage: Map<String, Set<String>> = pages.associate { page ->
            page.route to BmlStyleAssets.closure(page.componentTags, componentsByTag).toSet()
        },
        val deferredById: Map<String, bosca.bml.render.BmlDeferredRenderer> =
            (pages.flatMap { it.deferredRenderers } +
                components.flatMap { it.deferredRenderers } +
                componentRenderers.values.flatMap { it.deferredRenderers })
                .associateBy { it.id },
        val effectiveIslandDispatchers: Map<String, Map<String, bosca.bml.render.BmlIslandActionDispatcher>> =
            buildMap {
                putAll(islandDispatchers)
                for (page in pages) {
                    val deferred = page.deferredRenderers.flatMap { it.islandActionDispatchers }
                    if (deferred.isNotEmpty()) {
                        put(page.route, (get(page.route).orEmpty().values + deferred).associateBy { it.stateKey })
                    }
                }
            },
        val effectiveComponentIslandDispatchers: Map<String, bosca.bml.render.BmlIslandActionDispatcher> =
            (componentIslandDispatchers.values +
                components.flatMap { component ->
                    component.deferredRenderers.flatMap { it.islandActionDispatchers }
                } +
                componentRenderers.values.flatMap { renderer ->
                    renderer.deferredRenderers.flatMap { it.islandActionDispatchers }
                }).associateBy { it.stateKey },
        val linkedAssets: Boolean = components.isNotEmpty() || globalCss != null,
        val inlineComponentStyles: Boolean = components.isEmpty(),
        val productionPlan: BmlProductionAssets.Plan? =
            if (!development) {
                BmlProductionAssets.plan(pages, components.associateBy { it.tag })
            } else {
                null
            },
        val servedGlobalCss: String? = when {
            productionPlan == null -> globalCss
            else -> listOfNotNull(globalCss, productionPlan.sharedCss.takeIf { it.isNotBlank() })
                .joinToString("\n")
                .takeIf { it.isNotBlank() }
        },
        val gqlFactory: GraphQLClientFactory? = graphqlEndpoint?.let(::GraphQLClientFactory),
        val contractsByName: Map<String, bosca.bml.render.BmlContractDispatcher> = contracts.associateBy { it.name },
    ) {
        init {
            pages.forEach { ContentType.parse(it.contentType) }
        }

        /**
         * Shared-cache eligibility per page route. Every input is static for one generation, so each
         * decision is made once rather than on every request. Refusals are logged by
         * [logSharedCacheRefusals] when a generation goes live, not here, so a hot-swap copy never logs.
         */
        private val sharedCacheDecisions: Map<String, SharedCacheDecision> by lazy(LazyThreadSafetyMode.PUBLICATION) {
            pages.associate { page -> page.route to decideSharedCache(page) }
        }

        /** The page's shared-cache decision; a page outside this generation's table is decided on demand. */
        fun sharedCacheDecision(page: BmlPageRenderer): SharedCacheDecision =
            sharedCacheDecisions[page.route]?.takeIf { it.page === page } ?: decideSharedCache(page)

        /** Logs each page whose `cache="shared"` request is refused; called once when this generation goes live. */
        fun logSharedCacheRefusals() {
            for (decision in sharedCacheDecisions.values) {
                if (!decision.requested || decision.policy != null) continue
                val page = decision.page
                log.warn(
                    "bml: refusing shared caching for incompatible page '{}' " +
                        "(freshSeconds={}, staleSeconds={}, requireAuth={}, serverState={}, eagerFeatureFlags={}, contentType={})",
                    page.route,
                    page.sharedCacheMaxAgeSeconds,
                    page.sharedCacheStaleWhileRevalidateSeconds,
                    page.requiresAuth,
                    decision.needsSession,
                    decision.eagerFeatureFlags,
                    page.contentType,
                )
            }
        }

        /** Content validators for in-memory asset bodies; this generation's bodies never change, so hash each once. */
        val inMemoryAssetEtags: java.util.concurrent.ConcurrentHashMap<String, String> = java.util.concurrent.ConcurrentHashMap()

        private fun decideSharedCache(page: BmlPageRenderer): SharedCacheDecision {
            val needsSession = pageNeedsSession(page)
            val requested = page.sharedCacheMaxAgeSeconds != null || page.sharedCacheStaleWhileRevalidateSeconds != null
            if (!requested) {
                return SharedCacheDecision(page, requested = false, needsSession = needsSession, eagerFeatureFlags = false, policy = null)
            }
            val eagerHasFeatureFlags = BmlStyleAssets.eagerClosure(page.eagerComponentTags, componentsByTag)
                .any { componentsByTag[it]?.hasEagerFeatureFlags == true }
            val policy = page.sharedCacheMaxAgeSeconds?.let { freshSeconds ->
                page.sharedCacheStaleWhileRevalidateSeconds?.let { staleSeconds ->
                    SharedCachePolicy(freshSeconds, staleSeconds)
                }
            }?.takeIf {
                it.freshSeconds > 0 && it.staleWhileRevalidateSeconds > 0 &&
                    !page.requiresAuth && !needsSession && !eagerHasFeatureFlags &&
                    ContentType.parse(page.contentType).match("text/html")
            }
            return SharedCacheDecision(
                page,
                requested = true,
                needsSession = needsSession,
                eagerFeatureFlags = eagerHasFeatureFlags,
                policy = policy,
            )
        }

        fun pageOwns(renderer: bosca.bml.render.BmlDeferredRenderer, page: BmlPageRenderer): Boolean = when {
            renderer.pageRoute != null -> renderer.pageRoute == page.route
            renderer.ownerComponentTag != null ->
                renderer.ownerComponentTag in componentTagsByPage[page.route].orEmpty()
            else -> false
        }

        fun pageOwnsComponentState(page: BmlPageRenderer, stateKey: String): Boolean {
            val ownerTag = stateKey.substringBefore(':').substringBeforeLast('.', missingDelimiterValue = "")
            return ownerTag.isNotEmpty() && ownerTag in componentTagsByPage[page.route].orEmpty()
        }

        fun hasComponentDeferredRenderer(page: BmlPageRenderer): Boolean =
            componentTagsByPage[page.route].orEmpty().any { tag ->
                componentsByTag[tag]?.deferredRenderers?.isNotEmpty() == true ||
                    componentRenderers[tag]?.deferredRenderers?.isNotEmpty() == true
            }

        /** A page must establish its session before any deferred requests can run concurrently. */
        fun pageNeedsSession(page: BmlPageRenderer): Boolean {
            if (page.hasServerState) return true
            return componentClosureNeedsSession(page.componentTags) ||
                page.deferredRenderers.any(::deferredNeedsSession)
        }

        fun deferredNeedsSession(renderer: bosca.bml.render.BmlDeferredRenderer): Boolean =
            renderer.hasServerState || componentClosureNeedsSession(renderer.componentTags)

        private fun componentClosureNeedsSession(tags: List<String>): Boolean =
            BmlStyleAssets.closure(tags, componentsByTag).any { tag ->
                componentsByTag[tag]?.let { component ->
                    component.hasServerState || component.deferredRenderers.any { it.hasServerState }
                } == true || componentRenderers[tag]?.deferredRenderers?.any { it.hasServerState } == true
            }
    }

    private data class SharedCachePolicy(
        val freshSeconds: Long,
        val staleWhileRevalidateSeconds: Long,
    )

    private data class SharedCacheDecision(
        val page: BmlPageRenderer,
        val requested: Boolean,
        val needsSession: Boolean,
        val eagerFeatureFlags: Boolean,
        val policy: SharedCachePolicy?,
    )

    private data class SharedCacheValidator(
        val etag: String,
    )

    private data class RequestIdentity(
        val token: String?,
        val installationId: String?,
    )

    private val initialAuthCookiePrefix = authCookiePrefix?.trim()?.takeIf(String::isNotEmpty)
    private val installationEndpoint = installationEndpoint?.trim()?.takeIf(String::isNotEmpty)
    private val installationHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val effectiveGlobalJs = bmlGlobalJs(globalJs, initialAuthCookiePrefix)

    /** The build time written by the Gradle plugin; development generations don't use it. */
    private val buildId = if (dev) {
        ""
    } else {
        bmlBuildId(pages.firstOrNull()?.javaClass?.classLoader ?: BmlServer::class.java.classLoader)
    }
    private val deploymentCacheToken = if (dev) {
        ""
    } else {
        bmlDeploymentCacheToken(
            project = project,
            pages = pages,
            components = components,
            globalCss = globalCss,
            globalJs = effectiveGlobalJs,
            clientDir = clientDir,
            buildId = buildId,
        )
    }

    private val state = AtomicReference(
        RuntimeState(
            project = project,
            pages = pages,
            clientDir = clientDir,
            graphqlEndpoint = graphqlEndpoint,
            authCookiePrefix = initialAuthCookiePrefix,
            installationEndpoint = this.installationEndpoint,
            localePolicy = localePolicy,
            messageSource = messageSource,
            publicDir = publicDir,
            publicCacheControl = publicCacheControl,
            sharedCacheRevisionProvider = sharedCacheRevisionProvider,
            signInRoute = signInRoute,
            notFoundRoute = notFoundRoute,
            errorRoute = errorRoute,
            components = components,
            globalCss = globalCss,
            globalJs = effectiveGlobalJs,
            assetCacheToken = deploymentCacheToken,
            componentRenderers = componentRenderers,
            islandDispatchers = islandDispatchers,
            componentIslandDispatchers = componentIslandDispatchers,
            contracts = contracts,
            development = dev,
        ),
    )
    /** Unique per server process; generation changes are appended for in-process hot swaps. */
    private val bootId: String = java.util.UUID.randomUUID().toString()
    private val reloadGeneration = MutableStateFlow(0L)
    private val registeredPageRoutes = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var installedApplication: BoscaApplication? = null

    /** Starts the server, or publishes this generation into the existing dev server. */
    fun start() {
        if (dev && bmlHotSwapMode()) {
            if (BmlHotSwapTransaction.stage(this)) return
            startHotSwapOrReload(this)
            return
        }
        startBlocking()
    }

    internal fun activateHotSwapGeneration() {
        check(dev && bmlHotSwapMode()) { "BML hot-swap generation is not in development mode" }
        startHotSwapOrReload(this)
    }

    private fun startBlocking() {
        val current = state.get()
        val config = ApplicationConfig.load(inlineYaml().byteInputStream(Charsets.UTF_8))
        val application = BoscaApplication(config)
        install(application)

        val engine = NettyServerEngine(application, port)
        Runtime.getRuntime().addShutdownHook(
            Thread {
                log.info("bml-server stopping")
                Runtime.getRuntime().halt(0)
            },
        )
        application.freezeMiddleware()
        log.info(
            "bml-server serving {}@{} (build {}) on http://localhost:{} — routes: {}",
            current.project.name, current.project.version, buildId.ifEmpty { "unknown" }, port,
            current.pages.map { it.route }.sorted().joinToString(", "),
        )
        if (dev) log.info("bml-server dev hot swap enabled (SSE {})", BmlDevReload.ENDPOINT)
        engine.start()
    }

    /**
     * Atomically replaces the generated application state without stopping Netty or losing sessions.
     * Existing requests retain the generation they captured; subsequent requests use [replacement].
     */
    internal fun hotSwapFrom(replacement: BmlServer): () -> Unit {
        require(dev && replacement.dev) { "BML hot swap requires development mode" }
        require(port == replacement.port) { "BML hot swap cannot change the server port" }
        val previous = state.get()
        val replacementState = replacement.state.get()
        // Preserve the shared OkHttp connection pool when the endpoint is unchanged. Renderers
        // are generation-specific; the transport factory is framework state and need not churn.
        val next = if (previous.graphqlEndpoint == replacementState.graphqlEndpoint) {
            replacementState.copy(gqlFactory = previous.gqlFactory)
        } else {
            replacementState
        }
        require(previous.clientDir.canonicalFileOrNull() == next.clientDir.canonicalFileOrNull()) {
            "BML hot swap cannot change the client asset directory"
        }
        require(previous.publicDir.canonicalFileOrNull() == next.publicDir.canonicalFileOrNull()) {
            "BML hot swap cannot change the public asset directory"
        }
        require(previous.publicCacheControl == next.publicCacheControl) {
            "BML hot swap cannot change public asset caching"
        }

        val router = installedApplication?.router
        if (router != null) next.pages.forEach { registerPageRoute(router, it.route) }
        state.set(next)
        next.logSharedCacheRefusals()
        val generation = reloadGeneration.value + 1
        reloadGeneration.value = generation
        log.info(
            "bml-server hot-swapped {}@{} — generation {}, routes: {}",
            next.project.name,
            next.project.version,
            generation,
            next.pages.map { it.route }.sorted().joinToString(", "),
        )
        return {
            previous.pages.forEach { page -> installedApplication?.router?.let { registerPageRoute(it, page.route) } }
            state.set(previous)
            reloadGeneration.value = reloadGeneration.value + 1
        }
    }

    private fun java.io.File?.canonicalFileOrNull(): java.io.File? = this?.canonicalFile

    /** Register the project's pages + discovery files on the application router (visible for testing). */
    /**
     * Answers a failed page render: the configured error page with a 500 status, or a plain 500
     * when none is configured, the failing page IS the error page, or the error page itself
     * fails (an error page must never recurse).
     */
    private suspend fun respondErrorPage(
        current: RuntimeState,
        call: bosca.server.ServerCall,
        failedPage: BmlPageRenderer,
        token: String?,
        installationId: String?,
        existingSession: BmlSession?,
        restoreRequestIdentity: Boolean,
    ) {
        val errorPage = current.errorRoute?.let { current.pagesByRoute[it] }?.takeIf { it !== failedPage }
        if (errorPage == null) {
            call.respond(bosca.server.HttpStatusCode.InternalServerError, "500 Internal Server Error")
            return
        }
        try {
            val identity = resolveAlternatePageIdentity(
                current,
                call,
                token,
                installationId,
                restoreRequestIdentity,
            )
            val errorSession = sessionForAlternatePage(current, call, errorPage, existingSession)
            val errorCtx = RenderContext(
                gql = current.gqlFactory?.forToken(identity.token, identity.installationId, call.analyticsSessionId),
                token = identity.token,
                cookies = installationCookies(call.request.cookies.all, identity.installationId),
                query = requestQuery(call.request),
                locale = resolveLocale(current, call.request),
                messages = current.messageSource,
                inlineStyles = current.inlineComponentStyles,
                session = errorSession,
            ).apply { requestPath = errorPage.route }
            withRenderContext(errorCtx) { errorPage.render(errorCtx) }
            respondPage(current, call, errorPage, errorCtx, bosca.server.HttpStatusCode.InternalServerError)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("bml: the error page itself failed to render: {}", errorPage.route, e)
            call.respond(bosca.server.HttpStatusCode.InternalServerError, "500 Internal Server Error")
        }
    }

    /** Finishes a rendered page — asset links, script tags, dev reload — and responds it. */
    private suspend fun respondPage(
        current: RuntimeState,
        call: bosca.server.ServerCall,
        page: BmlPageRenderer,
        context: RenderContext,
        status: bosca.server.HttpStatusCode = bosca.server.HttpStatusCode.OK,
        contentType: ContentType = ContentType.parse(page.contentType),
        sharedCachePolicy: SharedCachePolicy? = null,
        sharedCacheValidator: SharedCacheValidator? = null,
    ) {
        val rendered = context.writer.toString()
        if (!contentType.match("text/html")) {
            call.respondBytes(rendered.toByteArray(Charsets.UTF_8), contentType, status)
            return
        }
        // An HTML page rendered with the visitor's identity must never be stored by a shared cache, even
        // under an edge rule that caches HTML for shared shells. `no-cache` (not `no-store`) keeps the
        // page eligible for the browser's back/forward cache. Non-HTML pages (feeds, sitemaps) keep
        // their existing caching, since they are rarely rendered from the visitor's identity.
        if (sharedCachePolicy == null && call.attributes[CACHE_CONTROL_SET_ATTRIBUTE] != true) {
            setCacheControl(call, "private, no-cache")
        }
        var html = rendered
        val hasComponentDeferredRenderer = current.hasComponentDeferredRenderer(page)
        if (sharedCachePolicy != null) {
            html = BmlClientAssets.injectSharedIdentity(html, current.installationEndpoint != null)
        } else if (current.installationEndpoint != null &&
            (page.deferredRenderers.isNotEmpty() || hasComponentDeferredRenderer)
        ) {
            html = BmlClientAssets.injectInstallationIdentity(html)
        }
        html = BmlClientAssets.injectPageContext(html, page.route, context.requestPath, context.lang)
        val cacheToken = current.assetCacheToken.takeUnless { current.development }
        // CSS: in production, at most two links — the normalized global stylesheet and the page's
        // merged stylesheet. In dev, the global plus one chunk per rendered component,
        // so an edit invalidates one small file.
        if (current.linkedAssets) {
            val links = current.productionPlan?.let { plan ->
                BmlStyleAssets.cssLinksProduction(
                    current.servedGlobalCss != null,
                    plan.pageCssUrl(page.route),
                    cacheToken,
                )
            } ?: run {
                val closure = BmlStyleAssets.closure(page.componentTags, current.componentsByTag)
                BmlStyleAssets.cssLinks(current.globalCss != null, closure, current.componentsByTag, cacheToken)
            }
            html = BmlStyleAssets.injectHead(html, links)
        }
        // Scripts (before </body>): global app.js, then the page's JS, then dev-reload.
        if (current.globalJs != null) {
            html = BmlClientAssets.injectUrl(html, BmlStyleAssets.GLOBAL_JS_URL, cacheToken)
        }
        if (current.productionPlan != null) {
            // Production: one bundle per page — the bundler merged the page's own client
            // code with its closure components' into `<slug>.page.js`; nothing else is injected.
            current.productionPlan.pageJsFile[page.route]?.let {
                html = BmlClientAssets.inject(html, it, cacheToken)
            }
        } else {
            // Dev: the page's own module plus one module per closure component, so an edit
            // invalidates one small file — the JS analog of the CSS chunks.
            page.clientModule?.let { html = BmlClientAssets.inject(html, it) }
            BmlStyleAssets.closure(page.componentTags, current.componentsByTag)
                .mapNotNull { current.componentsByTag[it]?.clientModule }
                .distinct()
                .filterNot { it == page.clientModule }
                .forEach { html = BmlClientAssets.inject(html, it) }
        }
        if (dev) html = BmlDevReload.inject(html)
        val bytes = html.toByteArray(Charsets.UTF_8)
        if (sharedCachePolicy != null && status == bosca.server.HttpStatusCode.OK) {
            val validator = sharedCacheValidator ?: SharedCacheValidator(etagForBody(bytes))
            applySharedCacheHeaders(current, call, sharedCachePolicy, validator)
            if (!current.development && isNotModified(call, validator)) {
                call.respond(bosca.server.HttpStatusCode.NotModified)
                return
            }
        }
        call.respondBytes(bytes, contentType, status)
    }

    private fun registerPageRoute(router: Router, route: String) {
        if (!registeredPageRoutes.add(route)) return
        router.get(route) { handlePage(call) }
    }

    /** Resolves against the current table so old route registrations never retain an old generation. */
    private suspend fun handlePage(
        call: bosca.server.ServerCall,
        exactPage: BmlPageRenderer? = null,
    ) {
        val current = state.get()
        val match = if (exactPage == null) {
            current.pages.firstNotNullOfOrNull { page ->
                Router.matchPath(page.route, call.request.path)?.let { page to it }
            }
        } else {
            Router.matchPath(exactPage.route, call.request.path)?.let { exactPage to it }
        }
        if (match == null) {
            call.respond(bosca.server.HttpStatusCode.NotFound, "404 Not Found")
            return
        }
        val (page, pathParameters) = match
        val sharedCache = current.sharedCacheDecision(page)
        val needsSession = sharedCache.needsSession
        val sharedRequested = sharedCache.requested
        // A shared shell must be selected by its URL alone. When the locale would come from
        // Accept-Language negotiation (several locales and no ?lang), the edge cannot be relied on to
        // separate the variants, so this request renders privately; ?lang-addressed URLs stay shareable.
        val negotiatedLocale = if (sharedCache.policy != null && call.request.queryParameters["lang"] == null) {
            // The locale policy can load from a Bosca localization project over the network; a failure
            // belongs to the configured error page like any other failure to serve the page.
            try {
                current.localePolicy.current().supported.size > 1
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setCacheControl(call, "private, no-store")
                log.error("bml: locale policy failed for page: {}", page.route, e)
                respondErrorPage(current, call, page, null, null, null, restoreRequestIdentity = true)
                return
            }
        } else {
            false
        }
        val sharedCachePolicy = sharedCache.policy?.takeUnless { negotiatedLocale }
        if (sharedRequested && sharedCachePolicy == null) setCacheControl(call, "private, no-store")
        if (sharedCachePolicy != null) call.suppressAnalyticsSessionCookie()

        val token = if (sharedCachePolicy == null) resolveToken(current, call.request) else null
        if (page.requiresAuth && token == null) {
            setCacheControl(call, "private, no-store")
            val separator = if ('?' in current.signInRoute) '&' else '?'
            val redirect = URLEncoder.encode(call.request.uri, Charsets.UTF_8)
            call.respondRedirect("${current.signInRoute}${separator}redirect=$redirect")
            return
        }
        val query = requestQuery(call.request)
        var installationId: String? = null
        var session: BmlSession? = null
        lateinit var ctx: RenderContext
        var sharedCacheValidator: SharedCacheValidator? = null
        try {
            // Identity resolution can call the installation endpoint; a failure there belongs to the
            // configured error page like any other render failure.
            if (sharedCachePolicy == null) installationId = resolveInstallationId(call, current)
            session = if (needsSession && sharedCachePolicy == null) {
                resolveSession(call.request, createIfMissing = true)
            } else {
                null
            }
            session?.let { appendSessionCookie(call, it) }
            val analyticsSessionId = if (sharedCachePolicy == null) call.analyticsSessionId else null
            val locale = if (sharedCachePolicy == null) {
                resolveLocale(current, call.request)
            } else {
                resolveSharedLocale(current, call.request)
            }
            ctx = RenderContext(
                gql = current.gqlFactory?.forToken(
                    token,
                    installationId,
                    analyticsSessionId,
                ),
                token = token,
                params = pathParameters,
                cookies = if (sharedCachePolicy == null) {
                    installationCookies(call.request.cookies.all, installationId)
                } else {
                    emptyMap()
                },
                query = query,
                locale = locale,
                messages = current.messageSource,
                inlineStyles = current.inlineComponentStyles,
                session = session,
            ).apply {
                if (sharedCachePolicy != null) disallowFeatureFlags()
                requestPath = call.request.path
            }
            var sharedCacheRequest: BmlSharedCacheRequest? = null
            var sharedCacheRevision: BmlSharedCacheRevision? = null
            if (sharedCachePolicy != null && !current.development) {
                val cacheRequest = BmlSharedCacheRequest(
                    pageRoute = page.route,
                    path = call.request.path,
                    params = pathParameters,
                    query = query,
                    locale = locale,
                )
                sharedCacheRequest = cacheRequest
                // A localized shell's bytes also depend on MessageSource's cached catalog snapshot.
                // MessageSource does not expose an atomic revision for that snapshot, so rendering is
                // required to derive a validator from the completed body.
                sharedCacheRevision = current.sharedCacheRevisionProvider
                    ?.takeIf { current.messageSource === bosca.bml.i18n.MessageSource.Empty }
                    ?.revision(cacheRequest)
                sharedCacheValidator = sharedCacheRevision?.let { revision ->
                    validatorForRevision(current, cacheRequest, revision)
                }
                val validator = sharedCacheValidator
                // `If-None-Match: *` is only answered after rendering: before it, this request might
                // still turn out to be a redirect or not-found, which has no current representation.
                if (validator != null && isNotModified(call, validator, allowWildcard = false)) {
                    applySharedCacheHeaders(current, call, sharedCachePolicy, validator)
                    call.respond(bosca.server.HttpStatusCode.NotModified)
                    return
                }
            }
            withRenderContext(ctx) { page.render(ctx) }
            val cacheRequest = sharedCacheRequest
            val renderedRevision = sharedCacheRevision
            if (cacheRequest != null && renderedRevision != null) {
                val revisionAfterRender = current.sharedCacheRevisionProvider?.revision(cacheRequest)
                if (revisionAfterRender != renderedRevision) {
                    // The page crossed a cache update while rendering, so the pre-render validator
                    // does not identify these exact bytes. respondPage will hash the completed body.
                    sharedCacheValidator = null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (sharedRequested) setCacheControl(call, "private, no-store")
            log.error("bml: page render failed: {}", page.route, e)
            respondErrorPage(
                current,
                call,
                page,
                token,
                installationId,
                session,
                sharedCachePolicy != null,
            )
            return
        }
        ctx.redirectTo?.let { location ->
            if (sharedRequested) setCacheControl(call, "private, no-store")
            call.respondRedirect(location, permanent = ctx.redirectPermanent)
            return
        }
        if (ctx.notFound) {
            if (sharedRequested) setCacheControl(call, "private, no-store")
            val notFoundPage = current.notFoundRoute?.let { current.pagesByRoute[it] }?.takeIf { it !== page }
            if (notFoundPage == null) {
                call.respond(bosca.server.HttpStatusCode.NotFound, "404 Not Found")
                return
            }
            try {
                val identity = resolveAlternatePageIdentity(
                    current,
                    call,
                    token,
                    installationId,
                    sharedCachePolicy != null,
                )
                val notFoundSession = sessionForAlternatePage(current, call, notFoundPage, session)
                val notFoundCtx = RenderContext(
                    gql = current.gqlFactory?.forToken(identity.token, identity.installationId, call.analyticsSessionId),
                    token = identity.token,
                    cookies = installationCookies(call.request.cookies.all, identity.installationId),
                    query = requestQuery(call.request),
                    locale = resolveLocale(current, call.request),
                    messages = current.messageSource,
                    inlineStyles = current.inlineComponentStyles,
                    session = notFoundSession,
                ).apply { requestPath = notFoundPage.route }
                withRenderContext(notFoundCtx) { notFoundPage.render(notFoundCtx) }
                respondPage(current, call, notFoundPage, notFoundCtx, bosca.server.HttpStatusCode.NotFound)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("bml: not-found page render failed: {}", notFoundPage.route, e)
                respondErrorPage(current, call, notFoundPage, token, installationId, session, sharedCachePolicy != null)
            }
            return
        }
        respondPage(
            current,
            call,
            page,
            ctx,
            sharedCachePolicy = sharedCachePolicy,
            sharedCacheValidator = sharedCacheValidator,
        )
    }

    fun install(application: BoscaApplication) {
        check(installedApplication == null) { "BML server is already installed" }
        installedApplication = application
        application.install(AnalyticsSessionIdGenerator())
        state.get().logSharedCacheRefusals()
        val router = application.router
        // Keep the conventional discovery URLs ahead of parameterized application routes. An
        // exact BML /sitemap.xml page is intentionally delegated to, while the compiled discovery
        // document remains the fallback for sites that do not provide one.
        router.get(SITEMAP_ROUTE) {
            val current = state.get()
            val page = current.pagesByRoute[SITEMAP_ROUTE]
            if (page != null) {
                handlePage(call, page)
                return@get
            }
            val body = current.project.discovery.sitemap
            if (body == null) call.respond(bosca.server.HttpStatusCode.NotFound, "404 Not Found")
            else call.respondBytes(body.toByteArray(Charsets.UTF_8), ContentType.Application.Xml)
        }
        router.get("/robots.txt") {
            val body = state.get().project.discovery.robots
            if (body == null) call.respond(bosca.server.HttpStatusCode.NotFound, "404 Not Found")
            else call.respondBytes(body.toByteArray(Charsets.UTF_8), ContentType.Text.Plain)
        }
        router.get("/llms.txt") {
            val body = state.get().project.discovery.llms
            if (body == null) call.respond(bosca.server.HttpStatusCode.NotFound, "404 Not Found")
            else call.respondBytes(body.toByteArray(Charsets.UTF_8), ContentType.Text.Plain)
        }
        state.get().pages.forEach { registerPageRoute(router, it.route) }
        // Dev serves every CSS/JS tier with `no-cache` (revalidate every use, NEVER `no-store`):
        // edits still land on the next reload because the browser always asks, but an unchanged
        // asset answers 304 with no body. `no-store` re-downloaded every bundle, stylesheet, and
        // font on every navigation — through the browser's ~6-connections-per-origin budget (one
        // of which the dev SSE reload stream holds), fast clicking piled loads up for seconds.
        // Production URLs carry a deployment token, so generated CSS/JS can be cached immutably
        // until the next deployment changes that URL. Public-directory assets keep their separate,
        // explicitly configured policy because their URLs are not versioned by BML.
        val initial = state.get()
        val assetCacheControl = if (dev) "no-cache" else PRODUCTION_ASSET_CACHE_CONTROL
        // Serve the bundled client JS (+ sourcemaps) via Bosca Server's native static handler —
        // it does path resolution, content types, traversal protection, and ETag revalidation.
        initial.clientDir?.let { dir -> router.staticFiles(BmlClientAssets.URL_PREFIX, dir, assetCacheControl) }
        // Serve static assets (CSS, images, favicon) at the site root. Page routes are registered
        // above and take precedence; the static handler only resolves to existing files.
        initial.publicDir?.let { dir ->
            router.staticFiles("/", dir, if (dev) assetCacheControl else initial.publicCacheControl)
        }
        // These routes are registered unconditionally because a hot-swapped generation can add
        // the first global/component asset after the Netty router is already live.
        router.get(BmlStyleAssets.GLOBAL_CSS_URL) {
            val current = state.get()
            val css = current.servedGlobalCss
            if (css == null) call.respond(bosca.server.HttpStatusCode.NotFound, "404 Not Found")
            else respondInMemoryAsset(current, call, assetCacheControl, css, ContentType("text", "css"))
        }
        router.get(BmlStyleAssets.GLOBAL_JS_URL) {
            val current = state.get()
            val js = current.globalJs
            if (js == null) call.respond(bosca.server.HttpStatusCode.NotFound, "404 Not Found")
            else respondInMemoryAsset(current, call, assetCacheControl, js, ContentType("text", "javascript"))
        }
        router.get("${BmlStyleAssets.COMPONENT_CSS_PREFIX}{file}") {
            val current = state.get()
            val file = call.pathParameters["file"].orEmpty()
            val css = if (file.endsWith(BmlProductionAssets.PAGE_CSS_SUFFIX)) {
                current.productionPlan?.cssForSlug(file.removeSuffix(BmlProductionAssets.PAGE_CSS_SUFFIX))
            } else {
                current.componentsByTag[file.removeSuffix(".css")]?.styles
            }
            if (css == null) call.respond(bosca.server.HttpStatusCode.NotFound, "404 Not Found")
            else respondInMemoryAsset(current, call, assetCacheControl, css, ContentType("text", "css"))
        }
        // Per-locale message catalogs for client-side t(): the requested
        // locale's fallback chain MERGED server-side, so the browser helper needs no fallback
        // logic. Unsupported/garbage tags serve the default locale's catalog — the same policy
        // page negotiation applies, no 404 surprises. Content changes on catalog refresh, so the
        // ETag is a body hash (not the boot id) with a short max-age in prod.
        router.get("/_bml/i18n/{file}") {
            val current = state.get()
            if (current.messageSource === bosca.bml.i18n.MessageSource.Empty) {
                call.respond(bosca.server.HttpStatusCode.NotFound, "404 Not Found")
            } else {
                val requested = call.pathParameters["file"].orEmpty().removeSuffix(".json")
                val policy = current.localePolicy.current()
                val locale = policy.match(requested) ?: policy.default
                val body = bosca.bml.i18n.clientCatalog(current.messageSource, locale).toClientJson(locale)
                val bytes = body.toByteArray(Charsets.UTF_8)
                call.response.header(HttpHeaders.CacheControl, if (dev) "no-cache" else "public, max-age=60")
                val etag = etagForBody(bytes)
                call.response.header(HttpHeaders.ETag, etag)
                val ifNoneMatch = call.request.header(HttpHeaders.IfNoneMatch)
                if (ifNoneMatch != null && (ifNoneMatch == "*" || ifNoneMatch.split(",").any { it.trim() == etag })) {
                    call.respond(bosca.server.HttpStatusCode.NotModified)
                } else {
                    call.respondBytes(bytes, ContentType.Application.Json)
                }
            }
        }
        // Shared shells cannot carry per-browser cookies. Establish both browser identities once,
        // before their private deferred fragments fan out in parallel.
        router.post("/_bml/identity") {
            val current = state.get()
            resolveInstallationId(call, current)
            call.analyticsSessionId
            call.response.header(HttpHeaders.CacheControl, "private, no-store")
            call.respond(bosca.server.HttpStatusCode.NoContent, "")
        }
        // Typed client↔server RPC: a page's generated contract stub POSTs a JSON args
        // array; the dispatcher decodes, invokes the site's implementation with the caller's
        // token-bound GraphQL client, and returns the JSON-encoded result.
        router.post("/_bml/contract/{name}/{method}") {
                val current = state.get()
                val dispatcher = current.contractsByName[call.pathParameters["name"]]
                val method = call.pathParameters["method"].orEmpty()
                if (dispatcher == null || method !in dispatcher.methods) {
                    log.warn("bml: unknown contract call '{}'.'{}'", call.pathParameters["name"], method)
                    call.respondBytes(
                        """{"error":"unknown contract method"}""".toByteArray(Charsets.UTF_8),
                        ContentType.Application.Json,
                        bosca.server.HttpStatusCode.NotFound,
                    )
                    return@post
                }
                val token = resolveToken(current, call.request)
                val installationId = resolveInstallationId(call, current)
                val gql = current.gqlFactory?.forToken(token, installationId, call.analyticsSessionId)
                // Ambient context for the implementation: contract bodies are site
                // Kotlin like any loader, so currentRenderContext()/currentLocale() (and the
                // localization helpers built on them) must work here too. The dispatcher keeps
                // its narrow (gql, method, body) signature — the context rides the coroutine.
                val ctx = RenderContext(
                    gql = gql,
                    token = token,
                    cookies = installationCookies(call.request.cookies.all, installationId),
                    locale = resolveLocale(current, call.request),
                    messages = current.messageSource,
                )
                val result = try {
                    withRenderContext(ctx) { dispatcher.dispatch(gql, method, call.request.bodyText()) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("bml: contract {}.{} failed: {}", dispatcher.name, method, e.toString())
                    call.respondBytes(
                        """{"error":"contract call failed"}""".toByteArray(Charsets.UTF_8),
                        ContentType.Application.Json,
                        bosca.server.HttpStatusCode.InternalServerError,
                    )
                    return@post
                }
                call.respondBytes(result.toByteArray(Charsets.UTF_8), ContentType.Application.Json)
        }
        // Same-origin OAuth pass-through (the auth SDK's signInWithRedirect navigates to
        // /oauth2/{provider}/login on THIS origin; the data plane answers with the provider
        // redirect, which is relayed — never followed). Mirrors Studio's oauth2 dev proxy.
        router.get("/oauth2/{provider}/login") {
                val factory = state.get().gqlFactory
                if (factory == null) {
                    call.respond(bosca.server.HttpStatusCode.NotFound, "404 Not Found")
                    return@get
                }
                val forwarded = try {
                    factory.forwardGet(call.request.uri)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("bml: oauth2 proxy upstream unreachable: {}", e.toString())
                    call.respond(bosca.server.HttpStatusCode.BadGateway, "OAuth upstream unreachable")
                    return@get
                }
                forwarded.headers.forEach { (name, value) -> call.response.header(name, value) }
                call.respondBytes(
                    forwarded.body,
                    forwarded.contentType?.let { ContentType.parse(it) } ?: ContentType.Text.Plain,
                    bosca.server.HttpStatusCode.fromValue(forwarded.status),
                )
        }
        // Account linking uses the same redirect relay, but forwards the current Bosca token so the
        // data plane can bind the provider identity to the authenticated principal instead of signing
        // into whichever account already owns that provider identity.
        router.get("/oauth2/{provider}/connect") {
                val current = state.get()
                val factory = current.gqlFactory
                if (factory == null) {
                    call.respond(bosca.server.HttpStatusCode.NotFound, "404 Not Found")
                    return@get
                }
                val forwarded = try {
                    factory.forwardGet(call.request.uri, resolveToken(current, call.request))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("bml: oauth2 connect proxy upstream unreachable: {}", e.toString())
                    call.respond(bosca.server.HttpStatusCode.BadGateway, "OAuth upstream unreachable")
                    return@get
                }
                forwarded.headers.forEach { (name, value) -> call.response.header(name, value) }
                call.respondBytes(
                    forwarded.body,
                    forwarded.contentType?.let { ContentType.parse(it) } ?: ContentType.Text.Plain,
                    bosca.server.HttpStatusCode.fromValue(forwarded.status),
                )
        }
        // Same-origin GraphQL BFF proxy (the "BFF" half of the BML server): browser code POSTs GraphQL
        // here instead of the (cross-origin) data plane, and the server forwards the body untouched
        // with the caller's resolved token attached. Upstream status, JSON, and session cookies pass
        // through as-is so auth mutations can create or clear browser sessions on the BML origin.
        router.post("/graphql") {
                val current = state.get()
                val factory = current.gqlFactory
                if (factory == null) {
                    call.respond(bosca.server.HttpStatusCode.NotFound, "404 Not Found")
                    return@post
                }
                val installationId = resolveInstallationId(call, current)
                val forwarded = try {
                    factory.forward(
                        call.request.bodyText(),
                        resolveToken(current, call.request),
                        call.request.appOrigin,
                        installationId,
                        call.analyticsSessionId,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("bml: graphql proxy upstream unreachable: {}", e.toString())
                    bosca.bml.graphql.GraphQLForwardResponse(502, """{"errors":[{"message":"GraphQL upstream unreachable"}]}""")
                }
                forwarded.headers.forEach { (name, value) -> call.response.header(name, value) }
                call.respondBytes(
                    forwarded.body.toByteArray(Charsets.UTF_8),
                    ContentType.Application.Json,
                    bosca.server.HttpStatusCode.fromValue(forwarded.status),
                )
        }
        // Deferred island render: the cached page carries only a public descriptor and fallback.
        // This request resolves the caller's CURRENT identity, creates an isolated RenderContext,
        // and renders only the compiler-registered island body. Every outcome is private/no-store.
        router.post("/_bml/deferred/{renderer}") {
                call.response.header(HttpHeaders.CacheControl, "private, no-store")
                val current = state.get()
                val rendererId = call.pathParameters["renderer"].orEmpty()
                val renderer = current.deferredById[rendererId]
                if (renderer == null) {
                    call.respond(bosca.server.HttpStatusCode.NotFound, "Unknown deferred island")
                    return@post
                }
                val request = bosca.bml.render.parseDeferredRenderRequest(call.request.bodyText())
                if (request == null) {
                    call.respond(bosca.server.HttpStatusCode.UnprocessableEntity, "Invalid deferred island request")
                    return@post
                }
                val page = current.pagesByRoute[request.page]
                val pathParameters = page?.takeIf { current.pageOwns(renderer, it) }
                    ?.let { Router.matchPath(it.route, request.path) }
                if (page == null || pathParameters == null) {
                    call.respond(bosca.server.HttpStatusCode.UnprocessableEntity, "Deferred island does not belong to this page")
                    return@post
                }
                val token = resolveToken(current, call.request)
                if (page.requiresAuth && token == null) {
                    call.respond(bosca.server.HttpStatusCode.Unauthorized, "Authentication required")
                    return@post
                }
                val installationId = existingInstallationId(call.request)
                val needsSession = current.deferredNeedsSession(renderer)
                val session = if (needsSession) {
                    resolveSession(call.request, createIfMissing = false)
                } else {
                    null
                }
                if (needsSession && session == null) {
                    respondSessionExpired(call)
                    return@post
                }
                val localePolicy = current.localePolicy.current()
                val locale = request.locale?.let(localePolicy::match)
                    ?: localePolicy.resolve(
                        lang = request.query["lang"],
                        cookie = call.request.cookies[bosca.bml.render.BML_LOCALE_COOKIE],
                        acceptLanguage = call.request.acceptLanguage(),
                    )
                val ctx = RenderContext(
                    gql = current.gqlFactory?.forToken(token, installationId, call.existingAnalyticsSessionId),
                    token = token,
                    params = pathParameters,
                    cookies = installationCookies(call.request.cookies.all, installationId),
                    query = request.query,
                    locale = locale,
                    messages = current.messageSource,
                    inlineStyles = current.inlineComponentStyles,
                    session = session,
                ).apply { requestPath = request.path }
                try {
                    withRenderContext(ctx) { renderer.render(ctx, request.props) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: bosca.bml.render.InvalidBmlDeferredPropsException) {
                    log.warn("bml: invalid props for deferred renderer '{}': {}", renderer.id, e.toString())
                    call.respond(bosca.server.HttpStatusCode.UnprocessableEntity, "Invalid deferred island props")
                    return@post
                } catch (e: Exception) {
                    log.error("bml: deferred renderer '{}' failed", renderer.id, e)
                    call.respond(bosca.server.HttpStatusCode.InternalServerError, "Deferred island render failed")
                    return@post
                }
                ctx.redirectTo?.let { location ->
                    call.response.header(DEFERRED_REDIRECT_HEADER, location)
                    call.respond(bosca.server.HttpStatusCode.Conflict, "Deferred island requested navigation")
                    return@post
                }
                if (ctx.notFound) {
                    call.respond(bosca.server.HttpStatusCode.NotFound, "Deferred island content not found")
                    return@post
                }
                session?.let { appendSessionCookie(call, it) }
                call.respondBytes(ctx.writer.toString().toByteArray(Charsets.UTF_8), ContentType.Text.Html)
        }
        // Sliver re-render: POST new state -> re-render one component server-side -> return its HTML.
        router.post("/_bml/render/{component}") {
                val current = state.get()
                val tag = call.pathParameters["component"].orEmpty()
                val renderer = current.componentRenderers[tag]
                if (renderer == null) {
                    log.warn("bml: sliver re-render requested for unknown component '{}'", tag)
                    call.respondBytes("<!-- unknown bml component: $tag -->".toByteArray(Charsets.UTF_8), ContentType.Text.Html)
                    return@post
                }
                val token = resolveToken(current, call.request) // passthrough so the component can fetch data
                val props = bosca.bml.render.propsFromJson(call.request.bodyText())
                val installationId = resolveInstallationId(call, current)
                val ctx = RenderContext( // TODO: several of these things could be computed in the RenderContext
                    gql = current.gqlFactory?.forToken(token, installationId, call.analyticsSessionId),
                    token = token,
                    cookies = installationCookies(call.request.cookies.all, installationId),
                    // The browser sends the page's selected locale as ?lang, which takes precedence
                    // over a cookie that may differ from a shared page's locale.
                    locale = resolveLocale(current, call.request),
                    messages = current.messageSource,
                    // A sliver is pure markup; the page already carries the component's CSS.
                    inlineStyles = false,
                )
                withRenderContext(ctx) { renderer.render(ctx, props) {} }
                call.respondBytes(ctx.writer.toString().toByteArray(Charsets.UTF_8), ContentType.Text.Html)
        }
        // Live-island action: POST {page, stateKey, method, state} -> run the @click method on the
        // state model (loaded from the session for a server scope, else from the posted JSON), re-render the
        // view, and return {state, html}. The client writes new client state back + swaps the view's children.
        // (page + stateKey pick the dispatcher: state keys are unique per page, not globally.)
        router.post("/_bml/action") {
                val current = state.get()
                val req = bosca.bml.render.parseActionRequest(call.request.bodyText())
                val page = current.pagesByRoute[req.page]
                if (page == null) {
                    log.warn("bml: action requested for unknown page '{}'", req.page)
                    call.respondBytes(
                        """{"error":"unknown action"}""".toByteArray(Charsets.UTF_8),
                        ContentType.Application.Json,
                        bosca.server.HttpStatusCode.NotFound,
                    )
                    return@post
                }
                val requestPath = req.path.ifBlank { req.page }
                val pathParameters = Router.matchPath(page.route, requestPath)
                if (pathParameters == null) {
                    log.warn("bml: action path '{}' does not match page '{}'", requestPath, page.route)
                    call.respondBytes(
                        """{"error":"invalid action path"}""".toByteArray(Charsets.UTF_8),
                        ContentType.Application.Json,
                        bosca.server.HttpStatusCode.UnprocessableEntity,
                    )
                    return@post
                }
                val token = resolveToken(current, call.request)
                if (page.requiresAuth && token == null) {
                    call.respond(bosca.server.HttpStatusCode.Unauthorized, "Authentication required")
                    return@post
                }
                // Page states resolve by route + key; a component state's key carries its per-instance
                // suffix ("<tag>.<provides>:<instance>") and resolves by prefix in the component registry.
                val pageDispatcher = current.effectiveIslandDispatchers[page.route]?.get(req.stateKey)
                val componentDispatcher = if (current.pageOwnsComponentState(page, req.stateKey)) {
                    current.effectiveComponentIslandDispatchers[req.stateKey.substringBefore(':')]
                } else {
                    null
                }
                val dispatcher = pageDispatcher ?: componentDispatcher
                if (dispatcher == null) {
                    log.warn("bml: action requested for unknown page/state key '{}' / '{}'", req.page, req.stateKey)
                    call.respondBytes(
                        """{"error":"unknown action"}""".toByteArray(Charsets.UTF_8),
                        ContentType.Application.Json,
                        bosca.server.HttpStatusCode.NotFound,
                    )
                    return@post
                }
                val key = req.stateKey
                val storageKey = when {
                    pageDispatcher != null -> bosca.bml.render.bmlPageSessionStateKey(requestPath, key)
                    dispatcher.siteScoped -> key
                    else -> bosca.bml.render.bmlPageSessionStateKey(requestPath, key)
                }
                // Server-scoped actions load the model from the cookie-identified session (sent automatically
                // by the browser); a missing/expired session or state entry is a 410. Client scope needs none.
                val session = if (dispatcher.serverScoped) {
                    resolveSession(call.request, createIfMissing = false)
                } else {
                    null
                }
                val actionSession = if (dispatcher.serverScoped) {
                    if (session == null) {
                        log.warn("bml: server-scoped action '{}' with missing/expired session", key)
                        respondSessionExpired(call)
                        return@post
                    }
                    val storedState = session.get(storageKey)
                    if (storedState == null) {
                        log.warn("bml: server-scoped action '{}' with expired state", key)
                        respondSessionExpired(call)
                        return@post
                    }
                    PrefetchedBmlSession(session, key, storageKey, storedState)
                } else {
                    null
                }
                val installationId = resolveInstallationId(call, current)
                val localePolicy = current.localePolicy.current()
                val locale = req.locale?.let(localePolicy::match)
                    ?: localePolicy.resolve(
                        lang = req.query["lang"],
                        cookie = call.request.cookies[bosca.bml.render.BML_LOCALE_COOKIE],
                        acceptLanguage = call.request.acceptLanguage(),
                    )
                val ctx = RenderContext(
                    gql = current.gqlFactory?.forToken(token, installationId, call.analyticsSessionId),
                    token = token,
                    params = pathParameters,
                    cookies = installationCookies(call.request.cookies.all, installationId),
                    query = req.query,
                    locale = locale,
                    messages = current.messageSource,
                    // A re-rendered view is pure markup; the page already carries the CSS.
                    inlineStyles = false,
                    session = actionSession,
                    renderLiveStateView = req.renderView,
                ).apply { this.requestPath = requestPath }
                val result = try {
                    withRenderContext(ctx) { dispatcher.dispatch(ctx, req.method, req.state, req.args, req.stateKey) }
                } catch (_: InvalidBmlClientStateException) {
                    log.warn("bml: rejected incompatible client state for page/state key '{}' / '{}'", req.page, req.stateKey)
                    call.respondBytes(
                        """{"error":"invalid client state"}""".toByteArray(Charsets.UTF_8),
                        ContentType.Application.Json,
                        bosca.server.HttpStatusCode.UnprocessableEntity,
                    )
                    return@post
                }
                session?.let { appendSessionCookie(call, it) }
                call.respondBytes(
                    bosca.bml.render.encodeActionResponse(result).toByteArray(Charsets.UTF_8),
                    ContentType.Application.Json,
                )
        }
        if (dev) {
            // SSE live-reload endpoint: announce the process + generation token, then send a new
            // token immediately after an in-process swap. Named `ping` heartbeats never fire the
            // client's onmessage. Registered as a REAL SSE route, NOT respondStreaming:
            // SSE sessions are exempt from the 5-minute streaming timeout and idle reaping, so a
            // tab's stream is never force-killed onto the EventSource reconnect treadmill — each
            // reconnect churns one of the browser's ~6 per-origin sockets, which every OTHER site
            // tab is competing for.
            router.sse(BmlDevReload.ENDPOINT) {
                try {
                    var generation = reloadGeneration.value
                    send("$bootId:$generation")
                    while (true) {
                        val next = withTimeoutOrNull(15_000.milliseconds) {
                            reloadGeneration.first { it != generation }
                        }
                        if (next == null) {
                            send("", event = BmlDevReload.HEARTBEAT_EVENT)
                        } else {
                            generation = next
                            send("$bootId:$generation")
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The tab navigated away or closed — the normal end of a reload stream.
                    log.debug("bml dev reload stream ended: {}", e.toString())
                }
            }
        }
    }

    /** The caller's passthrough token. API-style `Authorization` headers remain authoritative. */
    private fun resolveToken(current: RuntimeState, request: bosca.server.ServerRequest): String? =
        request.header("Authorization")
            ?: resolveCookieToken(current, request)

    private fun resolveCookieToken(current: RuntimeState, request: bosca.server.ServerRequest): String? {
        val cookies = request.cookies.all
        val cookieName = current.authCookiePrefix ?: bosca.bml.render.BOSCA_TOKEN_COOKIE
        return cookies[cookieName]?.removeSurrounding("\"")
    }

    private fun requestQuery(request: bosca.server.ServerRequest): Map<String, String> =
        request.queryParameters.names.associateWith { request.queryParameters[it].orEmpty() }

    /** Returns the Bosca-issued installation identity shared by SSR, browser flags, and analytics. */
    private suspend fun resolveInstallationId(
        call: bosca.server.ServerCall,
        current: RuntimeState,
    ): String? {
        val installationId = existingInstallationId(call.request)
            ?: current.installationEndpoint?.let { registerInstallation(it) }

        if (installationId == null) return null

        call.response.cookies.append(
            name = BML_INSTALLATION_COOKIE,
            value = installationId,
            maxAge = INSTALLATION_COOKIE_MAX_AGE_SECONDS,
            path = "/",
            secure = call.request.origin.scheme.equals("https", ignoreCase = true),
            httpOnly = false,
            sameSite = "Lax",
        )
        return installationId
    }

    private fun existingInstallationId(request: bosca.server.ServerRequest): String? =
        request.headers["X-Installation-ID"]
            ?.takeIf(INSTALLATION_ID::matches)
            ?: request.cookies[BML_INSTALLATION_COOKIE]
                ?.removeSurrounding("\"")
                ?.takeIf(INSTALLATION_ID::matches)

    /** Registers directly with the configured analytics endpoint; GraphQL origin is irrelevant. */
    private suspend fun registerInstallation(endpoint: String): String {
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .get()
            .build()
        installationHttpClient.newCall(request).await().use { response ->
            check(response.isSuccessful) {
                "Installation registration failed with HTTP ${response.code}"
            }
            return ((Json.parseToJsonElement(response.body.string()) as? JsonObject)?.get("id") as? JsonPrimitive)
                ?.contentOrNull
                ?.takeIf(INSTALLATION_ID::matches)
                ?: error("Installation registration returned an invalid id")
        }
    }

    /** Makes the resolved current-request id available without changing RenderContext's constructor ABI. */
    private fun installationCookies(cookies: Map<String, String>, installationId: String?): Map<String, String> =
        if (installationId == null || cookies[BML_INSTALLATION_COOKIE] == installationId) {
            cookies
        } else {
            cookies + (BML_INSTALLATION_COOKIE to installationId)
        }

    /** Restores the browser identity that a shared shell deliberately omitted before rendering a private fallback. */
    private suspend fun resolveAlternatePageIdentity(
        current: RuntimeState,
        call: bosca.server.ServerCall,
        token: String?,
        installationId: String?,
        restoreRequestIdentity: Boolean,
    ): RequestIdentity {
        if (!restoreRequestIdentity) return RequestIdentity(token, installationId)
        call.allowAnalyticsSessionCookie()
        call.analyticsSessionId
        return RequestIdentity(
            token = resolveToken(current, call.request),
            installationId = resolveInstallationId(call, current),
        )
    }

    /**
     * Per-request locale: explicit `?lang` override, else the site-managed
     * [bosca.bml.render.BML_LOCALE_COOKIE], else full `Accept-Language` negotiation (RFC 4647
     * lookup + filtering inside [bosca.bml.render.BmlLocales]) — always constrained to the
     * locales the site's localization project defines ([localePolicy]), falling back to the
     * project's source language.
     */
    private suspend fun resolveLocale(
        current: RuntimeState,
        request: bosca.server.ServerRequest,
    ): java.util.Locale =
        current.localePolicy.current().resolve(
            lang = request.queryParameters["lang"],
            cookie = request.cookies[bosca.bml.render.BML_LOCALE_COOKIE],
            acceptLanguage = request.acceptLanguage(),
        )

    private fun validatorForRevision(
        current: RuntimeState,
        request: BmlSharedCacheRequest,
        revision: BmlSharedCacheRevision,
    ): SharedCacheValidator {
        val material = buildString {
            fun part(value: String) {
                append(value.length).append(':').append(value).append('|')
            }
            part(current.assetCacheToken)
            part((current.installationEndpoint != null).toString())
            part(request.pageRoute)
            part(request.path)
            part(request.locale.toLanguageTag())
            request.params.toSortedMap().forEach { (key, value) ->
                part(key)
                part(value)
            }
            request.query.toSortedMap().forEach { (key, value) ->
                part(key)
                part(value)
            }
            part(revision.etag)
            revision.lastModified?.let { part(it.toString()) }
        }
        return SharedCacheValidator(etag = quotedDigest(ContentDigest.sha256(material)))
    }

    private fun etagForBody(body: ByteArray): String = quotedDigest(ContentDigest.sha256(body))

    private fun quotedDigest(digest: String): String = "\"${digest.removePrefix("sha256:")}\""

    private fun applySharedCacheHeaders(
        current: RuntimeState,
        call: bosca.server.ServerCall,
        policy: SharedCachePolicy,
        validator: SharedCacheValidator,
    ) {
        if (current.development) {
            setCacheControl(call, "no-store")
            return
        }
        setCacheControl(call, "public, max-age=0, must-revalidate")
        call.response.header(
            CLOUDFLARE_CDN_CACHE_CONTROL,
            "public, max-age=${policy.freshSeconds}, " +
                "stale-while-revalidate=${policy.staleWhileRevalidateSeconds}",
        )
        call.response.header(HttpHeaders.ETag, validator.etag)
    }

    private fun isNotModified(
        call: bosca.server.ServerCall,
        validator: SharedCacheValidator,
        allowWildcard: Boolean = true,
    ): Boolean {
        val header = call.request.header(HttpHeaders.IfNoneMatch) ?: return false
        return (allowWildcard && header.trim() == "*") || header.split(',').any { candidate ->
            weakEtag(candidate) == weakEtag(validator.etag)
        }
    }

    private fun weakEtag(value: String): String = value.trim().let { tag ->
        if (tag.startsWith("W/", ignoreCase = true)) tag.substring(2).trim() else tag
    }

    /**
     * A shared shell's locale comes from its URL only (`?lang`, else the default), never from the
     * visitor's cookie or Accept-Language, so every visitor of one URL receives the same bytes.
     */
    private suspend fun resolveSharedLocale(
        current: RuntimeState,
        request: bosca.server.ServerRequest,
    ): java.util.Locale =
        current.localePolicy.current().resolve(lang = request.queryParameters["lang"])

    /** Sets Cache-Control once; the response API appends headers, so later writers check the marker. */
    private fun setCacheControl(call: bosca.server.ServerCall, value: String) {
        call.response.header(HttpHeaders.CacheControl, value)
        call.attributes[CACHE_CONTROL_SET_ATTRIBUTE] = true
    }

    /**
     * Serves an in-process asset tier with a content validator. Content can change while the
     * process remains alive, so a process boot id is not a valid ETag for hot-swapped assets.
     */
    private fun respondInMemoryAsset(
        current: RuntimeState,
        call: bosca.server.ServerCall,
        cacheControl: String?,
        body: String,
        type: ContentType,
    ) {
        cacheControl?.let { call.response.header(HttpHeaders.CacheControl, it) }
        val bytes = body.toByteArray(Charsets.UTF_8)
        val etag = current.inMemoryAssetEtags.getOrPut(body) { etagForBody(bytes) }
        call.response.header(HttpHeaders.ETag, etag)
        val ifNoneMatch = call.request.header(HttpHeaders.IfNoneMatch)
        if (ifNoneMatch != null && (ifNoneMatch == "*" || ifNoneMatch.split(",").any { it.trim() == etag })) {
            call.respond(bosca.server.HttpStatusCode.NotModified)
        } else {
            call.respondBytes(bytes, type)
        }
    }

    private fun inlineYaml(): String = """
        bosca:
          server:
            port: $port
            development: true
    """.trimIndent()

    companion object {
        private val log = LoggerFactory.getLogger(BmlServer::class.java)
        private val hotSwapServers = mutableMapOf<Int, BmlServer>()

        /** Keeps Netty in the parent runtime while child-loaded application mains publish generations. */
        private fun startHotSwapOrReload(candidate: BmlServer): Boolean {
            synchronized(hotSwapServers) {
                val existing = hotSwapServers[candidate.port]
                if (existing != null) {
                    existing.hotSwapFrom(candidate)
                    return true
                }
                hotSwapServers[candidate.port] = candidate
            }
            Thread(
                {
                    try {
                        candidate.startBlocking()
                    } finally {
                        synchronized(hotSwapServers) {
                            if (hotSwapServers[candidate.port] === candidate) {
                                hotSwapServers.remove(candidate.port)
                            }
                        }
                    }
                },
                "bml-server-${candidate.port}",
            ).apply {
                isDaemon = false
                contextClassLoader = BmlServer::class.java.classLoader
                start()
            }
            return true
        }

        /** Cookie carrying the durable server-side live-state session id (HttpOnly: never read by client JS). */
        private const val BML_SESSION_COOKIE = "bml_session"
        /** Client-written one-shot marker requesting a fresh server-side live-state session. */
        private const val BML_SESSION_RESET_COOKIE = "bml_session_reset"
        private const val INSTALLATION_COOKIE_MAX_AGE_SECONDS = 365 * 24 * 60 * 60
        private val INSTALLATION_ID = Regex("^[A-Za-z0-9_-]{1,128}${'$'}")
        private const val SITEMAP_ROUTE = "/sitemap.xml"
        private const val PRODUCTION_ASSET_CACHE_CONTROL = "public, max-age=31536000, immutable"
        private const val CLOUDFLARE_CDN_CACHE_CONTROL = "Cloudflare-CDN-Cache-Control"
        private const val DEFERRED_REDIRECT_HEADER = "X-BML-Redirect"
        /** Call attribute recording that this response already carries a Cache-Control header. */
        private const val CACHE_CONTROL_SET_ATTRIBUTE = "bosca.bml.cache-control-set"
    }

    private fun appendSessionCookie(call: bosca.server.ServerCall, session: BmlSession) {
        call.response.cookies.append(
            name = BML_SESSION_COOKIE,
            value = session.id,
            maxAge = sessions.idleTimeout.inWholeSeconds.coerceIn(1, Int.MAX_VALUE.toLong()).toInt(),
            path = "/",
            httpOnly = true,
            sameSite = "Lax",
        )
        if (call.request.cookies[BML_SESSION_RESET_COOKIE] == "1") {
            call.response.cookies.append(
                name = BML_SESSION_RESET_COOKIE,
                value = "",
                maxAge = 0,
                path = "/",
                secure = call.request.origin.scheme.equals("https", ignoreCase = true),
                httpOnly = false,
                sameSite = "Lax",
            )
        }
    }

    /** Selects a live-state session, discarding the old identity's session after explicit client cleanup. */
    private suspend fun resolveSession(
        request: bosca.server.ServerRequest,
        createIfMissing: Boolean,
    ): BmlSession? {
        if (request.cookies[BML_SESSION_RESET_COOKIE] == "1") {
            return if (createIfMissing) sessions.create() else null
        }
        val existing = request.cookies[BML_SESSION_COOKIE]?.let { sessions.get(it) }
        return existing ?: if (createIfMissing) sessions.create() else null
    }

    /** Reuses the selected request session or creates one when an alternate response page needs it. */
    private suspend fun sessionForAlternatePage(
        current: RuntimeState,
        call: bosca.server.ServerCall,
        page: BmlPageRenderer,
        existingSession: BmlSession?,
    ): BmlSession? {
        if (!current.pageNeedsSession(page)) return null
        if (existingSession != null) return existingSession
        return resolveSession(call.request, createIfMissing = true)?.also { appendSessionCookie(call, it) }
    }

    private fun respondSessionExpired(call: bosca.server.ServerCall) {
        call.respondBytes(
            """{"error":"session expired"}""".toByteArray(Charsets.UTF_8),
            ContentType.Application.Json,
            bosca.server.HttpStatusCode.Gone,
        )
    }
}

/**
 * Produces one replica-stable token for every input bundled into a rendered shell. Generated page
 * and component revisions cover BML markup; bundled client files cover imported TypeScript output;
 * [buildId] ([bmlBuildId]) identifies the build, so a deploy that changes only Kotlin helpers,
 * services, or libraries still changes shared-shell validators. [CompiledProject.version] is
 * included too, but sites commonly leave it constant.
 */
internal fun bmlDeploymentCacheToken(
    project: CompiledProject,
    pages: List<BmlPageRenderer>,
    components: List<bosca.bml.render.BmlComponentInfo>,
    globalCss: String?,
    globalJs: String?,
    clientDir: java.io.File?,
    buildId: String = "",
): String {
    val material = buildString {
        fun part(value: String?) {
            val text = value.orEmpty()
            append(text.length).append(':').append(text).append('|')
        }
        part(project.name)
        part(project.version)
        part(buildId)
        project.pages.toSortedMap().forEach { (key, page) ->
            part(key)
            part(page.route)
            part(page.renderObject)
            part(page.prerendered.toString())
            part(page.prerenderedHtml)
            part(page.metadataId)
        }
        for (entries in listOf(project.islandBundles, project.stylesheets, project.assets)) {
            entries.toSortedMap().forEach { (key, value) ->
                part(key)
                part(value)
            }
        }
        part(project.discovery.sitemap)
        part(project.discovery.robots)
        part(project.discovery.llms)
        pages.sortedBy(BmlPageRenderer::route).forEach { page ->
            part(page.route)
            part(page.renderRevision)
            part(page.contentType)
            part(page.clientModule)
            page.componentTags.sorted().forEach(::part)
            page.deferredRenderers.map { it.id }.sorted().forEach(::part)
        }
        components.sortedBy { it.tag }.forEach { component ->
            part(component.tag)
            part(component.renderRevision)
            part(component.styles)
            part(component.clientModule)
            component.deps.sorted().forEach(::part)
        }
        part(globalCss)
        part(globalJs)
        clientDir?.takeIf { it.isDirectory }?.let { assetRoot ->
            assetRoot.walkTopDown()
                .filter { it.isFile }
                .sortedBy { it.relativeTo(assetRoot).invariantSeparatorsPath }
                .forEach { file ->
                    part(file.relativeTo(assetRoot).invariantSeparatorsPath)
                    part(ContentDigest.sha256(file.readBytes()))
                }
        }
    }
    return ContentDigest.sha256(material).removePrefix("sha256:")
}

/** Classpath resource written by the `io.bosca.bml` Gradle plugin's `bmlBuildId` task (the build time). */
internal const val BML_BUILD_ID_RESOURCE: String = "META-INF/bosca/bml/build-id"

/**
 * The build identity from [BML_BUILD_ID_RESOURCE]: the time of the build that produced the site.
 * Replicas of one build share it and every CI build changes it. Returns "" with a warning when the
 * site was not built with the `io.bosca.bml` plugin; the token then relies on
 * [CompiledProject.version] and the generated render revisions alone.
 */
internal fun bmlBuildId(loader: ClassLoader): String {
    val id = loader.getResourceAsStream(BML_BUILD_ID_RESOURCE)?.use { it.readBytes().decodeToString().trim() }
    if (id.isNullOrEmpty()) {
        LoggerFactory.getLogger(BmlServer::class.java).warn(
            "bml: no {} resource; shared-shell validators will not change on deploys that only change server code",
            BML_BUILD_ID_RESOURCE,
        )
        return ""
    }
    return id
}

/** Prepends the public BML runtime configuration before application client code executes. */
internal fun bmlGlobalJs(globalJs: String?, authCookiePrefix: String?): String? {
    val prefix = authCookiePrefix?.trim()?.takeIf(String::isNotEmpty) ?: return globalJs
    val runtimeConfig = "globalThis.bmlConfig=Object.freeze({\"authCookiePrefix\":${JsonPrimitive(prefix)}});"
    return listOfNotNull(runtimeConfig, globalJs).joinToString("\n")
}

/**
 * Keeps the action boundary's validated state in memory for the dispatcher, avoiding a second
 * distributed-cache read and the expiry race that would otherwise exist between validation and decode.
 */
private class PrefetchedBmlSession(
    private val delegate: BmlSession,
    private val logicalKey: String,
    private val storageKey: String,
    initialState: String,
) : BmlSession {
    override val id: String get() = delegate.id

    private var state: String = initialState

    override suspend fun get(key: String): String? = if (key == logicalKey) state else delegate.get(key)

    override suspend fun put(key: String, json: String) {
        delegate.put(if (key == logicalKey) storageKey else key, json)
        if (key == logicalKey) state = json
    }

    override suspend fun putIfAbsent(key: String, json: String): Boolean {
        val stored = delegate.putIfAbsent(if (key == logicalKey) storageKey else key, json)
        if (stored && key == logicalKey) state = json
        return stored
    }
}

/**
 * Development mode selected by the Gradle/CLI launchers. Deployments do not set either value, so
 * the normal server default remains production mode while `run` and `bmlDev` need no app-specific
 * constructor wiring.
 */
internal fun bmlDevelopmentMode(): Boolean =
    (System.getProperty("bml.dev") ?: System.getenv("BML_DEV"))?.equals("true", ignoreCase = true) == true

/** True only for the Gradle persistent launcher; ordinary `run` remains a blocking one-shot. */
internal fun bmlHotSwapMode(): Boolean =
    System.getProperty("bml.dev.hotswap")?.equals("true", ignoreCase = true) == true

/** Stages the server built by an application main until that main has returned successfully. */
internal object BmlHotSwapTransaction {
    private val active = ThreadLocal<Boolean>()
    private val staged = ThreadLocal<BmlServer>()

    fun begin() {
        check(active.get() != true) { "A BML hot-swap transaction is already active" }
        active.set(true)
    }

    fun stage(server: BmlServer): Boolean {
        if (active.get() != true) return false
        check(staged.get() == null) { "An application main may start only one BML server" }
        staged.set(server)
        return true
    }

    fun commit() {
        check(active.get() == true) { "No BML hot-swap transaction is active" }
        val server = checkNotNull(staged.get()) { "The application main did not start a BML server" }
        clear()
        server.activateHotSwapGeneration()
    }

    fun rollback() {
        clear()
    }

    private fun clear() {
        staged.remove()
        active.remove()
    }
}
