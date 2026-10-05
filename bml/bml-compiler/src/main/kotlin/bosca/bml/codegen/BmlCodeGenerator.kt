package bosca.bml.codegen

import bosca.bml.render.isSupportedDeferredPropType
import bosca.bml.parser.AttrInterpolation
import bosca.bml.parser.AttrPart
import bosca.bml.parser.AttrText
import bosca.bml.parser.Attribute
import bosca.bml.parser.BoundAttribute
import bosca.bml.parser.CommentNode
import bosca.bml.parser.Diagnostic
import bosca.bml.parser.Document
import bosca.bml.parser.ElementNode
import bosca.bml.parser.EventAttribute
import bosca.bml.parser.ForNode
import bosca.bml.parser.IfNode
import bosca.bml.parser.InterpolationNode
import bosca.bml.parser.Node
import bosca.bml.parser.RawKind
import bosca.bml.parser.Span
import bosca.bml.parser.RawTextNode
import bosca.bml.parser.Severity
import bosca.bml.parser.SpreadAttribute
import bosca.bml.parser.StaticAttribute
import bosca.bml.parser.TextNode
import bosca.bml.tags.DefaultTagRegistry
import bosca.bml.tags.BuiltinTags
import bosca.bml.tags.TagResolution

/** Result of generating Kotlin from a `.bml` document. */
data class CodeGenResult(
    val source: String,
    val diagnostics: List<Diagnostic>,
    val sourceMap: BmlSourceMap,
    /** Asset metadata for the generated page, when the document has one (metrics manifest). */
    val pageMeta: BmlPageMeta? = null,
    /** Asset metadata for each generated component (metrics manifest). */
    val componentMeta: List<BmlComponentMeta> = emptyList(),
    /** Localized messages harvested from `t`/`t:` markup (feeds the i18n manifest). */
    val i18n: List<BmlI18nEntry> = emptyList(),
)

internal data class SharedCacheComponentUsage(
    val requiresSession: Set<String>,
    val usesEagerFeatureFlags: Set<String>,
)

/**
 * lowers a parsed `.bml` page ([Document]) into Kotlin source — an
 * `object` with a `suspend fun render(ctx: RenderContext)` that writes HTML via
 * the `core-bml` render runtime. Embedded `<script server>` and `<inject>` declarations become the
 * function prologue (`provides`/injections → `val`s), `{ expr }` becomes escaped/raw writes,
 * and elements / `<for>` / `<if>` become writer calls.
 *
 * Pure (no kotlin-compiler dependency). The K2 plugin re-parses the
 * source and drives this lowering inside the compiler; the same output can also be
 * written to disk as generated `.kt` by the Gradle plugin.
 */
class BmlCodeGenerator(
    private val packageName: String,
    private val objectName: String,
    private val sourcePath: String,
    /** tag -> generated object name for components declared in OTHER files (cross-file instantiation). */
    components: Map<String, String> = emptyMap(),
    /** Stable digest of the compiler revision and complete `.bml` source. */
    private val sourceRevision: String = sourcePath,
    /** Component tags whose render closure contains deferred islands. */
    private val deferredComponentTags: Set<String> = emptySet(),
    /** Component tags whose render closure contains client scripts or declarative actions. */
    private val clientBehaviorComponentTags: Set<String> = emptySet(),
    /** Component tags whose full render closure requires server-session state. */
    private val sessionComponentTags: Set<String> = emptySet(),
    /** Component tags whose eager render closure evaluates request-identity feature flags. */
    private val featureFlagComponentTags: Set<String> = emptySet(),
    /** tag -> `<prop>` name -> the type a caller may pass (see StaticPropCoercion.acceptedPropTypes), for components declared in OTHER files. */
    componentPropTypes: Map<String, Map<String, String>> = emptyMap(),
) {
    private val sb = StringBuilder()
    private var indent = 0
    private val diagnostics = mutableListOf<Diagnostic>()
    private val registry = DefaultTagRegistry()

    /** Known component tags -> their generated object name (seeded with cross-file ones; this file's
     *  own `<component>` decls are added in [generate]). Used to lower `<tag …>` to a render call. */
    private val knownComponents = components.toMutableMap()
    private val knownComponentPropTypes = componentPropTypes.toMutableMap()

    /** Components declared in the document being generated; they share this file's imports. */
    private val localComponentTags = mutableSetOf<String>()

    /** Direct-child `<inject>` declarations already hoisted into their enclosing render scope. */
    private val hoistedInjections = mutableSetOf<Int>()

    /** When emitting inside a scoped component's body, the component tag whose scope marker
     *  (`data-bml-c="<tag>"`) every rendered element is stamped with; null outside a scoped component. */
    private var currentScope: String? = null

    /** While emitting a component body, that component's root element — it re-emits attributes forwarded by
     *  the instantiation (attribute fall-through). Null outside a component / for non-element roots. */
    private var fallthroughRoots: List<ElementNode> = emptyList()

    /** Component whose client setup owns the current root; emitted as a mount marker without a wrapper. */
    private var currentClientComponentTag: String? = null

    /** Component that owns islands in the current generated render body. */
    private var currentIslandOwnerComponentTag: String? = null

    /** Nesting depth of native SVG markup, where names such as `use` override BML structural tags. */
    private var svgDepth: Int = 0

    /**
     * Live-island state declared on the current page, keyed by binding name (== the state key and the
     * `@click` receiver). Empty for static pages. Computed once per page in [computeLiveStates] and read by
     * [emitIsland] (which islands are reactive views), [emitOpenTag] (which `@click`s wire), and the
     * dispatcher/island-object emitters. See [LiveState].
     */
    private var liveStates: Map<String, LiveState> = emptyMap()

    /** How to reference each live state's full wire key in the CURRENT emission context: a static
     *  literal expression for page states, the `__bmlKey_<name>` local inside a component's render
     *  body, or the `stateKey` parameter inside an island's `renderInner`. */
    private var stateKeyExprs: Map<String, String> = emptyMap()

    /** Backing session slot for each server-scoped state. Wire keys identify dispatchers; this map
     * separately qualifies page-owned session state by the concrete request path. */
    private var sessionStateKeyExprs: Map<String, String> = emptyMap()

    /** When set, the live-state `<script>`s are deferred and emitted just before `</body>` (so they land
     *  inside the document, not before `<html>`); the value is the page route for the identity marker. */
    private var pendingLiveScriptsRoute: String? = null

    /** Deferred declarations for the render unit currently being emitted, keyed by source offset. */
    private var deferredByOffset: Map<Int, DeferredDef> = emptyMap()

    /** Component tags whose local or transitive render closure contains a deferred island. */
    private var componentsUsingDeferred: Set<String> = emptySet()

    /** Component tags whose local or transitive render closure contains client behavior. */
    private var componentsUsingClientBehavior: Set<String> = emptySet()

    private var componentsRequiringSession: Set<String> = sessionComponentTags
    private var componentsUsingEagerFeatureFlags: Set<String> = featureFlagComponentTags

    // Source-map state: the 1-based generated line about to be written, the `.bml` line the
    // current node came from (null = no mapping, e.g. the package/import header), and the
    // accumulated generated→source line mappings.
    private var outputLine = 1
    private var currentSourceLine: Int? = null
    private val mappings = mutableListOf<BmlLineMapping>()

    // Asset metadata collected while emitting, for the metrics manifest.
    private var pageMeta: BmlPageMeta? = null
    private val componentMeta = mutableListOf<BmlComponentMeta>()

    // Localized messages harvested from t/t: markup, plus the one-key-one-message guard.
    private val i18nEntries = mutableListOf<BmlI18nEntry>()
    private val i18nByKey = HashMap<String, BmlI18nEntry>()

    fun generate(document: Document): CodeGenResult {
        // Top-level `<component tag="…">` declarations -> generated render objects + instantiation map.
        val componentDecls = document.nodes.filterIsInstance<ElementNode>()
            .filter { it.name == "component" && it.namespace == null }
        componentDecls.forEach { decl ->
            staticString(decl.attributes, "tag")?.let { tag ->
                knownComponents[tag] = componentObjectName(tag)
                knownComponentPropTypes[tag] = StaticPropCoercion.acceptedPropTypes(decl)
                localComponentTags += tag
            }
        }
        val pages = document.nodes.filterIsInstance<ElementNode>()
            .filter { it.name in ROUTABLE_TAGS && it.namespace == null }
        val page = pages.firstOrNull()
        val message = document.nodes.filterIsInstance<ElementNode>()
            .firstOrNull { it.name == "message" && it.namespace == null }
        val localComponents = componentDecls.mapNotNull { declaration ->
            staticString(declaration.attributes, "tag")?.let { it to declaration }
        }.toMap()
        componentsUsingDeferred = componentsUsingDeferredIslands(localComponents, deferredComponentTags)
        componentsUsingClientBehavior = componentsUsingClientBehavior(localComponents, clientBehaviorComponentTags)
        val sharedCacheUsage = componentsBlockingSharedCache(
            declarations = localComponents,
            componentTags = knownComponents.keys,
            inheritedSession = sessionComponentTags,
            inheritedFeatureFlags = featureFlagComponentTags,
        )
        componentsRequiringSession = sharedCacheUsage.requiresSession
        componentsUsingEagerFeatureFlags = sharedCacheUsage.usesEagerFeatureFlags
        if (page == null && message == null && componentDecls.isEmpty()) {
            diagnostics.add(Diagnostic(Severity.Error, "No <page>, <route>, <message>, or <component> declaration found in $sourcePath", document.span))
            return CodeGenResult("", diagnostics, BmlSourceMap(sourcePath, emptyList()))
        }
        if (pages.size > 1) {
            diagnostics.add(
                Diagnostic(
                    Severity.Error,
                    "A BML file may contain only one <page> or <route> declaration",
                    pages[1].span,
                ),
            )
            return CodeGenResult("", diagnostics, BmlSourceMap(sourcePath, emptyList()))
        }
        val renderUnits = listOfNotNull(page, message)
        if (renderUnits.size > 1) {
            diagnostics.add(Diagnostic(Severity.Error, "<page>/<route> and <message> cannot share a file ($sourcePath) — one render unit per file", document.span))
            return CodeGenResult("", diagnostics, BmlSourceMap(sourcePath, emptyList()))
        }
        if (message != null) {
            firstDeferredUsage(message.children, componentsUsingDeferred)?.let { usage ->
                diagnostics.add(
                    Diagnostic(
                        Severity.Error,
                        "Deferred islands are unavailable in <message>, including through components",
                        usage.span,
                    ),
                )
                return CodeGenResult("", diagnostics, BmlSourceMap(sourcePath, emptyList()))
            }
            firstFeatureFlagCondition(message.children)?.let { span ->
                diagnostics.add(
                    Diagnostic(
                        Severity.Error,
                        "Feature-flag conditions are unavailable in <message> because no recipient feature identity is defined",
                        span,
                    ),
                )
                return CodeGenResult("", diagnostics, BmlSourceMap(sourcePath, emptyList()))
            }
        }

        // `import` lines from any <script server> are hoisted to the file top (Kotlin imports can't
        // live inside render()). This is how a page brings domain/GraphQL types into scope so they can
        // be used in server logic and passed into components as typed props.
        val userImports = sortedSetOf<String>()
        collectServerImports(document.nodes, userImports)

        line("package $packageName")
        blank()
        line("import bosca.bml.render.RenderContext")
        // Always in scope for embedded server code: `currentRenderContext()` / `client()` are the
        // ambient (coroutine-context) alternative to threading `ctx` through site functions.
        line("import bosca.bml.render.client")
        line("import bosca.bml.render.currentLocale")
        line("import bosca.bml.render.currentRenderContext")
        // Localization: `t("key")` and the locale-aware formatters are callable in
        // every expression position without an import.
        line("import bosca.bml.i18n.t")
        line("import bosca.bml.i18n.formatCurrency")
        line("import bosca.bml.i18n.formatDate")
        line("import bosca.bml.i18n.formatDateTime")
        line("import bosca.bml.i18n.formatNumber")
        line("import bosca.bml.i18n.formatPercent")
        if (page != null || message != null) {
            line("import bosca.bml.annotations.BmlGenerated")
        }
        if (page != null) line("import bosca.bml.annotations.BmlPage")
        if (message != null) line("import bosca.bml.annotations.BmlMessage")
        userImports.forEach { line(it) }
        blank()

        componentDecls.forEach { emitComponent(it) }
        if (page != null) emitPage(page)
        if (message != null) emitMessage(message)

        return CodeGenResult(
            sb.toString(),
            diagnostics,
            BmlSourceMap(sourcePath, mappings.toList()),
            pageMeta,
            componentMeta.toList(),
            i18nEntries.toList(),
        )
    }

    /** Finds unsupported deferred markup or a component whose render closure contains it. */
    private fun firstDeferredUsage(
        nodes: List<Node>,
        componentsUsingDeferred: Set<String>,
    ): ElementNode? {
        for (node in nodes) when (node) {
            is ElementNode -> {
                if (isDeferredIsland(node) || node.namespace == null && node.name in componentsUsingDeferred) {
                    return node
                }
                firstDeferredUsage(node.children, componentsUsingDeferred)?.let { return it }
            }
            is ForNode -> firstDeferredUsage(node.children, componentsUsingDeferred)?.let { return it }
            is IfNode -> {
                node.branches.forEach { branch ->
                    firstDeferredUsage(branch.children, componentsUsingDeferred)?.let { return it }
                }
                node.elseChildren?.let { children ->
                    firstDeferredUsage(children, componentsUsingDeferred)?.let { return it }
                }
            }
            else -> Unit
        }
        return null
    }

    private fun emitPage(page: ElementNode) {
        // Routes carry `{name}` param placeholders, which the attribute lexer reads as
        // interpolations — reconstruct the literal pattern from the parts.
        val pathAttributeName = if (page.name == "route") "path" else "route"
        val pathAttribute = page.attributes.firstOrNull { it.name == pathAttributeName }
        val route = reconstructLiteral(page.attributes, pathAttributeName) ?: ""
        if (page.name == "route" && (pathAttribute == null || route.isBlank())) {
            diagnostics.add(
                Diagnostic(
                    Severity.Error,
                    "<route> requires a non-blank literal path attribute",
                    pathAttribute?.span ?: page.span,
                ),
            )
        }
        val contentTypeAttribute = page.attributes.firstOrNull { it.name == "contentType" }
        val contentType = staticString(page.attributes, "contentType")
        if (contentTypeAttribute != null && contentType.isNullOrBlank()) {
            diagnostics.add(
                Diagnostic(
                    Severity.Error,
                    "<${page.name}> contentType requires a non-blank literal HTTP media type",
                    contentTypeAttribute.span,
                ),
            )
        }

        val pageDeferred = collectDeferredDefs(page.children, pageRoute = route)
        if (pageDeferred.isNotEmpty()) {
            routeParams(route).filter { it in DEFERRED_RENDERER_LOCALS }.forEach { param ->
                diagnostics.add(
                    Diagnostic(
                        Severity.Error,
                        "Route parameter '{$param}' conflicts with a generated deferred-renderer local; rename it",
                        page.span,
                    ),
                )
            }
        }
        deferredByOffset = pageDeferred.associateBy { it.element.span.startOffset }
        val deferredUsage = firstDeferredUsage(page.children, componentsUsingDeferred)
        val pageRefs = collectComponentRefs(page.children)
        val eagerPageRefs = collectEagerComponentRefs(page.children)
        if (deferredUsage != null && !contentType.isNullOrBlank() && !isHtmlContentType(contentType)) {
            diagnostics.add(Diagnostic(Severity.Error, "Deferred islands are available only on HTML pages", deferredUsage.span))
        }

        // Removed page features: accepted by the grammar but with no generated behavior. Warn so a page
        // written against the old syntax does not silently render without its layout or title.
        page.attributes.forEach { attribute ->
            REMOVED_PAGE_ATTRIBUTES[attribute.name]?.let { advice ->
                diagnostics.add(
                    Diagnostic(Severity.Warning, "<${page.name}> ${attribute.name} has no effect; $advice", attribute.span),
                )
            }
        }

        val cacheAttribute = page.attributes.firstOrNull { it.name == "cache" }
        val cache = staticString(page.attributes, "cache")
        val maxAgeAttribute = page.attributes.firstOrNull { it.name == "maxAge" }
        val maxAge = staticString(page.attributes, "maxAge")?.trim()?.toLongOrNull()
        val staleWhileRevalidateAttribute = page.attributes.firstOrNull { it.name == "staleWhileRevalidate" }
        val staleWhileRevalidate = staticString(page.attributes, "staleWhileRevalidate")?.trim()?.toLongOrNull()
        if (cacheAttribute != null && cache != "shared") {
            diagnostics.add(Diagnostic(Severity.Error, "<${page.name}> cache must be `shared`", cacheAttribute.span))
        }
        if (cache == "shared" && (maxAge == null || maxAge <= 0)) {
            diagnostics.add(
                Diagnostic(
                    Severity.Error,
                    "<${page.name}> cache=\"shared\" requires a positive literal maxAge in seconds",
                    maxAgeAttribute?.span ?: cacheAttribute?.span ?: page.span,
                ),
            )
        } else if (cache != "shared" && maxAgeAttribute != null) {
            diagnostics.add(Diagnostic(Severity.Error, "<${page.name}> maxAge requires cache=\"shared\"", maxAgeAttribute.span))
        }
        if (staleWhileRevalidateAttribute != null && cache != "shared") {
            diagnostics.add(
                Diagnostic(
                    Severity.Error,
                    "<${page.name}> staleWhileRevalidate requires cache=\"shared\"",
                    staleWhileRevalidateAttribute.span,
                ),
            )
        } else if (staleWhileRevalidateAttribute != null && (staleWhileRevalidate == null || staleWhileRevalidate <= 0)) {
            diagnostics.add(
                Diagnostic(
                    Severity.Error,
                    "<${page.name}> staleWhileRevalidate requires a positive literal number of seconds",
                    staleWhileRevalidateAttribute.span,
                ),
            )
        }

        // Resolve live-island state (a serializable server binding driven by `@click`/`@submit`) before
        // emitting, so the render body, islands, and action markers below all see the same picture.
        liveStates = computeLiveStates(page)
        validateLiveStateKeys(liveStates, pageDeferred)
        stateKeyExprs = liveStates.mapValues { kstr(it.value.name) }
        sessionStateKeyExprs = liveStates.mapValues { (_, state) ->
            "bosca.bml.render.bmlPageSessionStateKey(ctx.requestPath, ${kstr(state.name)})"
        }
        val hasServerState = liveStates.values.any { it.serverScoped }
        val deferredHasServerState = pageDeferred.any { deferred ->
            deferred.liveStates.values.any { it.serverScoped }
        }
        val componentRequiresSession = pageRefs.any { it in componentsRequiringSession }
        val requiresAuth = page.attributes.filterIsInstance<StaticAttribute>().any { it.name == "requireAuth" }
        if (cache == "shared") {
            if (requiresAuth) {
                diagnostics.add(Diagnostic(Severity.Error, "A shared-cache page cannot requireAuth", page.span))
            }
            if (hasServerState || deferredHasServerState || componentRequiresSession) {
                diagnostics.add(Diagnostic(Severity.Error, "A shared-cache page cannot use server-session state in its render closure", page.span))
            }
            if (!contentType.isNullOrBlank() && !isHtmlContentType(contentType)) {
                diagnostics.add(Diagnostic(Severity.Error, "Shared caching is available only for HTML pages", page.span))
            }
            firstFeatureFlagCondition(page.children, skipDeferredIslands = true)?.let { span ->
                diagnostics.add(
                    Diagnostic(
                        Severity.Error,
                        "A shared-cache shell cannot evaluate request-identity feature flags; move the condition into a deferred island",
                        span,
                    ),
                )
            }
            if (eagerPageRefs.any { it in componentsUsingEagerFeatureFlags }) {
                diagnostics.add(
                    Diagnostic(
                        Severity.Error,
                        "A shared-cache shell cannot evaluate request-identity feature flags through a component",
                        page.span,
                    ),
                )
            }
        }

        line("@BmlGenerated(source = ${kstr(sourcePath)})")
        line("@BmlPage(route = ${kstr(route)})")
        // Implement BmlPageRenderer so bml-server registers the page directly on Bosca's router
        // (FQN supertype to avoid clashing with the imported @BmlPage annotation).
        line("public object $objectName : bosca.bml.render.BmlPageRenderer {")
        indent++
        line("override val route: String = ${kstr(route)}")
        line("override val renderRevision: String = ${kstr(sourceRevision)}")
        if (!contentType.isNullOrBlank()) {
            line("override val contentType: String = ${kstr(contentType)}")
        }
        // Live state needs the island runtime even when the author wrote no <script client>.
        val hasLocalClientRuntime = hasClientScript(page) || liveStates.isNotEmpty() || pageDeferred.isNotEmpty()
        val hasSharedComponentRuntime = cache == "shared" && pageRefs.any { tag ->
            tag in componentsUsingClientBehavior || tag in componentsUsingDeferred
        }
        val clientModule = "$objectName.js".takeIf { hasLocalClientRuntime || hasSharedComponentRuntime }
        if (clientModule != null) {
            line("override val clientModule: String = ${kstr(clientModule)}")
        }
        // The components this page renders, so the asset pipeline can link their CSS/JS chunks.
        if (pageRefs.isNotEmpty()) {
            line("override val componentTags: List<String> = listOf(${pageRefs.joinToString(", ") { kstr(it) }})")
        }
        if (eagerPageRefs != pageRefs) {
            line("override val eagerComponentTags: List<String> = listOf(${eagerPageRefs.joinToString(", ") { kstr(it) }})")
        }
        pageMeta = BmlPageMeta(objectName, route, clientModule, pageRefs)
        // One dispatcher per live state key; bml-server looks them up at POST /_bml/action/{stateKey}.
        if (liveStates.isNotEmpty() || pageDeferred.isNotEmpty()) {
            val dispatchers = liveStates.values.joinToString(", ") { it.dispatcherObject }
            line("override val islandActionDispatchers: List<bosca.bml.render.BmlIslandActionDispatcher> = listOf($dispatchers)")
        }
        if (hasServerState) line("override val hasServerState: Boolean = true")
        if (pageDeferred.isNotEmpty()) {
            line(
                "override val deferredRenderers: List<bosca.bml.render.BmlDeferredRenderer> = " +
                    "listOf(${pageDeferred.joinToString(", ") { it.objectName }})",
            )
        }
        if (cache == "shared" && maxAge != null && maxAge > 0) {
            line("override val sharedCacheMaxAgeSeconds: Long = ${maxAge}L")
            if (staleWhileRevalidate != null && staleWhileRevalidate > 0) {
                line("override val sharedCacheStaleWhileRevalidateSeconds: Long = ${staleWhileRevalidate}L")
            }
        }
        // `<page>/<route> … requireAuth` — presence attribute; the server redirects token-less callers.
        if (requiresAuth) {
            line("override val requiresAuth: Boolean = true")
        }
        line("override suspend fun render(ctx: RenderContext) {")
        indent++
        // Self-install the ambient context (no-op when the host already installed the same
        // instance) so ambient helpers — currentRenderContext()/t()/currentLocale() — work in
        // ANY host, not just ones that remembered to wrap.
        line("bosca.bml.render.withRenderContext(ctx) {")
        indent++
        line("val w = ctx.writer")
        // Bind route params (`/lists/{id}` -> `val id`) from the matched route so server scripts use them.
        for (param in routeParams(route)) {
            line("val $param: String = ctx.params[${kstr(param)}].orEmpty()")
        }
        currentSourceLine = page.span.startLine
        emitServerPrologue(page.children)
        // Live-state scripts belong inside the document. If the page has a <body>, defer them to just before
        // </body> (emitElement); otherwise (email / plain fragment) emit them inline here.
        if (liveStates.isNotEmpty() || pageDeferred.isNotEmpty()) {
            if (containsBody(page.children)) pendingLiveScriptsRoute = route else emitLiveStateScripts(route)
        }
        emitChildren(page.children)
        // Safety net: if a <body> was expected but never emitted, flush rather than drop the scripts.
        pendingLiveScriptsRoute?.let { emitLiveStateScripts(it); pendingLiveScriptsRoute = null }
        currentSourceLine = page.span.startLine
        indent--
        line("}")
        indent--
        line("}")
        // Each live state contributes a view-island object (the reactive view, rendered identically on
        // first paint and on re-render) and a dispatcher object (runs the `@click` method + persists).
        for (state in liveStates.values) {
            if (state.islandEl != null) {
                blank()
                emitIslandObject(state)
            }
            blank()
            emitDispatcher(state)
        }
        for (deferred in pageDeferred) {
            blank()
            emitDeferredRenderer(deferred)
        }
        indent--
        line("}")
        deferredByOffset = emptyMap()
    }

    // ── message templates ─────────────────────────────────────────────────────────

    private data class PushActionRegion(
        val element: ElementNode,
        val id: String,
        val isDefault: Boolean,
        val labelVariable: String,
    )

    /**
     * Canonical communication unit: `<message>` owns shared server code and contains sibling
     * `<email>` and/or `<push>` channel regions. Both channels render under one locale, payload,
     * message source, and published artifact version.
     */
    private fun emitMessage(message: ElementNode) {
        val key = messageKey(message, sourcePath)
        val emailElements = message.children.filterIsInstance<ElementNode>()
            .filter { it.name == "email" && it.namespace == null }
        val pushElements = message.children.filterIsInstance<ElementNode>()
            .filter { it.name == "push" && it.namespace == null }
        if (emailElements.size > 1 || pushElements.size > 1) {
            diagnostics.add(
                Diagnostic(
                    Severity.Error,
                    "<message> permits at most one <email> and one <push> region in $sourcePath",
                    message.span,
                ),
            )
            return
        }
        val emailRegion = emailElements.singleOrNull()
        val push = pushElements.singleOrNull()
        if (emailRegion == null && push == null) {
            diagnostics.add(
                Diagnostic(Severity.Error, "<message> requires an <email> or <push> region in $sourcePath", message.span),
            )
            return
        }
        val subjectEl = emailRegion?.children?.filterIsInstance<ElementNode>()
            ?.firstOrNull { it.name == "subject" && it.namespace == null }
        if (emailRegion != null && subjectEl == null) {
            diagnostics.add(
                Diagnostic(Severity.Error, "a message <email> region requires <subject> in $sourcePath", emailRegion.span),
            )
            return
        }
        emailRegion?.attributes?.forEach {
            diagnostics.add(Diagnostic(Severity.Error, "a message <email> region does not accept attributes", it.span))
        }
        val pushTitle = push?.children?.filterIsInstance<ElementNode>()
            ?.firstOrNull { it.name == "title" && it.namespace == null }
        val pushBody = push?.children?.filterIsInstance<ElementNode>()
            ?.firstOrNull { it.name == "body" && it.namespace == null }
        val pushActionElements = push?.children?.filterIsInstance<ElementNode>()
            ?.filter { it.name == "action" && it.namespace == null }
            .orEmpty()
        val pushActions = pushActionElements.mapIndexedNotNull { index, action ->
            val id = staticString(action.attributes, "id")?.takeIf { it.isNotBlank() }
            if (id == null) {
                diagnostics.add(
                    Diagnostic(Severity.Error, "a push <action> requires a non-blank literal id", action.span),
                )
                null
            } else {
                val isDefault = action.attributes.any { it is StaticAttribute && it.name == "default" }
                PushActionRegion(action, id, isDefault, "__bmlPushAction${index}Label")
            }
        }
        pushActions.groupBy { it.id }.filterValues { it.size > 1 }.forEach { (id, duplicates) ->
            diagnostics.add(
                Diagnostic(Severity.Error, "push action id '$id' is declared more than once", duplicates.last().element.span),
            )
        }
        if (pushActions.count { it.isDefault } > 1) {
            diagnostics.add(
                Diagnostic(Severity.Error, "a push region permits at most one default <action>", push?.span ?: message.span),
            )
        }
        pushActionElements.forEach { action ->
            action.attributes.filterNot { attribute ->
                attribute is StaticAttribute && attribute.name in setOf("id", "default", "t")
            }.forEach { attribute ->
                diagnostics.add(
                    Diagnostic(
                        Severity.Error,
                        "<action> supports a literal id, optional default presence attribute, and optional t localization key",
                        attribute.span,
                    ),
                )
            }
        }
        val pushImage = push?.children?.filterIsInstance<ElementNode>()
            ?.firstOrNull { it.name == "image" && it.namespace == null }
        val pushAttachments = push?.children?.filterIsInstance<ElementNode>()
            ?.firstOrNull { it.name == "attachments" && it.namespace == null }
        val pushConversation = push?.children?.filterIsInstance<ElementNode>()
            ?.firstOrNull { it.name == "conversation" && it.namespace == null }
        if (push != null && (pushTitle == null || pushBody == null)) {
            diagnostics.add(
                Diagnostic(Severity.Error, "<push> requires one <title> and one <body> region in $sourcePath", push.span),
            )
            return
        }
        val pushOptions = push?.attributes?.filterIsInstance<BoundAttribute>()
            ?.firstOrNull { it.name == "options" }
            ?.expression
        push?.attributes?.filterNot { it is BoundAttribute && it.name == "options" }?.forEach {
            diagnostics.add(
                Diagnostic(Severity.Error, "<push> supports only the optional :options=\"…\" binding", it.span),
            )
        }
        listOfNotNull(pushImage, pushAttachments, pushConversation).forEach { richTag ->
            richTag.attributes.forEach {
                diagnostics.add(Diagnostic(Severity.Error, "<${richTag.name}> does not accept attributes", it.span))
            }
            richTag.children.filterNot { it is CommentNode || it is TextNode && it.value.isBlank() }.forEach {
                diagnostics.add(Diagnostic(Severity.Error, "<${richTag.name}> must be empty", it.span))
            }
        }
        push?.children?.filterNot { node ->
            node === pushTitle || node === pushBody || node in pushActionElements || node === pushImage ||
                node === pushAttachments || node === pushConversation || node is CommentNode ||
                (node is TextNode && node.value.isBlank())
        }?.forEach {
            diagnostics.add(
                Diagnostic(
                    Severity.Error,
                    "<push> supports <title>, <body>, <action>, <image>, <attachments>, and <conversation>",
                    it.span,
                ),
            )
        }
        message.children.filterNot { node ->
            node === emailRegion || node === push || node is RawTextNode && node.kind == RawKind.ServerScript ||
                node is ElementNode && node.namespace == null && node.name == "inject" ||
                node is CommentNode || node is TextNode && node.value.isBlank()
        }.forEach {
            diagnostics.add(
                Diagnostic(
                    Severity.Error,
                    "<message> supports shared server scripts/injections plus <email> and <push> channel regions",
                    it.span,
                ),
            )
        }
        liveStates = emptyMap()
        stateKeyExprs = emptyMap()
        sessionStateKeyExprs = emptyMap()

        currentSourceLine = message.span.startLine
        line("@BmlGenerated(source = ${kstr(sourcePath)})")
        line("@BmlMessage(key = ${kstr(key)})")
        line("public object $objectName : bosca.bml.message.BmlMessageTemplate {")
        indent++
        line("override val key: String = ${kstr(key)}")
        if (emailRegion != null) line("override val supportsEmail: Boolean = true")
        if (push != null) line("override val supportsPush: Boolean = true")
        payloadSerializerExpr(message)?.let {
            line("override val payloadSerializer: kotlinx.serialization.KSerializer<*>? = $it")
        }
        line(
            "override suspend fun renderMessage(message: bosca.bml.message.BmlMessageContext): " +
                "bosca.bml.message.RenderedMessage {",
        )
        indent++
        line(
            "val ctx = RenderContext(gql = message.gql, token = message.token, " +
                "locale = java.util.Locale.forLanguageTag(message.locale), messages = message.messages)",
        )
        line("return bosca.bml.render.withRenderContext(ctx) {")
        indent++
        line("val w = ctx.writer")
        currentSourceLine = message.span.startLine
        emitServerPrologue(message.children)
        if (emailRegion != null) {
            line("val __bmlEmail = if (message.channel != bosca.bml.message.BmlMessageChannel.PUSH) {")
            indent++
            line("ctx.beginEmailCollection()")
            currentSourceLine = requireNotNull(subjectEl).span.startLine
            line("val __bmlSubject: String = (${textRegionExpr(subjectEl, "subject")}).trim()")
            emitChildren(emailRegion.children.filterNot { it === subjectEl })
            currentSourceLine = message.span.startLine
            line("val __bmlBody = w.toString()")
            line("bosca.bml.email.RenderedEmail(")
            indent++
            line("subject = __bmlSubject,")
            line("html = bosca.bml.render.EmailRenderer.render(__bmlBody, ctx.collectedEmailCss),")
            line("text = bosca.bml.render.PlainTextRenderer.render(__bmlBody),")
            line("images = ctx.collectedEmailImages,")
            indent--
            line(")")
            indent--
            line("} else null")
        }
        if (push != null) {
            line("val __bmlPush = if (message.channel != bosca.bml.message.BmlMessageChannel.EMAIL) {")
            indent++
            currentSourceLine = requireNotNull(pushTitle).span.startLine
            line("val __bmlPushTitle: String = (${textRegionExpr(pushTitle, "push title")}).trim()")
            currentSourceLine = requireNotNull(pushBody).span.startLine
            line("val __bmlPushBody: String = (${textRegionExpr(pushBody, "push body")}).trim()")
            pushActions.forEach { action ->
                currentSourceLine = action.element.span.startLine
                line(
                    "val ${action.labelVariable}: String = " +
                        "(${textRegionExpr(action.element, "push action", setOf("id", "default"))}).trim()",
                )
            }
            line("bosca.bml.message.RenderedPush(")
            indent++
            line("title = __bmlPushTitle,")
            line("body = __bmlPushBody,")
            line(
                "options = ${pushOptionsExpr(pushOptions, pushActions, pushImage != null, pushAttachments != null, pushConversation != null)},",
            )
            indent--
            line(")")
            indent--
            line("} else null")
        }
        currentSourceLine = message.span.startLine
        line("bosca.bml.message.RenderedMessage(")
        indent++
        if (emailRegion != null) line("email = __bmlEmail,")
        if (push != null) line("push = __bmlPush,")
        indent--
        line(")")
        indent--
        line("}")
        indent--
        line("}")
        indent--
        line("}")
    }

    /**
     * The `X.serializer()` expression of the unit's payload decode, when its server code
     * contains the canonical `message.payload(X.serializer())` form (fully-qualified or
     * import-resolved — hoisted imports keep it resolvable in the generated object). Null when
     * the unit takes no payload or declares it another way.
     */
    private fun payloadSerializerExpr(message: ElementNode): String? {
        fun scripts(nodes: List<Node>): Sequence<String> = nodes.asSequence().flatMap { node ->
            when (node) {
                is RawTextNode -> if (node.kind == RawKind.ServerScript) sequenceOf(node.content) else emptySequence()
                is ElementNode -> scripts(node.children)
                is ForNode -> scripts(node.children)
                is IfNode -> node.branches.asSequence().flatMap { scripts(it.children) } +
                    (node.elseChildren?.let { scripts(it) } ?: emptySequence())
                else -> emptySequence()
            }
        }
        for (content in scripts(message.children)) {
            val match = PAYLOAD_DECODE.find(content) ?: continue
            return "${match.groupValues[1]}.serializer()"
        }
        return null
    }

    /**
     * A plain-text channel region as a Kotlin expression. `t="key"` uses the same message source,
     * locale, authored fallback, and placeholder folding as localized markup in an email body.
     */
    private fun textRegionExpr(
        region: ElementNode,
        label: String,
        allowedAttributes: Set<String> = emptySet(),
    ): String {
        val localization = parseTMarkup(region)
        if (localization != null) {
            region.attributes.filterNot {
                it is StaticAttribute && (it.name == "t" || it.name in allowedAttributes)
            }.forEach {
                diagnostics.add(
                    Diagnostic(Severity.Error, "<$label> localization supports only t=\"key\"", it.span),
                )
            }
            val key = localization.key
            if (key == null) {
                diagnostics.add(Diagnostic(Severity.Error, "<$label> localization requires t=\"key\"", region.span))
                return rawTextRegionExpr(region, label)
            }
            val args = LinkedHashMap<String, String>()
            val message = foldMessage(region.children, null, args, region.span)
                ?: return rawTextRegionExpr(region, label)
            recordI18n(
                BmlI18nEntry(
                    key = key,
                    message = message,
                    placeholders = args.map { BmlI18nPlaceholder(it.key, it.value) },
                    origin = BmlI18nOrigin.ELEMENT,
                    file = sourcePath,
                    line = region.span.startLine,
                ),
                region.span,
            )
            return "bosca.bml.i18n.Messages.resolveOr(ctx.messages, ctx.locale, ${kstr(key)}, " +
                "${kstr(message)}, ${argsMapExpr(args)})"
        }
        return rawTextRegionExpr(region, label)
    }

    private fun rawTextRegionExpr(region: ElementNode, label: String): String {
        val parts = mutableListOf<String>()
        for (node in region.children) when (node) {
            is TextNode -> {
                val collapsed = node.value.replace(whitespaceRuns, " ")
                if (collapsed.isNotEmpty()) parts += kstr(collapsed)
            }
            is InterpolationNode -> parts += "(${node.expression})"
            is CommentNode -> Unit
            else -> diagnostics.add(
                Diagnostic(
                    Severity.Warning,
                    "<$label> supports only text and { expr } interpolations; ignoring markup",
                    node.span,
                ),
            )
        }
        return if (parts.isEmpty()) "\"\"" else parts.joinToString(" + ", prefix = "\"\" + ")
    }

    /** Localized actions and declarative rich tags shape the producer-owned options envelope. */
    private fun pushOptionsExpr(
        explicitOptions: String?,
        actions: List<PushActionRegion>,
        image: Boolean,
        attachments: Boolean,
        conversation: Boolean,
    ): String {
        val base = explicitOptions ?: "message.pushOptions"
        val selected = ".selectRichContent(image = $image, attachments = $attachments, conversation = $conversation)"
        return if (actions.isNotEmpty()) {
            val selections = actions.joinToString(", ", "listOf(", ")") { action ->
                "bosca.bml.message.BmlPushActionSelection(" +
                    "id = ${kstr(action.id)}, label = ${action.labelVariable}, isDefault = ${action.isDefault})"
            }
            "((($base) ?: bosca.bml.message.BmlPushOptions()).selectActions($selections))$selected"
        } else {
            "($base)?.selectActions(emptyList())?$selected"
        }
    }

    private val whitespaceRuns = Regex("\\s+")

    // ── live islands (declarative @event and programmatic server actions) ──────

    /** Mutually exclusive ownership/lifetime of a live model produced by a server script. */
    private enum class LiveStateScope {
        Page,
        ClientSession,
        ClientLocal,
        ServerSession,
    }

    /** A live-island state: a `@Serializable` server binding driven by `@click`/`@submit` actions. */
    private data class LiveState(
        val name: String,           // binding name == @click receiver (== the state key for page states)
        val type: String,           // concrete Kotlin type inferred from a constructor or declared by inject
        val scope: LiveStateScope,
        val clearOnSignOut: Boolean,
        val siteScoped: Boolean,    // stable across pages; may run without a view on the current page
        val methods: Map<String, ServerAction>, // action methods on it anywhere in the tree: wire name → parsed action
        val sourceLine: Int,
        val islandEl: ElementNode?, // optional for site state that only performs headless actions
        val islandName: String?,    // its data-bml-island name when a view exists
        val componentTag: String? = null,   // set when the state is declared inside a <component>
        val componentScope: String? = null, // the component's style-scope tag when it is scoped
        val keyExpr: String? = null,        // component per-instance key: the island's :key expression (props in scope)
    ) {
        val isComponentState: Boolean get() = componentTag != null
        val serverScoped: Boolean get() = scope == LiveStateScope.ServerSession
        val clientScope: String? get() = when (scope) {
            LiveStateScope.ClientSession -> "client-session"
            LiveStateScope.ClientLocal -> "client-local"
            else -> null
        }

        /** The dispatcher's registered key: the provides name, or a component state's `"<tag>.<provides>"` prefix. */
        val registeredKey: String get() = componentTag?.let { "$it.$name" } ?: name

        /** Kotlin expression producing THIS instance's full wire state key (evaluated with props in scope). */
        val instanceKeyExpr: String get() =
            componentTag?.let { tag ->
                if (siteScoped) "\"$tag.$name\""
                else keyExpr?.let { "\"$tag.$name:\" + ($it)" } ?: "\"$tag.$name\""
            } ?: "\"$name\""

        /** The `val` holding the instance key inside a component's render body. */
        val localKeyVal: String get() = "__bmlKey_$name"

        private val objectPrefix: String get() = componentTag?.let { componentObjectName(it).removeSuffix("Component") }.orEmpty()
        val islandObject: String get() = objectPrefix + islandObjectName(islandName ?: name)
        val dispatcherObject: String get() = objectPrefix + dispatcherObjectName(name)
    }

    /** One parsed declarative or programmatic server-action argument. */
    private sealed interface ActionArg {
        /** A render-time Kotlin expression, evaluated into the `data-bml-args` marker. */
        data class Expr(val code: String) : ActionArg

        /** A `form.<field>` reference, resolved by the client runtime from the form at submit time. */
        data class Field(val name: String) : ActionArg

        /** The server [bosca.bml.render.RenderContext], injected by the dispatcher. */
        data object Ctx : ActionArg
    }

    private data class ServerAction(val receiver: String, val method: String, val event: String, val args: List<ActionArg>) {
        /** The args that travel on the wire (everything but `ctx`), in declaration order. */
        val wireArgs: List<ActionArg> get() = args.filterNot { it is ActionArg.Ctx }
    }

    /** Runtime scheduling/persistence policy encoded by `@event.<modifier>` segments. */
    private data class ActionPolicy(
        val event: String,
        val debounceMs: Long? = null,
        val throttleMs: Long? = null,
        val coalesce: Boolean = false,
        val keepalive: Boolean = false,
        val flushOnPageHide: Boolean = false,
    )

    /**
     * Find a container's live states: a binding is live when it is (a) an action receiver and (b) either
     * a constructor-typed `provides` or an explicitly typed `<inject server>`. Page-owned state needs a
     * view `<island>` referencing it. Site-owned component state may be headless. Anything invalid gets a
     * diagnostic and degrades to a plain non-interactive binding. [componentTag] is set for a component;
     * page-scoped component instances need an island `:key`, while a site component has one stable key.
     */
    private fun computeLiveStates(
        container: ElementNode,
        componentTag: String? = null,
        componentScope: String? = null,
        siteScoped: Boolean = false,
    ): Map<String, LiveState> {
        // 1. server binding → state type/lifetime from a direct-child <script server provides> or
        // <inject server> (mirrors emitServerPrologue's scope). Scripts infer a type from their constructor;
        // injects already carry an explicit type.
        data class Provided(
            val body: String?,
            val type: String?,
            val scope: LiveStateScope,
            val clearOnSignOut: Boolean,
            val sourceLine: Int,
        )
        fun clearOnSignOut(attributes: List<Attribute>, name: String, scope: LiveStateScope): Boolean {
            val attribute = attributes.firstOrNull { it.name == "clear-on-sign-out" } ?: return false
            val enabled = attribute is StaticAttribute && attribute.value == null
            if (!enabled) {
                diagnostics.add(
                    Diagnostic(
                        Severity.Error,
                        "live state `$name` clear-on-sign-out is a bare presence marker",
                        attribute.span,
                    ),
                )
            } else if (scope !in setOf(LiveStateScope.ClientSession, LiveStateScope.ClientLocal)) {
                diagnostics.add(
                    Diagnostic(
                        Severity.Error,
                        "live state `$name` clear-on-sign-out requires `client-session` or `client-local` scope",
                        attribute.span,
                    ),
                )
            }
            return enabled
        }
        val provided = LinkedHashMap<String, Provided>()
        for (child in container.children) {
            if (child is RawTextNode && child.kind == RawKind.ServerScript) {
                val name = staticString(child.attributes, "provides") ?: continue
                child.attributes.firstOrNull { it.name == "persist" }?.let { obsolete ->
                    diagnostics.add(
                        Diagnostic(
                            Severity.Error,
                            "live state `$name` no longer supports `persist`; use `scope=\"page\"` (the default), `scope=\"client-session\"`, or `scope=\"client-local\"`",
                            obsolete.span,
                        ),
                    )
                }
                val scopeAttribute = child.attributes.firstOrNull { it.name == "scope" }
                val scope = when (val value = staticString(child.attributes, "scope")) {
                    null -> if (scopeAttribute == null) LiveStateScope.Page else null
                    "page" -> LiveStateScope.Page
                    "client-session" -> LiveStateScope.ClientSession
                    "client-local" -> LiveStateScope.ClientLocal
                    "server-session" -> LiveStateScope.ServerSession
                    else -> null
                } ?: run {
                    diagnostics.add(
                        Diagnostic(
                            Severity.Error,
                            "live state `$name` scope must be `page`, `client-session`, `client-local`, or `server-session`",
                            scopeAttribute?.span ?: child.span,
                        ),
                    )
                    LiveStateScope.Page
                }
                provided[name] = Provided(
                    body = child.content.trim(),
                    type = null,
                    scope = scope,
                    clearOnSignOut = clearOnSignOut(child.attributes, name, scope),
                    sourceLine = child.span.startLine,
                )
            } else if (child is ElementNode && child.namespace == null && child.name == "inject" && isServerInject(child)) {
                val name = staticString(child.attributes, "name")?.takeIf { it.isNotBlank() } ?: continue
                val type = staticString(child.attributes, "type")?.takeIf { it.isNotBlank() } ?: continue
                val scope = when (staticString(child.attributes, "scope")) {
                    "client-session" -> LiveStateScope.ClientSession
                    "client-local" -> LiveStateScope.ClientLocal
                    "server-session" -> LiveStateScope.ServerSession
                    else -> LiveStateScope.Page
                }
                provided[name] = Provided(
                    body = null,
                    type = type,
                    scope = scope,
                    clearOnSignOut = clearOnSignOut(child.attributes, name, scope),
                    sourceLine = child.span.startLine,
                )
            }
        }
        // 2. Declarative and programmatic actions across the subtree, grouped by receiver.
        val methodsByReceiver = LinkedHashMap<String, LinkedHashMap<String, ServerAction>>()
        fun collectActions(nodes: List<Node>, insideIsland: Boolean = false) {
            for (n in nodes) when (n) {
                is ElementNode -> {
                    if (isDeferredIsland(n)) continue
                    for (a in n.attributes) if (a is EventAttribute) {
                        parseAction(a.event.substringBefore('.'), a.expression)?.let { action ->
                            val existing = methodsByReceiver.getOrPut(action.receiver) { linkedMapOf() }.putIfAbsent(action.method, action)
                            if (existing != null && existing.wireArgs.size != action.wireArgs.size) {
                                diagnostics.add(Diagnostic(Severity.Error, "@${a.event}=\"${a.expression}\" passes ${action.wireArgs.size} argument(s) but an earlier action passes ${existing.wireArgs.size}; every call site of one method must pass the same argument count", a.span))
                            }
                        }
                    }
                    collectActions(n.children, insideIsland || n.namespace == null && n.name == "island")
                }
                is RawTextNode -> if (n.kind == RawKind.ClientScript) {
                    PROGRAMMATIC_ACTION.findAll(n.content).forEach { match ->
                        val receiver = match.groupValues[1]
                        val method = match.groupValues[2]
                        val scoped = n.attributes.any { it is StaticAttribute && it.name == "scoped" }
                        if (!insideIsland && !(scoped && componentTag != null)) {
                            diagnostics.add(
                                Diagnostic(
                                    Severity.Error,
                                    "programmatic action `$receiver.$method` requires an island script or a component's <script client scoped>",
                                    n.span,
                                ),
                            )
                            return@forEach
                        }
                        val rawArgs = match.groupValues[3].trim()
                        val args = if (rawArgs.isEmpty()) emptyList() else {
                            splitActionArguments(rawArgs).map { arg ->
                                if (arg.trim() == "ctx") ActionArg.Ctx else ActionArg.Expr("")
                            }
                        }
                        val action = ServerAction(receiver, method, "programmatic", args)
                        val existing = methodsByReceiver.getOrPut(receiver) { linkedMapOf() }.putIfAbsent(method, action)
                        if (existing != null && existing.wireArgs.size != action.wireArgs.size) {
                            diagnostics.add(
                                Diagnostic(
                                    Severity.Error,
                                    "programmatic action `$receiver.$method` passes ${action.wireArgs.size} argument(s) but another call site passes ${existing.wireArgs.size}",
                                    n.span,
                                ),
                            )
                        }
                    }
                }
                is ForNode -> collectActions(n.children, insideIsland)
                is IfNode -> {
                    n.branches.forEach { collectActions(it.children, insideIsland) }
                    n.elseChildren?.let { collectActions(it, insideIsland) }
                }
                else -> Unit
            }
        }
        collectActions(container.children)
        if (methodsByReceiver.isEmpty()) return emptyMap()

        // 3. each constructor-typed receiver with a view island becomes a live state.
        val islands = collectIslands(container.children)
        val result = LinkedHashMap<String, LiveState>()
        for ((receiver, methods) in methodsByReceiver) {
            val prov = provided[receiver]
            if (prov == null) {
                if (methods.values.any { it.event == "programmatic" }) {
                    diagnostics.add(
                        Diagnostic(
                            Severity.Error,
                            "programmatic action receiver `$receiver` has no matching constructor-typed `provides`",
                            container.span,
                        ),
                    )
                }
                continue // declarative receivers receive their element-anchored error in emitOpenTag
            }
            val type = prov.type ?: constructorType(prov.body.orEmpty())
            if (type == null) {
                diagnostics.add(Diagnostic(Severity.Error, "action receiver `$receiver` is not a constructor-typed `provides`; live action cannot be generated", container.span))
                continue
            }
            if (siteScoped && prov.scope == LiveStateScope.Page) {
                diagnostics.add(
                    Diagnostic(
                        Severity.Error,
                        "site-scoped component state `$receiver` must use `client-session`, `client-local`, or `server-session` scope",
                        container.span,
                    ),
                )
                continue
            }
            val views = islands.filter { islandReferences(it, receiver) }
            if (views.isEmpty() && !siteScoped) {
                diagnostics.add(Diagnostic(Severity.Error, "live state `$receiver` has no <island> view referencing it; live action cannot be generated", container.span))
                continue
            }
            if (views.size > 1) {
                diagnostics.add(Diagnostic(Severity.Error, "live state `$receiver` is referenced by multiple islands; one live state must have exactly one view island", container.span))
            }
            val islandEl = views.firstOrNull()
            val keyExpr = islandEl?.attributes?.filterIsInstance<BoundAttribute>()?.firstOrNull { it.name == "key" }?.expression
            if (componentTag != null && !siteScoped && keyExpr == null) {
                diagnostics.add(Diagnostic(Severity.Error, "component live state `$receiver` needs a per-instance `:key=\"…\"` on its <island>; live action cannot be generated", islandEl?.span ?: container.span))
                continue
            }
            if (siteScoped && keyExpr != null) {
                diagnostics.add(
                    Diagnostic(
                        Severity.Error,
                        "site-scoped component state `$receiver` has one stable component identity and must not declare an island :key",
                        islandEl.span,
                    ),
                )
                continue
            }
            result[receiver] = LiveState(
                name = receiver,
                type = type,
                scope = prov.scope,
                clearOnSignOut = prov.clearOnSignOut,
                siteScoped = siteScoped,
                methods = methods,
                sourceLine = prov.sourceLine,
                islandEl = islandEl,
                islandName = islandEl?.let { staticString(it.attributes, "name") ?: "island" },
                componentTag = componentTag,
                componentScope = componentScope,
                keyExpr = keyExpr,
            )
        }
        return result
    }

    /**
     * Emit the live-state `<script>`s (placed just before `</body>` — see [emitElement]): a page-identity
     * marker so an action POST routes to THIS page's dispatcher, a JSON state script per **client** state
     * (the round-tripped source of truth, optionally marked with a browser-storage scope), and a
     * `ctx.session?.putIfAbsent(...)` per **server** state (the model initializes missing session state but
     * never replaces a value already persisted by an action).
     */
    private fun emitLiveStateScripts(route: String) {
        // Page identity: state keys are unique per page, not globally — two pages may both define `counterModel`.
        // This is a fallback for renders outside bml-server; bml-server injects the canonical marker
        // (route, concrete path, and locale) into every HTML page, and the runtime prefers that one.
        line("w.markup(${kstr("<script type=\"application/json\" data-bml-page=\"$route\"></script>")})")
        for (state in liveStates.values) {
            if (state.serverScoped) {
                val sessionKey = sessionStateKeyExprs[state.name] ?: kstr(state.name)
                line("ctx.session?.putIfAbsent($sessionKey, bosca.bml.render.encodeState(${state.name}))")
            } else {
                val scope = state.clientScope?.let { " data-bml-scope=\"$it\"" }.orEmpty()
                val clear = " data-bml-clear-on-sign-out".takeIf { state.clearOnSignOut }.orEmpty()
                line("w.markup(${kstr("<script type=\"application/json\" data-bml-state-key=\"${state.name}\"$scope$clear>")})")
                line("w.raw(bosca.bml.render.encodeClientState(${state.name}))")
                line("w.markup(${kstr("</script>")})")
            }
        }
    }

    /**
     * A view island's inner-content renderer: `object <Name>Island { suspend fun renderInner(ctx, model) }`.
     * Called from [emitIsland] for first paint and from the dispatcher for re-render, so the SSR markup and
     * the swapped-in fragment are produced by identical code.
     */
    private fun emitIslandObject(state: LiveState) {
        val island = state.islandEl ?: return
        currentSourceLine = island.span.startLine
        line("public object ${state.islandObject} {")
        indent++
        line("@Suppress(\"UNUSED_PARAMETER\", \"UNUSED_VARIABLE\")")
        line("suspend fun renderInner(ctx: RenderContext, ${state.name}: ${state.type}, stateKey: String) {")
        indent++
        // Self-install the ambient context (no-op under a host/page that already did).
        line("bosca.bml.render.withRenderContext(ctx) {")
        indent++
        line("val w = ctx.writer")
        val previousScope = currentScope
        val previousKeys = stateKeyExprs
        val previousIslandOwner = currentIslandOwnerComponentTag
        // Island content renders at page scope — unless the island lives in a scoped component,
        // whose style marker its elements must keep carrying after a re-render swap.
        currentScope = state.componentScope
        currentIslandOwnerComponentTag = state.componentTag
        // Action markers inside the island reference the instance key that was posted/rendered.
        stateKeyExprs = previousKeys + (state.name to "stateKey")
        emitChildren(island.children.filterNot { it is RawTextNode && it.kind == RawKind.ClientScript })
        currentScope = previousScope
        stateKeyExprs = previousKeys
        currentIslandOwnerComponentTag = previousIslandOwner
        currentSourceLine = island.span.startLine
        indent--
        line("}")
        indent--
        line("}")
        indent--
        line("}")
    }

    /**
     * A state key's action dispatcher: decode the model (from the session for a server scope, else from the
     * posted client JSON), run the selected model method, re-render the view island into a fresh writer,
     * and persist (write back to the session for server scope, else return the new client JSON).
     */
    private fun emitDispatcher(state: LiveState) {
        currentSourceLine = state.sourceLine
        line("public object ${state.dispatcherObject} : bosca.bml.render.BmlIslandActionDispatcher {")
        indent++
        line("override val stateKey: String = ${kstr(state.registeredKey)}")
        line("override val serverScoped: Boolean = ${state.serverScoped}")
        line("override val siteScoped: Boolean = ${state.siteScoped}")
        line("override suspend fun dispatch(ctx: RenderContext, method: String, state: String, args: kotlinx.serialization.json.JsonArray, instanceKey: String): bosca.bml.render.IslandActionResult {")
        indent++
        if (state.serverScoped) {
            line("val ${state.name}: ${state.type} = bosca.bml.render.decodeState<${state.type}>(ctx.session?.get(instanceKey) ?: state)")
        } else {
            line("val ${state.name}: ${state.type} = bosca.bml.render.decodeClientState<${state.type}>(state)")
        }
        line("when (method) {")
        indent++
        for ((name, action) in state.methods) line("${kstr(name)} -> ${state.name}.${dispatchCall(action)}")
        indent--
        line("}")
        if (state.islandEl != null) {
            line("if (ctx.renderLiveStateView) ${state.islandObject}.renderInner(ctx, ${state.name}, instanceKey)")
        }
        val html = if (state.islandEl == null) "null" else "if (ctx.renderLiveStateView) ctx.writer.toString() else null"
        if (state.serverScoped) {
            line("ctx.session?.put(instanceKey, bosca.bml.render.encodeState(${state.name}))")
            line("return bosca.bml.render.IslandActionResult(null, $html)")
        } else {
            line("return bosca.bml.render.IslandActionResult(bosca.bml.render.encodeClientState(${state.name}), $html)")
        }
        indent--
        line("}")
        indent--
        line("}")
    }

    /**
     * The Kotlin invocation a dispatcher makes for one action: posted wire args decode positionally
     * via [bosca.bml.render.actionArg] (the model method's parameter types drive the decode through
     * reified inference), and `ctx` is injected in place.
     */
    private fun dispatchCall(action: ServerAction): String {
        var wireIndex = 0
        val params = action.args.map { arg ->
            if (arg is ActionArg.Ctx) "ctx" else "bosca.bml.render.actionArg(args, ${wireIndex++})"
        }
        return "${action.method}(${params.joinToString(", ")})"
    }

    /**
     * `counterModel.increment` / `list.remove(item.id, ctx)` / `wall.share(form.title, ctx)` →
     * receiver, method name for the wire marker, and the parsed argument list. Three argument kinds:
     * `ctx` (the [RenderContext], injected server-side by the dispatcher), `form.<field>` (resolved
     * by the client runtime from the submitted form), and any other Kotlin expression (evaluated at
     * render time into the element's `data-bml-args` marker).
     */
    private fun parseAction(event: String, expr: String): ServerAction? {
        val e = expr.trim()
        val paren = e.indexOf('(')
        val head = if (paren < 0) e else e.substring(0, paren)
        val dot = head.lastIndexOf('.')
        if (dot <= 0) return null
        val receiver = head.substring(0, dot).trim()
        val method = head.substring(dot + 1).trim()
        val argsRaw = when {
            paren < 0 -> ""
            !e.endsWith(")") -> return null
            else -> e.substring(paren + 1, e.length - 1).trim()
        }
        fun isIdent(s: String) = s.isNotEmpty() && s.all { it.isLetterOrDigit() || it == '_' }
        if (!isIdent(receiver) || !isIdent(method)) return null
        val args = if (argsRaw.isEmpty()) emptyList() else splitActionArguments(argsRaw).map { raw ->
            val a = raw.trim()
            when {
                a == "ctx" -> ActionArg.Ctx
                a.startsWith("form.") && isIdent(a.removePrefix("form.")) -> ActionArg.Field(a.removePrefix("form."))
                else -> ActionArg.Expr(a)
            }
        }
        return ServerAction(receiver, method, event, args)
    }

    /**
     * A constructor-call body (`CounterModel()` / `bosca.bml.sample.CounterModel(a, b)`) → its type; else
     * null. Mirrors the IDE's [BmlPageScope] heuristic: the callee (text before the first `(`) is a dotted
     * identifier path whose last segment is PascalCase, so `listOf(...)` is correctly excluded.
     */
    private fun constructorType(body: String): String? {
        val paren = body.indexOf('(')
        if (paren <= 0) return null
        val callee = body.substring(0, paren).trim()
        if (callee.isEmpty() || !callee.all { it.isLetterOrDigit() || it == '.' || it == '_' }) return null
        return if (callee.substringAfterLast('.').firstOrNull()?.isUpperCase() == true) callee else null
    }

    /** Whether [nodes] contain an HTML `<body>` element (so live-state scripts can be placed inside it). */
    private fun containsBody(nodes: List<Node>): Boolean = nodes.any { n ->
        when (n) {
            is ElementNode -> (n.namespace == null && n.name == "body") || containsBody(n.children)
            is ForNode -> containsBody(n.children)
            is IfNode -> n.branches.any { containsBody(it.children) } || (n.elseChildren?.let { containsBody(it) } ?: false)
            else -> false
        }
    }

    /** Every `<island>` in [nodes] (recursively), in document order. */
    private fun collectIslands(nodes: List<Node>): List<ElementNode> {
        val out = mutableListOf<ElementNode>()
        fun walk(ns: List<Node>) {
            for (n in ns) when (n) {
                is ElementNode -> {
                    if (isDeferredIsland(n)) continue
                    if (n.namespace == null && n.name == "island") out += n
                    walk(n.children)
                }
                is ForNode -> walk(n.children)
                is IfNode -> { n.branches.forEach { walk(it.children) }; n.elseChildren?.let { walk(it) } }
                else -> Unit
            }
        }
        walk(nodes)
        return out
    }

    /** Whether any expression inside [island] references the identifier [name] (a whole-word match). */
    private fun islandReferences(island: ElementNode, name: String): Boolean {
        val pattern = Regex("\\b" + Regex.escape(name) + "\\b")
        fun refs(nodes: List<Node>): Boolean {
            for (n in nodes) when (n) {
                is ElementNode -> {
                    for (a in n.attributes) {
                        val expr = when (a) {
                            is BoundAttribute -> a.expression
                            is SpreadAttribute -> a.expression
                            is EventAttribute -> a.expression
                            is StaticAttribute -> a.value.orEmpty().filterIsInstance<AttrInterpolation>().joinToString(" ") { it.expression }
                        }
                        if (pattern.containsMatchIn(expr)) return true
                    }
                    if (refs(n.children)) return true
                }
                is InterpolationNode -> if (pattern.containsMatchIn(n.expression)) return true
                is ForNode -> { if (pattern.containsMatchIn(n.iterable) || refs(n.children)) return true }
                is IfNode -> {
                    if (n.branches.any { pattern.containsMatchIn(it.condition) || refs(it.children) }) return true
                    if (n.elseChildren?.let { refs(it) } == true) return true
                }
                else -> Unit
            }
            return false
        }
        return refs(island.children)
    }

    // ── components ──────────────────────────────────────────────────

    private data class PropDef(val name: String, val type: String, val default: String?)

    private data class DeferredDef(
        val element: ElementNode,
        val name: String,
        val id: String,
        val objectName: String,
        val props: List<PropDef>,
        val fallback: List<Node>,
        /** False when the fallback was rejected; its content is then not emitted, to avoid follow-on errors. */
        val fallbackValid: Boolean,
        val body: List<Node>,
        val componentTags: List<String>,
        val pageRoute: String?,
        val ownerComponentTag: String?,
        val componentScope: String?,
        val componentSiteScoped: Boolean,
        val liveStates: Map<String, LiveState>,
    )

    /**
     * Action requests identify state by owner plus state key. Distinct eager and deferred render
     * scopes under that owner therefore cannot publish the same key: one dispatcher would silently
     * replace the other when the generated registry is built.
     */
    private fun validateLiveStateKeys(eager: Map<String, LiveState>, deferred: List<DeferredDef>) {
        val seen = eager.keys.toMutableSet()
        for (definition in deferred) {
            for (key in definition.liveStates.keys) {
                if (!seen.add(key)) {
                    diagnostics.add(
                        Diagnostic(
                            Severity.Error,
                            "live state `$key` is declared in more than one eager or deferred render scope",
                            definition.element.span,
                        ),
                    )
                }
            }
        }
    }

    /** Collects and validates every deferred declaration in one page/component render unit. */
    private fun collectDeferredDefs(
        nodes: List<Node>,
        pageRoute: String? = null,
        ownerComponentTag: String? = null,
        componentScope: String? = null,
        componentSiteScoped: Boolean = false,
    ): List<DeferredDef> {
        val result = mutableListOf<DeferredDef>()
        val names = mutableSetOf<String>()
        val objectNames = mutableSetOf<String>()
        val eagerIslandNames = eagerIslandNames(nodes)

        fun walk(current: List<Node>, insideControlFlow: Boolean = false, insideSvg: Boolean = false) {
            for (node in current) when (node) {
                is ElementNode -> {
                    if (isDeferredIsland(node)) {
                        if (insideSvg) {
                            diagnostics.add(
                                Diagnostic(
                                    Severity.Error,
                                    "Deferred islands cannot be declared inside <svg>; their boundary is an HTML <div>",
                                    node.span,
                                ),
                            )
                        }
                        if (insideControlFlow) {
                            diagnostics.add(
                                Diagnostic(
                                    Severity.Error,
                                    "Deferred islands cannot be declared inside <if> or <for>",
                                    node.span,
                                ),
                            )
                        }
                        val name = staticString(node.attributes, "name")?.takeIf(String::isNotBlank)
                        if (name == null) {
                            diagnostics.add(Diagnostic(Severity.Error, "A deferred <island> requires a non-blank literal name", node.span))
                            continue
                        }
                        if (!DEFERRED_NAME.matches(name)) {
                            diagnostics.add(
                                Diagnostic(
                                    Severity.Error,
                                    "Deferred island name '$name' may contain only letters, digits, '.', '_', and '-'",
                                    node.span,
                                ),
                            )
                        }
                        if (!names.add(name)) {
                            diagnostics.add(Diagnostic(Severity.Error, "Deferred island name '$name' is duplicated in this render unit", node.span))
                        }
                        if (name in eagerIslandNames) {
                            diagnostics.add(
                                Diagnostic(
                                    Severity.Error,
                                    "Deferred island name '$name' is also used by an eager island in this render unit",
                                    node.span,
                                ),
                            )
                        }
                        // Page renderers are nested in the page object, while component renderers
                        // are top-level declarations. Include the component in the latter's name so
                        // two components can use the same local deferred-island name safely.
                        val generatedName = ownerComponentTag
                            ?.let { componentDeferredObjectName(it, name) }
                            ?: deferredObjectName(name)
                        if (!objectNames.add(generatedName)) {
                            diagnostics.add(
                                Diagnostic(
                                    Severity.Error,
                                    "Deferred island name '$name' collides with another generated renderer name",
                                    node.span,
                                ),
                            )
                        }

                        val fallbacks = node.children.filterIsInstance<ElementNode>()
                            .filter { it.namespace == null && it.name == "fallback" }
                        if (fallbacks.size > 1) {
                            diagnostics.add(Diagnostic(Severity.Error, "A deferred island may declare only one <fallback>", fallbacks[1].span))
                        }
                        var fallbackValid = true
                        fallbacks.firstOrNull()?.let { fallback ->
                            firstDeferredUsage(fallback.children, componentsUsingDeferred)?.let { usage ->
                                fallbackValid = false
                                diagnostics.add(
                                    Diagnostic(
                                        Severity.Error,
                                        "Deferred islands cannot be declared inside <fallback>, including through components",
                                        usage.span,
                                    ),
                                )
                            }
                            firstFallbackClientBehavior(fallback.children, componentsUsingClientBehavior)?.let { usage ->
                                fallbackValid = false
                                diagnostics.add(
                                    Diagnostic(
                                        Severity.Error,
                                        "Deferred <fallback> content cannot contain <script client> or declarative actions",
                                        usage.span,
                                    ),
                                )
                            }
                        }
                        val propElements = node.children.filterIsInstance<ElementNode>()
                            .filter { it.namespace == null && it.name == "prop" }
                        // An untyped <prop> would default to Any?, which skips the compile-time wire-type check
                        // and turns a non-JSON value into a render failure on every request.
                        propElements.filter { staticString(it.attributes, "type") == null }.forEach { untyped ->
                            diagnostics.add(
                                Diagnostic(
                                    Severity.Error,
                                    "Deferred prop '${staticString(untyped.attributes, "name").orEmpty()}' needs an explicit type; " +
                                        "use its JSON-shaped type, such as String, List<...>, or Map<String, ...>",
                                    untyped.span,
                                ),
                            )
                        }
                        val props = propElements.mapNotNull(::parseProp)
                        val propNames = mutableSetOf<String>()
                        props.forEach { prop ->
                            if (!KOTLIN_IDENTIFIER.matches(prop.name) || prop.name in KOTLIN_KEYWORDS) {
                                diagnostics.add(Diagnostic(Severity.Error, "Deferred prop '${prop.name}' is not a valid Kotlin identifier", node.span))
                            }
                            if (prop.name in DEFERRED_RENDERER_LOCALS) {
                                diagnostics.add(
                                    Diagnostic(
                                        Severity.Error,
                                        "Deferred prop '${prop.name}' conflicts with a generated renderer local",
                                        node.span,
                                    ),
                                )
                            }
                            if (!propNames.add(prop.name)) {
                                diagnostics.add(Diagnostic(Severity.Error, "Deferred prop '${prop.name}' is declared more than once", node.span))
                            }
                            val input = node.attributes.firstOrNull { it.name == prop.name }
                            if (input == null && prop.default == null) {
                                diagnostics.add(
                                    Diagnostic(
                                        Severity.Error,
                                        "Deferred prop '${prop.name}' is required but the island has no '${prop.name}' input",
                                        node.span,
                                    ),
                                )
                            }
                            if (input != null && !isSupportedDeferredPropType(prop.type)) {
                                diagnostics.add(
                                    Diagnostic(
                                        Severity.Error,
                                        "Deferred prop '${prop.name}' type '${prop.type}' is not a supported wire type; " +
                                            "use its expanded JSON-shaped type, such as String, List<...>, or Map<String, ...>",
                                        input.span,
                                    ),
                                )
                            }
                            // An unsupported wire type is already reported; a second error about the
                            // same input's value would only repeat the problem.
                            if (input != null && isSupportedDeferredPropType(prop.type)) {
                                (typedPropInput(input, prop.name, prop.type, strict = true) as? StaticPropValue.Invalid)?.let { invalid ->
                                    diagnostics.add(
                                        Diagnostic(
                                            Severity.Error,
                                            "Deferred prop '${prop.name}' has type '${prop.type}', but ${invalid.reason}",
                                            input.span,
                                        ),
                                    )
                                }
                            }
                        }
                        val controls = setOf("name", "render", "hydrate", "client", "key")
                        node.attributes.forEach { attribute ->
                            when {
                                attribute.name in controls -> Unit
                                attribute.name in propNames && attribute !is SpreadAttribute && attribute !is EventAttribute -> Unit
                                else -> diagnostics.add(
                                    Diagnostic(
                                        Severity.Error,
                                        "Deferred island input '${attribute.name}' needs a matching <prop> declaration",
                                        attribute.span,
                                    ),
                                )
                            }
                        }
                        val body = node.children.filterNot {
                            it is ElementNode && it.namespace == null && it.name in setOf("fallback", "prop")
                        }
                        firstSlot(body)?.let { slot ->
                            diagnostics.add(
                                Diagnostic(
                                    Severity.Error,
                                    "<slot> is unavailable inside a deferred island because slot content cannot cross the deferred request boundary",
                                    slot.span,
                                ),
                            )
                        }
                        val owner = ownerComponentTag?.let { "component:$it" } ?: objectName
                        result += DeferredDef(
                            element = node,
                            name = name,
                            id = "$owner:$name",
                            objectName = generatedName,
                            props = props,
                            fallback = fallbacks.firstOrNull()?.children.orEmpty(),
                            fallbackValid = fallbackValid,
                            body = body,
                            componentTags = collectComponentRefs(body),
                            pageRoute = pageRoute,
                            ownerComponentTag = ownerComponentTag,
                            componentScope = componentScope,
                            componentSiteScoped = componentSiteScoped,
                            liveStates = computeLiveStates(
                                node,
                                componentTag = ownerComponentTag,
                                componentScope = componentScope,
                                siteScoped = componentSiteScoped,
                            ),
                        )
                        walk(body, insideControlFlow, insideSvg)
                    } else {
                        walk(node.children, insideControlFlow, insideSvg || node.name == "svg")
                    }
                }
                is ForNode -> walk(node.children, insideControlFlow = true, insideSvg = insideSvg)
                is IfNode -> {
                    node.branches.forEach { walk(it.children, insideControlFlow = true, insideSvg = insideSvg) }
                    node.elseChildren?.let { walk(it, insideControlFlow = true, insideSvg = insideSvg) }
                }
                else -> Unit
            }
        }
        walk(nodes)
        return result
    }

    /**
     * The Kotlin value an attribute supplies for a prop declared as [type], shared by deferred-island
     * inputs and component calls. Static text is converted to the declared type at compile time
     * (`count="3"` on an `Int` prop is the literal `3`); a bare attribute is `true`; text that starts
     * with a literal segment is string concatenation. Bound expressions, and static values made only
     * of interpolations, keep their expression type; they are marked for a [typedPropValue] check.
     *
     * When [type] is not one BML recognizes (an application class, alias, or interface), static text
     * or `true` may still be valid: `typealias Url = String` accepts `href="/docs"`. With [strict]
     * (deferred inputs, whose wire types are all recognized) that is an error; otherwise the value
     * passes through marked for the Kotlin check, which knows what the type really is.
     */
    private fun typedPropInput(input: Attribute, propName: String, type: String, strict: Boolean): StaticPropValue {
        val bindHint = "; bind a typed value with :$propName=\"…\""
        val recognized = strict || StaticPropCoercion.isRecognizedType(type)
        return when (input) {
            is BoundAttribute -> StaticPropValue.Code("(${input.expression})", needsTypeCheck = true)
            is StaticAttribute -> {
                val parts = input.value
                when {
                    parts == null -> when {
                        StaticPropCoercion.acceptsBareAttribute(type) -> StaticPropValue.Code("true")
                        recognized -> StaticPropValue.Invalid("a bare attribute supplies Boolean `true`$bindHint")
                        else -> StaticPropValue.Code("true", needsTypeCheck = true)
                    }
                    parts.all { it is AttrText } -> {
                        val text = parts.joinToString("") { (it as AttrText).value }
                        when (val value = StaticPropCoercion.coerceText(text, type)) {
                            StaticPropValue.Unsupported ->
                                if (recognized) StaticPropValue.Invalid("static text cannot express that type$bindHint")
                                else StaticPropValue.Code(kstr(text), needsTypeCheck = true)
                            else -> value
                        }
                    }
                    parts.size > 1 || parts.first() is AttrText -> when {
                        StaticPropCoercion.acceptsText(type) -> StaticPropValue.Code("(${attrConcatExpr(parts)})")
                        recognized -> StaticPropValue.Invalid("text containing an interpolation is a String$bindHint")
                        else -> StaticPropValue.Code("(${attrConcatExpr(parts)})", needsTypeCheck = true)
                    }
                    else -> StaticPropValue.Code("(${attrConcatExpr(parts)})", needsTypeCheck = true)
                }
            }
            is SpreadAttribute, is EventAttribute -> StaticPropValue.Unsupported
        }
    }

    /**
     * [expression] checked against its declared prop type by the Kotlin compiler. `kotlin.run` is
     * inline, so the check has no runtime cost.
     */
    private fun typedPropValue(expression: String, type: String): String = "kotlin.run<$type> { $expression }"

    private fun firstFallbackClientBehavior(
        nodes: List<Node>,
        clientBehaviorComponents: Set<String>,
    ): Node? {
        for (node in nodes) when (node) {
            is RawTextNode -> if (node.kind == RawKind.ClientScript) return node
            is ElementNode -> {
                if (node.attributes.any { it is EventAttribute } ||
                    node.namespace == null && node.name in clientBehaviorComponents
                ) {
                    return node
                }
                firstFallbackClientBehavior(node.children, clientBehaviorComponents)?.let { return it }
            }
            is ForNode -> firstFallbackClientBehavior(node.children, clientBehaviorComponents)?.let { return it }
            is IfNode -> {
                node.branches.forEach { branch ->
                    firstFallbackClientBehavior(branch.children, clientBehaviorComponents)?.let { return it }
                }
                node.elseChildren?.let { children ->
                    firstFallbackClientBehavior(children, clientBehaviorComponents)?.let { return it }
                }
            }
            else -> Unit
        }
        return null
    }

    private fun eagerIslandNames(nodes: List<Node>): Set<String> = buildSet {
        fun walk(current: List<Node>) {
            for (node in current) when (node) {
                is ElementNode -> {
                    if (isDeferredIsland(node)) {
                        walk(node.children.filterNot {
                            it is ElementNode && it.namespace == null && it.name in setOf("fallback", "prop")
                        })
                    } else {
                        if (node.namespace == null && node.name == "island") {
                            add(staticString(node.attributes, "name") ?: "island")
                        }
                        walk(node.children)
                    }
                }
                is ForNode -> walk(node.children)
                is IfNode -> {
                    node.branches.forEach { walk(it.children) }
                    node.elseChildren?.let(::walk)
                }
                else -> Unit
            }
        }
        walk(nodes)
    }

    private fun emitDeferredRenderer(def: DeferredDef) {
        val previousLive = liveStates
        val previousKeys = stateKeyExprs
        val previousSessionKeys = sessionStateKeyExprs
        val previousScope = currentScope
        val previousRoots = fallthroughRoots
        val previousClientComponent = currentClientComponentTag
        val previousIslandOwner = currentIslandOwnerComponentTag
        // The deferred declaration is a complete render scope. It cannot capture an enclosing
        // page/component model across the HTTP boundary, but state declared directly inside it
        // gets the same action/session behavior as an ordinary page or component render.
        liveStates = def.liveStates
        stateKeyExprs = if (def.ownerComponentTag == null) {
            liveStates.mapValues { (name) -> kstr(name) }
        } else {
            liveStates.mapValues { it.value.localKeyVal }
        }
        sessionStateKeyExprs = liveStates.mapValues { (name, state) ->
            when {
                def.ownerComponentTag == null ->
                    "bosca.bml.render.bmlPageSessionStateKey(ctx.requestPath, ${kstr(name)})"
                state.siteScoped -> state.localKeyVal
                else -> "bosca.bml.render.bmlPageSessionStateKey(ctx.requestPath, ${state.localKeyVal})"
            }
        }
        currentScope = def.componentScope
        fallthroughRoots = emptyList()
        currentClientComponentTag = null
        currentIslandOwnerComponentTag = def.ownerComponentTag

        line("public object ${def.objectName} : bosca.bml.render.BmlDeferredRenderer {")
        indent++
        line("override val id: String = ${kstr(def.id)}")
        def.pageRoute?.let { line("override val pageRoute: String = ${kstr(it)}") }
        def.ownerComponentTag?.let { line("override val ownerComponentTag: String = ${kstr(it)}") }
        if (def.componentTags.isNotEmpty()) {
            line("override val componentTags: List<String> = listOf(${def.componentTags.joinToString(", ") { kstr(it) }})")
        }
        if (liveStates.values.any { it.serverScoped }) line("override val hasServerState: Boolean = true")
        if (liveStates.isNotEmpty()) {
            val dispatchers = liveStates.values.joinToString(", ") { it.dispatcherObject }
            line("override val islandActionDispatchers: List<bosca.bml.render.BmlIslandActionDispatcher> = listOf($dispatchers)")
        }
        line("@Suppress(\"UNCHECKED_CAST\", \"UNUSED_PARAMETER\", \"UNUSED_VARIABLE\", \"NAME_SHADOWING\")")
        line("override suspend fun render(ctx: RenderContext, props: Map<String, Any?>) {")
        indent++
        // Route params live outside the render lambda so a prop with the same name shadows them while its
        // default can still read the route value (`<prop name="id" type="String" :default="id"/>`).
        def.pageRoute?.let { route ->
            for (param in routeParams(route).filterNot(DEFERRED_RENDERER_LOCALS::contains)) {
                line("val $param: String = ctx.params[${kstr(param)}].orEmpty()")
            }
        }
        line("bosca.bml.render.withRenderContext(ctx) {")
        indent++
        line("val w = ctx.writer")
        def.props.forEach { line(propLocal(it, deferred = true)) }
        if (def.ownerComponentTag != null) {
            for (state in liveStates.values.filter { it.serverScoped }) {
                line("val ${state.localKeyVal}: String = ${state.instanceKeyExpr}")
            }
        }
        emitServerPrologue(def.element.children)
        if (def.ownerComponentTag != null) {
            for (state in liveStates.values.filterNot { it.serverScoped }) {
                line("val ${state.localKeyVal}: String = ${state.instanceKeyExpr}")
            }
        }
        emitChildren(def.body)
        if (def.ownerComponentTag == null && liveStates.isNotEmpty()) {
            emitLiveStateScripts(def.pageRoute.orEmpty())
        } else {
            for (state in liveStates.values.filter { it.siteScoped }) {
                val keyExpr = stateKeyExprs.getValue(state.name)
                if (state.serverScoped) {
                    val sessionKey = sessionStateKeyExprs.getValue(state.name)
                    line("ctx.session?.putIfAbsent($sessionKey, bosca.bml.render.encodeState(${state.name}))")
                }
                line("if (ctx.useStateMarkerOnce($keyExpr)) {")
                indent++
                line("w.markup(${kstr("<script type=\"application/json\"")})")
                line("w.attr(\"data-bml-state-key\", $keyExpr)")
                val scope = state.clientScope?.let { " data-bml-scope=\"$it\"" }.orEmpty()
                val clear = " data-bml-clear-on-sign-out".takeIf { state.clearOnSignOut }.orEmpty()
                val server = " data-bml-server".takeIf { state.serverScoped }.orEmpty()
                line("w.markup(${kstr("$scope$clear data-bml-site$server>")})")
                if (!state.serverScoped) {
                    line("w.raw(bosca.bml.render.encodeClientState(${state.name}))")
                }
                line("w.markup(${kstr("</script>")})")
                indent--
                line("}")
            }
        }
        indent--
        line("}")
        indent--
        line("}")
        for (state in liveStates.values) {
            if (state.islandEl != null) {
                blank()
                emitIslandObject(state)
            }
            blank()
            emitDispatcher(state)
        }
        indent--
        line("}")

        liveStates = previousLive
        stateKeyExprs = previousKeys
        sessionStateKeyExprs = previousSessionKeys
        currentScope = previousScope
        fallthroughRoots = previousRoots
        currentClientComponentTag = previousClientComponent
        currentIslandOwnerComponentTag = previousIslandOwner
    }

    /** `<component tag="card">` -> `public object CardComponent { suspend fun render(ctx, props, slot) {…} }`. */
    private fun emitComponent(decl: ElementNode) {
        val tag = staticString(decl.attributes, "tag")
        if (tag == null) {
            diagnostics.add(Diagnostic(Severity.Error, "<component> requires a tag=\"…\"", decl.span))
            return
        }
        val stateScopeAttribute = decl.attributes.firstOrNull { it.name == "scope" }
        val stateScope = staticString(decl.attributes, "scope")
        if (stateScopeAttribute != null && stateScope !in setOf("page", "site")) {
            diagnostics.add(Diagnostic(Severity.Error, "<component> scope must be `page` or `site`", stateScopeAttribute.span))
        }
        val siteScoped = stateScope == "site"
        val props = decl.children.filterIsInstance<ElementNode>()
            .filter { it.name == "prop" && it.namespace == null }
            .mapNotNull(::parseProp)
        // `<style scoped>` blocks are hoisted out of the body, scoped to this component, and inlined
        // once per render; everything else (incl. a plain non-scoped `<style>`) renders normally.
        val scopedStyles = decl.children.filterIsInstance<RawTextNode>()
            .filter { it.kind == RawKind.Style && isScoped(it) }
        val body = decl.children.filterNot {
            (it is ElementNode && it.name == "prop" && it.namespace == null) ||
                (it is RawTextNode && it.kind == RawKind.Style && isScoped(it))
        }
        val isScopedComponent = scopedStyles.isNotEmpty()
        val scopedCss = scopedStyles.joinToString("\n") { CssScoper.scope(it.content, tag) }

        val deps = collectComponentRefs(body)
        val eagerDeps = collectEagerComponentRefs(body)
        // Scan the declaration, not just the body: `<prop :default="…">` expressions run on every eager render.
        val hasEagerFeatureFlags = firstFeatureFlagCondition(decl.children, skipDeferredIslands = true) != null
        val componentDeferred = collectDeferredDefs(
            body,
            ownerComponentTag = tag,
            componentScope = tag.takeIf { isScopedComponent },
            componentSiteScoped = siteScoped,
        )
        val previousDeferred = deferredByOffset
        deferredByOffset = componentDeferred.associateBy { it.element.span.startOffset }

        // Resolve the component's own live states (a server binding inside the component driven by
        // `@click`/`@submit` with a `:key`ed view island) — each instance gets its own state.
        val previousLive = liveStates
        val previousKeys = stateKeyExprs
        val previousSessionKeys = sessionStateKeyExprs
        liveStates = computeLiveStates(
            decl,
            componentTag = tag,
            componentScope = tag.takeIf { isScopedComponent },
            siteScoped = siteScoped,
        )
        validateLiveStateKeys(liveStates, componentDeferred)
        stateKeyExprs = liveStates.mapValues { it.value.localKeyVal }
        sessionStateKeyExprs = liveStates.mapValues { (_, state) ->
            if (state.siteScoped) state.localKeyVal
            else "bosca.bml.render.bmlPageSessionStateKey(ctx.requestPath, ${state.localKeyVal})"
        }
        val hasServerState = liveStates.values.any { it.serverScoped }

        currentSourceLine = decl.span.startLine
        // Implements BmlComponentRenderer so the server can re-render it by tag for a sliver update.
        line("public object ${componentObjectName(tag)} : bosca.bml.render.BmlComponentRenderer {")
        indent++
        if (isScopedComponent) {
            // Exposed for the asset pipeline (the per-component CSS chunk) AND inlined once per render below.
            line("public const val scope: String = ${kstr(tag)}")
            line("public val styles: String = ${kstr(scopedCss)}")
        }
        // Registry entry: scope/css for the per-component chunk, deps to expand a page's render
        // closure, and — when this file carries a `<script client>` or a live state — the bundled
        // module the server loads on every page that renders the component (the JS analog of the CSS
        // closure; live actions need the runtime's binder even with no author client code).
        run {
            val scopeArg = if (isScopedComponent) kstr(tag) else "null"
            val stylesArg = if (isScopedComponent) "styles" else "\"\""
            val depsArg = deps.joinToString(", ") { kstr(it) }
            val clientModule = "$objectName.js".takeIf {
                hasClientScript(decl) || liveStates.isNotEmpty() || componentDeferred.isNotEmpty()
            }
            val clientArg = clientModule?.let(::kstr) ?: "null"
            val eagerDepsArg = eagerDeps.joinToString(", ") { kstr(it) }
            val eagerDepsOverride = if (eagerDeps == deps) "" else ", eagerDeps = listOf($eagerDepsArg)"
            val featureFlagsOverride = if (hasEagerFeatureFlags) ", hasEagerFeatureFlags = true" else ""
            val deferredOverride = if (componentDeferred.isEmpty()) {
                ""
            } else {
                ", deferredRenderers = listOf(${componentDeferred.joinToString(", ") { it.objectName }})"
            }
            line("public val info: bosca.bml.render.BmlComponentInfo = bosca.bml.render.BmlComponentInfo(${kstr(tag)}, $scopeArg, $stylesArg, listOf($depsArg), $clientArg, $hasServerState$eagerDepsOverride$featureFlagsOverride, renderRevision = ${kstr(sourceRevision)}$deferredOverride)")
            componentMeta += BmlComponentMeta(tag, if (isScopedComponent) scopedCss else "", deps, clientModule)
        }
        // One dispatcher per live state, registered under the "<tag>.<binding>" prefix
        // (bml.generated.BmlIslands.componentDispatchers).
        if (liveStates.isNotEmpty()) {
            val dispatchers = liveStates.values.joinToString(", ") { it.dispatcherObject }
            line("override val islandActionDispatchers: List<bosca.bml.render.BmlIslandActionDispatcher> = listOf($dispatchers)")
        }
        if (componentDeferred.isNotEmpty()) {
            line(
                "override val deferredRenderers: List<bosca.bml.render.BmlDeferredRenderer> = " +
                    "listOf(${componentDeferred.joinToString(", ") { it.objectName }})",
            )
        }
        line("@Suppress(\"UNCHECKED_CAST\", \"UNUSED_PARAMETER\", \"UNUSED_VARIABLE\")")
        line("override suspend fun render(ctx: RenderContext, props: Map<String, Any?>, slot: suspend () -> Unit) {")
        indent++
        // Self-install the ambient context (no-op under a page render, which already did) so
        // sliver re-renders and custom hosts get ambient helpers without remembering to wrap.
        line("bosca.bml.render.withRenderContext(ctx) {")
        indent++
        line("val w = ctx.writer")
        // Inline the scoped CSS the first time this component renders in a given page (deduped by ctx).
        if (isScopedComponent) line("if (ctx.useStyleOnce(${kstr(tag)})) w.markup(\"<style>\" + styles + \"</style>\")")
        for (p in props) line(propLocal(p, deferred = false))
        // A server-scoped model needs its per-instance key before the prologue can restore it from
        // the session. Client-scoped state is initialized first so its island key may derive from
        // the model itself (for example, :key="profile.id") as well as from component props.
        for (state in liveStates.values.filter { it.serverScoped }) {
            line("val ${state.localKeyVal}: String = ${state.instanceKeyExpr}")
        }
        emitServerPrologue(decl.children)
        for (state in liveStates.values.filterNot { it.serverScoped }) {
            line("val ${state.localKeyVal}: String = ${state.instanceKeyExpr}")
        }
        currentSourceLine = decl.span.startLine
        val previousScope = currentScope
        val previousRoots = fallthroughRoots
        val previousClientComponent = currentClientComponentTag
        val previousIslandOwner = currentIslandOwnerComponentTag
        if (isScopedComponent) currentScope = tag
        // The component's first top-level element is its root: it re-emits any attributes the instantiation
        // forwards (e.g. a `@click` on `<button>` falls through onto the `<html:button>` here).
        fallthroughRoots = componentRootElements(body)
        currentClientComponentTag = tag.takeIf { hasScopedClientScriptOutsideIsland(decl.children) }
        currentIslandOwnerComponentTag = tag
        if (currentClientComponentTag != null && fallthroughRoots.isEmpty()) {
            diagnostics.add(Diagnostic(Severity.Error, "component <$tag> has scoped client code but no element root to mount", decl.span))
        }
        emitChildren(body)
        for (state in liveStates.values.filter { it.siteScoped }) {
            val keyExpr = stateKeyExprs.getValue(state.name)
            if (state.serverScoped) {
                val sessionKey = sessionStateKeyExprs.getValue(state.name)
                line("ctx.session?.putIfAbsent($sessionKey, bosca.bml.render.encodeState(${state.name}))")
            }
            line("if (ctx.useStateMarkerOnce($keyExpr)) {")
            indent++
            line("w.markup(${kstr("<script type=\"application/json\"")})")
            line("w.attr(\"data-bml-state-key\", $keyExpr)")
            val scope = state.clientScope?.let { " data-bml-scope=\"$it\"" }.orEmpty()
            val clear = " data-bml-clear-on-sign-out".takeIf { state.clearOnSignOut }.orEmpty()
            val server = " data-bml-server".takeIf { state.serverScoped }.orEmpty()
            line("w.markup(${kstr("$scope$clear data-bml-site$server>")})")
            if (!state.serverScoped) {
                line("w.raw(bosca.bml.render.encodeClientState(${state.name}))")
            }
            line("w.markup(${kstr("</script>")})")
            indent--
            line("}")
        }
        currentScope = previousScope
        fallthroughRoots = previousRoots
        currentClientComponentTag = previousClientComponent
        currentIslandOwnerComponentTag = previousIslandOwner
        currentSourceLine = decl.span.startLine
        indent--
        line("}")
        indent--
        line("}")
        indent--
        line("}")
        // Each live state contributes a view-island object + dispatcher, exactly like a page's.
        for (state in liveStates.values) {
            if (state.islandEl != null) {
                blank()
                emitIslandObject(state)
            }
            blank()
            emitDispatcher(state)
        }
        for (deferred in componentDeferred) {
            blank()
            emitDeferredRenderer(deferred)
        }
        liveStates = previousLive
        stateKeyExprs = previousKeys
        sessionStateKeyExprs = previousSessionKeys
        deferredByOffset = previousDeferred
        blank()
    }

    /** A `<style scoped>` raw block (the `scoped` boolean attribute marks it for component scoping). */
    private fun isScoped(node: RawTextNode): Boolean = node.attributes.any { it.name == "scoped" }

    /** The `bml-inline` marker: inline this style/img into the email itself. */
    private fun isBmlInline(attributes: List<Attribute>): Boolean =
        attributes.any { it is StaticAttribute && it.name == "bml-inline" }

    /**
     * `<img bml-inline src="logo.png">` — embed the bundled image in the email instead of
     * referencing it remotely. Lowers to `src="cid:<id>"` (the mailer attaches the asset as an
     * inline `multipart/related` part under that Content-ID) plus a collection call recording
     * that this render actually used the image — the call sits in the same control flow as the
     * tag, so an image inside a false `<if>` attaches nothing. The `src` must be a STATIC
     * `public/`-relative bundle path: remote URLs have nothing to attach, and interpolated
     * paths (`{ email.assetsUrl }/…`) are the remote-asset pattern — drop the prefix to inline.
     */
    private fun emitInlineImg(el: ElementNode) {
        val src = staticString(el.attributes, "src")
        if (src == null || src.startsWith("http://") || src.startsWith("https://") || src.startsWith("cid:")) {
            diagnostics.add(
                Diagnostic(
                    Severity.Error,
                    "<img bml-inline> requires a static, bundle-relative src (a public/ asset path like \"logo.png\") in $sourcePath",
                    el.span,
                ),
            )
            return
        }
        val source = src.removePrefix("/")
        val cid = source.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').lowercase()
        line("ctx.collectEmailImage(${kstr(cid)}, ${kstr(source)})")
        val lowered = el.copy(
            attributes = el.attributes.mapNotNull { attr ->
                when {
                    attr.name == "bml-inline" -> null
                    attr is StaticAttribute && attr.name == "src" ->
                        attr.copy(value = listOf(AttrText("cid:$cid")))
                    else -> attr
                }
            },
        )
        emitOpenTag(lowered)
    }

    private fun parseProp(el: ElementNode): PropDef? {
        val name = staticString(el.attributes, "name") ?: return null
        val type = staticString(el.attributes, "type") ?: "Any?"
        // `:default="expr"` is a Kotlin expression; `default="lit"` is a literal (quoted for String types).
        val boundDefault = el.attributes.filterIsInstance<BoundAttribute>().firstOrNull { it.name == "default" }?.expression
        val staticDefault = staticString(el.attributes, "default")
        val default = boundDefault ?: staticDefault?.let { StaticPropCoercion.staticDefault(it, type) }
        return PropDef(name, type, default)
    }

    private fun propLocal(p: PropDef, deferred: Boolean): String =
        if (p.default != null && deferred) {
            "val ${p.name}: ${p.type} = bosca.bml.render.deferredPropOrDefault<${p.type}>(props, ${kstr(p.name)}, ${kstr(p.type)}) { ${p.default} }"
        } else if (p.default != null) {
            "val ${p.name}: ${p.type} = (props[${kstr(p.name)}] as? ${p.type.removeSuffix("?")}) ?: (${p.default})"
        } else if (deferred) {
            "val ${p.name}: ${p.type} = bosca.bml.render.requiredDeferredProp<${p.type}>(props, ${kstr(p.name)}, ${kstr(p.type)})"
        } else {
            "val ${p.name}: ${p.type} = props[${kstr(p.name)}] as ${p.type}"
        }

    /** `<card title="x" :elevation="2">kids</card>` -> `CardComponent.render(ctx, mapOf(…)) { kids }`. */
    private fun emitComponentCall(el: ElementNode) {
        val obj = knownComponents.getValue(el.name)
        val propTypes = knownComponentPropTypes[el.name].orEmpty()
        val entries = el.attributes.mapNotNull { attribute ->
            componentPropEntry(el.name, attribute, propTypes[attribute.name]) ?: propEntry(attribute)
        }.toMutableList()
        // Attribute fall-through: a live `@click` on this component (e.g. `<button>` is the `button`
        // component) — and/or markers forwarded to it when this call is the enclosing component's own root —
        // ride a reserved prop the component re-emits onto its root element. No wrapper, no DOM noise.
        fallthroughEntry(el)?.let { entries.add(it) }
        val mapArgs = entries.joinToString(", ")
        if (el.children.isEmpty()) {
            line("$obj.render(ctx, mapOf($mapArgs)) {}")
        } else {
            line("$obj.render(ctx, mapOf($mapArgs)) {")
            indent++
            emitChildren(el.children)
            indent--
            currentSourceLine = el.span.startLine
            line("}")
        }
    }

    /**
     * The reserved `__bmlAttrs` map entry that forwards fall-through attributes into a component, or null.
     * Combines a live `@click` on the instantiation with anything the enclosing component forwarded (when
     * this call is that component's root), so attributes chain through nested component roots.
     */
    private fun fallthroughEntry(el: ElementNode): String? {
        val markersExpr = el.attributes.filterIsInstance<EventAttribute>().firstNotNullOfOrNull { actionMarkersExpr(it) }
        val forwarded = "(props[${kstr(FALLTHROUGH_KEY)}] as? String).orEmpty()"
        val isRoot = isFallthroughRoot(el)
        val clientMarker = currentClientComponentTag?.takeIf { isRoot }
            ?.let { kstr(" data-bml-component=\"$it\"") }
        val local = listOfNotNull(markersExpr, clientMarker).joinToString(" + ").ifEmpty { null }
        val expr = when {
            local != null && isRoot -> "$local + $forwarded"
            local != null -> local
            markersExpr != null -> markersExpr
            isRoot -> forwarded
            else -> return null
        }
        return "${kstr(FALLTHROUGH_KEY)} to ($expr)"
    }

    /**
     * One declared prop's value on a component call ([typedPropInput], non-strict): static text is
     * converted to a recognized declared type, and values that can never match a recognized type
     * (`count="three"` on an `Int`) are compile errors. Values for other types pass through. The value
     * is also checked against the declared type by Kotlin when that type resolves at this call site;
     * see [typeResolvesAtCallSite]. Returns null for undeclared attributes, which keep [propEntry].
     */
    private fun componentPropEntry(tag: String, attribute: Attribute, declaredType: String?): String? {
        if (declaredType == null) return null
        return when (val value = typedPropInput(attribute, attribute.name, declaredType, strict = false)) {
            is StaticPropValue.Code -> {
                val checked = if (value.needsTypeCheck && typeResolvesAtCallSite(tag, declaredType)) {
                    typedPropValue(value.expression, declaredType)
                } else {
                    value.expression
                }
                "${kstr(attribute.name)} to $checked"
            }
            is StaticPropValue.Invalid -> {
                diagnostics.add(
                    Diagnostic(
                        Severity.Error,
                        "Component <$tag> prop '${attribute.name}' has type '$declaredType', but ${value.reason}",
                        attribute.span,
                    ),
                )
                null
            }
            StaticPropValue.Unsupported -> null
        }
    }

    /**
     * Whether [declaredType] can be written in this generated file. A component declared in the same
     * `.bml` shares the file's hoisted imports, so its short type names resolve here. For a component
     * from another file only built-in and package-qualified names are safe: its short names may rely
     * on imports this file does not have. `Any`-typed props are left unchecked because every value fits.
     */
    private fun typeResolvesAtCallSite(tag: String, declaredType: String): Boolean {
        if (StaticPropCoercion.rawType(declaredType) == "Any") return false
        return tag in localComponentTags || StaticPropCoercion.resolvesWithoutImports(declaredType)
    }

    private fun propEntry(attr: Attribute): String? = when (attr) {
        is StaticAttribute -> {
            val v = attr.value
            when {
                v == null -> "${kstr(attr.name)} to true"
                v.all { it is AttrText } ->
                    "${kstr(attr.name)} to ${kstr(v.joinToString("") { (it as AttrText).value })}"
                else -> "${kstr(attr.name)} to (${attrConcatExpr(v)})"
            }
        }
        is BoundAttribute -> "${kstr(attr.name)} to (${attr.expression})"
        is SpreadAttribute -> null // spread onto a component is a follow-on
        is EventAttribute -> null // @click on a component instantiation is not a prop (v1: events are page-level)
    }

    private fun emitSlot(el: ElementNode) {
        if (staticString(el.attributes, "name") != null) {
            diagnostics.add(Diagnostic(Severity.Warning, "named slots are a follow-on; rendering the default slot", el.span))
        }
        line("slot()")
    }

    private data class ComponentCacheUsage(
        val deps: List<String>,
        val eagerDeps: List<String>,
        val requiresSession: Boolean,
        val usesEagerFeatureFlags: Boolean,
    )

    private fun analyzeSharedCacheComponents(
        declarations: Map<String, ElementNode>,
        inheritedSession: Set<String>,
        inheritedFeatureFlags: Set<String>,
    ): SharedCacheComponentUsage {
        val usage = declarations.mapValues { (tag, declaration) ->
            val siteScoped = staticString(declaration.attributes, "scope") == "site"
            val componentScope = tag.takeIf {
                declaration.children.filterIsInstance<RawTextNode>()
                    .any { child -> child.kind == RawKind.Style && isScoped(child) }
            }
            val eagerState = computeLiveStates(
                declaration,
                componentTag = tag,
                componentScope = componentScope,
                siteScoped = siteScoped,
            ).values.any { it.serverScoped }

            fun deferredState(nodes: List<Node>): Boolean = nodes.any { node ->
                when (node) {
                    is ElementNode -> if (isDeferredIsland(node)) {
                        computeLiveStates(
                            node,
                            componentTag = tag,
                            componentScope = componentScope,
                            siteScoped = siteScoped,
                        ).values.any { it.serverScoped } || deferredState(
                            node.children.filterNot {
                                it is ElementNode && it.namespace == null && it.name in setOf("fallback", "prop")
                            },
                        )
                    } else {
                        deferredState(node.children)
                    }
                    is ForNode -> deferredState(node.children)
                    is IfNode -> node.branches.any { deferredState(it.children) } ||
                        (node.elseChildren?.let(::deferredState) ?: false)
                    else -> false
                }
            }

            ComponentCacheUsage(
                deps = collectComponentRefs(declaration.children),
                eagerDeps = collectEagerComponentRefs(declaration.children),
                requiresSession = eagerState || deferredState(declaration.children),
                usesEagerFeatureFlags = firstFeatureFlagCondition(
                    declaration.children,
                    skipDeferredIslands = true,
                ) != null,
            )
        }

        val requiresSession = inheritedSession.toMutableSet()
        val usesEagerFeatureFlags = inheritedFeatureFlags.toMutableSet()
        usage.forEach { (tag, component) ->
            if (component.requiresSession) requiresSession += tag
            if (component.usesEagerFeatureFlags) usesEagerFeatureFlags += tag
        }
        do {
            var changed = false
            usage.forEach { (tag, component) ->
                if (tag !in requiresSession && component.deps.any { it in requiresSession }) {
                    requiresSession += tag
                    changed = true
                }
                if (tag !in usesEagerFeatureFlags && component.eagerDeps.any { it in usesEagerFeatureFlags }) {
                    usesEagerFeatureFlags += tag
                    changed = true
                }
            }
        } while (changed)
        return SharedCacheComponentUsage(requiresSession, usesEagerFeatureFlags)
    }

    companion object {
        private val ROUTABLE_TAGS = setOf("page", "route")
        private val KOTLIN_IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val DEFERRED_NAME = Regex("[A-Za-z0-9_.-]+")
        private val DEFERRED_RENDERER_LOCALS = setOf("ctx", "props", "w")
        /** Page attributes whose features were removed, with what to use instead. */
        private val REMOVED_PAGE_ATTRIBUTES = mapOf(
            "layout" to "pages own their full HTML document; share chrome through components",
            "title" to "put a <title> element in the page's <head>",
            "render" to "only <island> accepts render",
        )
        /** Request-identity feature evaluation through the render context. */
        private val FEATURE_FLAG_ACCESS =
            Regex("""(?:\bctx|\bcurrentRenderContext\s*\(\s*\))\s*\??\.\s*featureFlags\b""")
        private val KOTLIN_KEYWORDS = setOf(
            "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if",
            "in", "interface", "is", "null", "object", "package", "return", "super", "this",
            "throw", "true", "try", "typealias", "typeof", "val", "var", "when", "while",
        )

        /** The canonical payload-decode form for message units. */
        private val PAYLOAD_DECODE =
            Regex("""message\s*\.\s*payload\s*\(\s*([\w.]+)\s*\.\s*serializer\s*\(\s*\)""")

        /** `ctx.dispatch(model.method(args...), options?)` references generated server dispatchers. */
        private val PROGRAMMATIC_ACTION = Regex(
            """ctx\.dispatch\(\s*([A-Za-z_$][A-Za-z0-9_$]*)\.([A-Za-z_$][A-Za-z0-9_$]*)\s*(?:\(([^()]*)\))?""",
        )

        /** A component tag's generated object name: `card` -> `CardComponent`, `item-list` -> `ItemListComponent`. */
        fun componentObjectName(tag: String): String =
            tag.split('-', '_', '.', ' ').filter { it.isNotEmpty() }
                .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) } + "Component"

        /** A view island's generated object name: `counter` -> `CounterIsland`, `live-counter` -> `LiveCounterIsland`. */
        fun islandObjectName(name: String): String =
            name.split('-', '_', '.', ' ').filter { it.isNotEmpty() }
                .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) } + "Island"

        /** A deferred island renderer object's source-safe name. */
        fun deferredObjectName(name: String): String {
            val stem = name.split('-', '_', '.', ' ').filter { it.isNotEmpty() }
                .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
                .ifEmpty { "Island" }
            return (if (stem.first().isDigit()) "Island$stem" else stem) + "DeferredRenderer"
        }

        /** A component deferred renderer's owner-qualified source name. */
        fun componentDeferredObjectName(componentTag: String, name: String): String =
            componentObjectName(componentTag).removeSuffix("Component") + "_" + deferredObjectName(name)

        /**
         * Returns every declared or inherited component whose render closure contains a deferred
         * island. The fixed point catches wrapper components that only reference another affected
         * component, which matters because message templates cannot hydrate any deferred boundary.
         */
        fun componentsUsingDeferredIslands(
            declarations: Map<String, ElementNode>,
            inherited: Set<String> = emptySet(),
        ): Set<String> {
            val affected = inherited.toMutableSet()

            fun staticValue(element: ElementNode, name: String): String? {
                val attribute = element.attributes.filterIsInstance<StaticAttribute>()
                    .firstOrNull { it.name == name } ?: return null
                val value = attribute.value ?: return null
                return value.takeIf { parts -> parts.all { it is AttrText } }
                    ?.joinToString("") { (it as AttrText).value }
            }

            fun usesDeferred(nodes: List<Node>): Boolean = nodes.any { node ->
                when (node) {
                    is ElementNode -> {
                        val deferred = node.namespace == null && node.name == "island" &&
                            staticValue(node, "render") == "deferred"
                        deferred || node.namespace == null && node.name in affected || usesDeferred(node.children)
                    }
                    is ForNode -> usesDeferred(node.children)
                    is IfNode -> node.branches.any { usesDeferred(it.children) } ||
                        (node.elseChildren?.let(::usesDeferred) ?: false)
                    else -> false
                }
            }

            do {
                var changed = false
                declarations.forEach { (tag, declaration) ->
                    if (tag !in affected && usesDeferred(declaration.children)) {
                        affected += tag
                        changed = true
                    }
                }
            } while (changed)
            return affected
        }

        /** Returns every component whose render closure contains client scripts or declarative actions. */
        fun componentsUsingClientBehavior(
            declarations: Map<String, ElementNode>,
            inherited: Set<String> = emptySet(),
        ): Set<String> {
            val affected = inherited.toMutableSet()

            fun usesClientBehavior(nodes: List<Node>): Boolean = nodes.any { node ->
                when (node) {
                    is RawTextNode -> node.kind == RawKind.ClientScript
                    is ElementNode ->
                        node.attributes.any { it is EventAttribute } ||
                            node.namespace == null && node.name in affected ||
                            usesClientBehavior(node.children)
                    is ForNode -> usesClientBehavior(node.children)
                    is IfNode -> node.branches.any { usesClientBehavior(it.children) } ||
                        (node.elseChildren?.let(::usesClientBehavior) ?: false)
                    else -> false
                }
            }

            do {
                var changed = false
                declarations.forEach { (tag, declaration) ->
                    if (tag !in affected && usesClientBehavior(declaration.children)) {
                        affected += tag
                        changed = true
                    }
                }
            } while (changed)
            return affected
        }

        internal fun componentsBlockingSharedCache(
            declarations: Map<String, ElementNode>,
            componentTags: Set<String> = declarations.keys,
            inheritedSession: Set<String> = emptySet(),
            inheritedFeatureFlags: Set<String> = emptySet(),
        ): SharedCacheComponentUsage {
            if (declarations.isEmpty()) {
                return SharedCacheComponentUsage(inheritedSession, inheritedFeatureFlags)
            }
            val components = componentTags.associateWith(::componentObjectName)
            return BmlCodeGenerator("", "BmlComponentAnalysis", "", components)
                .analyzeSharedCacheComponents(declarations, inheritedSession, inheritedFeatureFlags)
        }

        /** A state key's generated dispatcher object name: `counterModel` -> `CounterModelStateDispatcher`. */
        fun dispatcherObjectName(stateKey: String): String =
            stateKey.replaceFirstChar(Char::uppercaseChar) + "StateDispatcher"

        /**
         * A `<message>` unit's stable key: its literal `key="…"` attribute, else the file's base
         * name. Shared with the compiler plugin's artifact manifest writer.
         */
        fun messageKey(message: ElementNode, sourcePath: String): String {
            val attr = message.attributes.filterIsInstance<StaticAttribute>().firstOrNull { it.name == "key" }
            val literal = attr?.value?.takeIf { parts -> parts.all { it is AttrText } }
                ?.joinToString("") { (it as AttrText).value }
            return literal ?: sourcePath.substringAfterLast('/').removeSuffix(".bml")
        }

        /** Reserved prop key carrying fall-through attributes from a component instantiation to its root. */
        const val FALLTHROUGH_KEY: String = "__bmlAttrs"

    }

    // ── server prologue ───────────────────────────────────────────────────────

    private fun emitServerPrologue(children: List<Node>) {
        // Dependencies belong to the whole render-unit scope, so resolve and initialize every direct
        // inject before evaluating any server-script initializer, independent of source placement.
        children.filterIsInstance<ElementNode>()
            .filter { it.namespace == null && it.name == "inject" }
            .forEach { inject ->
                hoistedInjections += inject.span.startOffset
                emitInject(inject)
            }
        for (child in children) {
            if (child is RawTextNode && child.kind == RawKind.ServerScript) {
                val provides = staticString(child.attributes, "provides")
                // Map each emitted body line back to its real `.bml` line so breakpoints inside
                // server Kotlin land where the author wrote them. `content` is `contentSpan` verbatim;
                // .trim() drops only the leading blank line(s), so offset from the first non-blank.
                val rawLines = child.content.lines()
                val firstNonBlank = rawLines.indexOfFirst { it.isNotBlank() }.coerceAtLeast(0)
                val bodyLines = child.content.trim().lines()
                fun emitBody() = bodyLines.forEachIndexed { i, text ->
                    if (text.trim().startsWith("import ")) return@forEachIndexed // hoisted to the file top
                    currentSourceLine = child.contentSpan.startLine + firstNonBlank + i
                    line(text.trim())
                }
                if (provides != null) {
                    currentSourceLine = child.span.startLine
                    // A server-scoped live state is durable: restore the stored model from the session
                    // (cookie-identified, persists across refreshes) and only construct a fresh one when
                    // the session has none yet. Other provides always evaluate their initializer.
                    val serverState = liveStates[provides]?.takeIf { it.serverScoped }
                    if (serverState != null) {
                        val keyExpr = sessionStateKeyExprs[provides] ?: stateKeyExprs[provides] ?: kstr(provides)
                        line("val $provides: ${serverState.type} = ctx.session?.get($keyExpr)?.let { bosca.bml.render.decodeState<${serverState.type}>(it) } ?: run {")
                    } else {
                        line("val $provides = run {")
                    }
                    indent++
                    emitBody()
                    indent--
                    currentSourceLine = child.span.startLine
                    line("}")
                } else {
                    emitBody()
                }
            }
        }
    }

    // ── nodes ─────────────────────────────────────────────────────────────────

    private fun emitChildren(nodes: List<Node>) {
        for (node in nodes) emitNode(node)
    }

    private fun emitNode(node: Node) {
        currentSourceLine = node.span.startLine
        when (node) {
            is TextNode -> if (node.value.isNotBlank()) line("w.markup(${kstr(node.value)})")
            is InterpolationNode ->
                if (node.raw) line("w.raw(${node.expression})") else line("w.text(${node.expression})")
            is CommentNode -> if (node.emitted) line("w.markup(${kstr("<!--${node.text}-->")})")
            is ForNode -> emitFor(node)
            is IfNode -> emitIf(node)
            is RawTextNode -> when (node.kind) {
                RawKind.Style ->
                    if (isBmlInline(node.attributes)) {
                        // Email renders collect the CSS for style-attribute inlining (works through
                        // components — same context); non-email renders fall back to a normal tag.
                        line("if (!ctx.collectEmailCss(${kstr(node.content)})) w.markup(${kstr("<style>${node.content}</style>")})")
                    } else {
                        line("w.markup(${kstr("<style>${node.content}</style>")})")
                    }
                else -> Unit // server/client scripts + contracts are handled elsewhere
            }
            is ElementNode -> emitElement(node)
        }
    }

    private fun emitFor(node: ForNode) {
        val b = node.binding
        if (b.indexOrKey != null) {
            line("for ((${b.indexOrKey}, ${b.item}) in (${node.iterable}).withIndex()) {")
        } else {
            line("for (${b.item} in (${node.iterable})) {")
        }
        indent++
        emitChildren(node.children)
        indent--
        currentSourceLine = node.span.startLine
        line("}")
    }

    private fun emitIf(node: IfNode) {
        node.branches.forEachIndexed { i, branch ->
            currentSourceLine = branch.span.startLine
            line(if (i == 0) "if (${branch.condition}) {" else "} else if (${branch.condition}) {")
            indent++
            emitChildren(branch.children)
            indent--
        }
        val elseChildren = node.elseChildren
        if (elseChildren != null) {
            currentSourceLine = node.span.startLine
            line("} else {")
            indent++
            emitChildren(elseChildren)
            indent--
        }
        currentSourceLine = node.span.startLine
        line("}")
    }

    /**
     * Finds request-identity feature evaluation in server-evaluated code: `<if flag>` conditions,
     * `<script server>` bodies, interpolations, loop iterables, and bound or interpolated attributes.
     * Kotlin string literals and comments are ignored; string-template expressions are still code.
     * `@event` action expressions are scanned too: their arguments are evaluated during this render
     * into the element's `data-bml-args` marker. With [skipDeferredIslands] a deferred body is
     * skipped while its shell-evaluated inputs are not.
     */
    private fun firstFeatureFlagCondition(nodes: List<Node>, skipDeferredIslands: Boolean = false): Span? {
        fun attributeUse(attributes: List<Attribute>): Span? = attributes.firstOrNull { attribute ->
            when (attribute) {
                is BoundAttribute -> usesFeatureFlags(attribute.expression)
                is SpreadAttribute -> usesFeatureFlags(attribute.expression)
                is StaticAttribute -> attribute.value.orEmpty().any { part ->
                    part is AttrInterpolation && usesFeatureFlags(part.expression)
                }
                is EventAttribute -> usesFeatureFlags(attribute.expression)
            }
        }?.span

        nodes.forEach { node ->
            when (node) {
                is IfNode -> {
                    node.branches.firstOrNull { usesFeatureFlags(it.condition) }?.let { return it.span }
                    node.branches.forEach { branch ->
                        firstFeatureFlagCondition(branch.children, skipDeferredIslands)?.let { return it }
                    }
                    node.elseChildren?.let { children ->
                        firstFeatureFlagCondition(children, skipDeferredIslands)?.let { return it }
                    }
                }
                is ElementNode -> {
                    attributeUse(node.attributes)?.let { return it }
                    if (skipDeferredIslands && isDeferredIsland(node)) {
                        firstFeatureFlagCondition(deferredFallbackChildren(node), skipDeferredIslands)
                            ?.let { return it }
                        return@forEach
                    }
                    firstFeatureFlagCondition(node.children, skipDeferredIslands)?.let { return it }
                }
                is ForNode -> {
                    if (usesFeatureFlags(node.iterable)) return node.span
                    firstFeatureFlagCondition(node.children, skipDeferredIslands)?.let { return it }
                }
                is InterpolationNode -> if (usesFeatureFlags(node.expression)) return node.span
                is RawTextNode -> if (node.kind == RawKind.ServerScript && usesFeatureFlags(node.content)) {
                    return node.span
                }
                else -> Unit
            }
        }
        return null
    }

    private fun usesFeatureFlags(kotlin: String): Boolean =
        FEATURE_FLAG_ACCESS.containsMatchIn(KotlinCodeScanner(kotlin).codeWithoutLiterals())

    private fun emitElement(el: ElementNode) {
        // Localization markup: a stray plural form outside a t element…
        if (el.namespace == "t") {
            diagnostics.add(
                Diagnostic(
                    Severity.Error,
                    "<t:${el.name}> is a plural form — only valid inside an element carrying t=\"key\" and t:count",
                    el.span,
                ),
            )
            return
        }
        // …and the t / t:count / t:<attr> vocabulary itself.
        parseTMarkup(el)?.let { emitLocalizedElement(el, it); return }

        val nativeSvg = el.namespace == "svg" || svgDepth > 0 && el.name in BuiltinTags.svg

        if (el.namespace == null && !nativeSvg) {
            // A declared component instantiation lowers to a render call (takes precedence over HTML).
            if (el.name in knownComponents) { emitComponentCall(el); return }
            when (el.name) {
                "data" -> { emitData(el); return }
                "inject" -> {
                    if (el.span.startOffset !in hoistedInjections) {
                        diagnostics.add(
                            Diagnostic(
                                Severity.Error,
                                "<inject> must be a direct child of a page, route, component, message, or template",
                                el.span,
                            ),
                        )
                    }
                    return
                }
                "island" -> { emitIsland(el); return }
                "fallback" -> {
                    diagnostics.add(Diagnostic(Severity.Error, "<fallback> is valid only as a direct child of a deferred island", el.span))
                    return
                }
                "slot" -> { emitSlot(el); return }
                "component", "prop" -> return // declarations — emitted by generate()/emitComponent()
                "page", "route", "template", "use" -> {
                    diagnostics.add(Diagnostic(Severity.Warning, "<${el.name}> codegen is not yet implemented", el.span))
                    return
                }
                "email", "subject" -> {
                    diagnostics.add(Diagnostic(Severity.Warning, "<${el.name}> is only valid as an email unit's root / subject region; ignoring", el.span))
                    return
                }
            }
        }

        val resolvedNamespace = if (nativeSvg) "svg" else el.namespace
        when (val res = registry.resolve(el.name, resolvedNamespace)) {
            is TagResolution.Error ->
                diagnostics.add(Diagnostic(Severity.Warning, res.message, el.span))
            else -> Unit
        }

        if (el.namespace == null && el.name == "img" && isBmlInline(el.attributes)) {
            emitInlineImg(el)
            return
        }

        val isVoid = (registry.resolve(el.name, resolvedNamespace) as? TagResolution.Html)?.isVoid == true
        emitOpenTag(el)
        if (isVoid) return
        if (el.selfClosing) {
            // A self-closed NON-void element (an SVG `<path/>`, an empty `<div/>`) still needs its
            // close tag: HTML parses a bare `<path ...>` as unclosed and swallows the following
            // siblings as children (which is how a four-path Google mark renders as one path).
            line("w.markup(${kstr("</${el.name}>")})")
            return
        }
        val opensSvg = el.name == "svg" && (el.namespace == null || el.namespace == "svg")
        if (opensSvg) svgDepth++
        emitChildren(el.children)
        if (opensSvg) svgDepth--
        // Hydration-state scripts go at the end of <body> (inside the document), like other SSR frameworks.
        if (el.namespace == null && el.name == "body") {
            pendingLiveScriptsRoute?.let { emitLiveStateScripts(it); pendingLiveScriptsRoute = null }
        }
        currentSourceLine = el.span.startLine
        line("w.markup(${kstr("</${el.name}>")})")
    }

    private fun emitData(el: ElementNode) {
        val provides = staticString(el.attributes, "provides")
        val expr = el.children.filterIsInstance<InterpolationNode>().firstOrNull()?.expression
        if (provides == null || expr == null) {
            diagnostics.add(Diagnostic(Severity.Error, "<data> requires provides=\"…\" and a single { expression } body", el.span))
            return
        }
        line("val $provides = run { $expr }")
    }

    /**
     * `<inject name="service" type="example.Service"/>` contributes a suspend Bosca DI lookup to
     * the enclosing renderer's prologue. Like a server `provides`, the declaration is available to
     * the whole page/component/message body regardless of its source position and emits no markup.
     * A bare `server` marker lets an injected serializable model participate in live server actions.
     */
    private fun emitInject(el: ElementNode) {
        val name = staticString(el.attributes, "name")?.takeIf { it.isNotBlank() }
        val type = staticString(el.attributes, "type")?.takeIf { it.isNotBlank() }
        val provider = staticString(el.attributes, "provider")?.takeIf { it.isNotBlank() }
        val providerAttribute = el.attributes.firstOrNull { it.name == "provider" }
        val init = staticString(el.attributes, "init")?.takeIf { it.isNotBlank() }
        val initAttribute = el.attributes.firstOrNull { it.name == "init" }
        val serverAttribute = el.attributes.firstOrNull { it.name == "server" }
        val server = serverAttribute is StaticAttribute && serverAttribute.value == null
        val scopeAttribute = el.attributes.firstOrNull { it.name == "scope" }
        val scope = staticString(el.attributes, "scope")
        val clearOnSignOutAttribute = el.attributes.firstOrNull { it.name == "clear-on-sign-out" }

        el.attributes.filterNot { attribute ->
            attribute is StaticAttribute && attribute.name in setOf(
                "name", "type", "provider", "init", "server", "scope", "clear-on-sign-out",
            )
        }.forEach { attribute ->
            diagnostics.add(
                Diagnostic(
                    Severity.Error,
                    "<inject> supports only literal name, type, optional provider/init/scope, and bare server/clear-on-sign-out markers",
                    attribute.span,
                ),
            )
        }
        if (serverAttribute is StaticAttribute && serverAttribute.value != null) {
            diagnostics.add(Diagnostic(Severity.Error, "<inject> server is a bare presence marker", serverAttribute.span))
        }
        if (clearOnSignOutAttribute != null && serverAttribute == null) {
            diagnostics.add(
                Diagnostic(
                    Severity.Error,
                    "<inject> clear-on-sign-out requires the bare server marker",
                    clearOnSignOutAttribute.span,
                ),
            )
        }
        if (scopeAttribute is StaticAttribute) {
            if (serverAttribute == null) {
                diagnostics.add(Diagnostic(Severity.Error, "<inject> scope requires the bare server marker", scopeAttribute.span))
            } else if (server && scope !in setOf("page", "client-session", "client-local", "server-session")) {
                diagnostics.add(
                    Diagnostic(
                        Severity.Error,
                        "live state `$name` scope must be `page`, `client-session`, `client-local`, or `server-session`",
                        scopeAttribute.span,
                    ),
                )
            }
        }
        if (name == null || type == null) {
            diagnostics.add(
                Diagnostic(
                    Severity.Error,
                    "<inject> requires non-blank literal name=\"…\" and type=\"…\" attributes",
                    el.span,
                ),
            )
            return
        }
        if (providerAttribute != null && provider == null) {
            diagnostics.add(Diagnostic(Severity.Error, "<inject> provider must be a non-blank literal", providerAttribute.span))
            return
        }
        if (initAttribute != null && init == null) {
            diagnostics.add(Diagnostic(Severity.Error, "<inject> init must be a non-blank literal method name", initAttribute.span))
            return
        }
        if (init != null && (!KOTLIN_IDENTIFIER.matches(init) || init in KOTLIN_KEYWORDS)) {
            diagnostics.add(Diagnostic(Severity.Error, "<inject> init '$init' is not a valid Kotlin method name", initAttribute?.span ?: el.span))
            return
        }
        if (!KOTLIN_IDENTIFIER.matches(name) || name in KOTLIN_KEYWORDS) {
            diagnostics.add(Diagnostic(Severity.Error, "<inject> name '$name' is not a valid Kotlin identifier", el.span))
            return
        }
        val meaningfulChildren = el.children.filterNot { it is CommentNode || it is TextNode && it.value.isBlank() }
        if (meaningfulChildren.isNotEmpty()) {
            diagnostics.add(Diagnostic(Severity.Error, "<inject> must be empty", meaningfulChildren.first().span))
            return
        }

        currentSourceLine = el.span.startLine
        val lookup = if (provider == null) {
            "bosca.di.provide<$type>()"
        } else {
            "bosca.di.provide<$type>(${kstr(provider)})"
        }
        val serverState = liveStates[name]?.takeIf { it.serverScoped }
        if (serverState != null) {
            val keyExpr = sessionStateKeyExprs[name] ?: stateKeyExprs[name] ?: kstr(name)
            val injected = "__bmlInjected_$name"
            line("val $name: $type = ctx.session?.get($keyExpr)?.let { bosca.bml.render.decodeState<$type>(it) } ?: run {")
            indent++
            line("val $injected: $type = $lookup")
            if (init != null) line("$injected.$init()")
            line(injected)
            indent--
            line("}")
        } else {
            line("val $name: $type = $lookup")
            if (init != null) line("$name.$init()")
        }
    }

    /** A valid live injected model uses a bare `server` presence marker. */
    private fun isServerInject(el: ElementNode): Boolean =
        el.attributes.any { it is StaticAttribute && it.name == "server" && it.value == null }

    private fun emitIsland(el: ElementNode) {
        val renderAttribute = el.attributes.firstOrNull { it.name == "render" }
        val render = staticString(el.attributes, "render")
        if (renderAttribute != null && render !in setOf("request", "prerender", "deferred")) {
            diagnostics.add(
                Diagnostic(
                    Severity.Error,
                    "<island> render must be `request`, `prerender`, or `deferred`",
                    renderAttribute.span,
                ),
            )
        }
        deferredByOffset[el.span.startOffset]?.let { deferred ->
            emitDeferredShell(deferred)
            return
        }
        // A deferred island is registered unless its declaration was rejected (no name, or placed inside a
        // <fallback>); that error is already reported, and emitting it as an eager island would only add
        // misleading follow-on errors about its <fallback> and actions.
        if (isDeferredIsland(el)) return
        val name = staticString(el.attributes, "name") ?: "island"
        val clientName = clientIslandName(name, currentIslandOwnerComponentTag)
        // A live view island reflects a state model: its wrapper carries data-bml-state-key (the join to the
        // `@click`/`@submit` element + dispatcher) and its inner content is produced by the shared renderInner().
        val live = liveStates.values.firstOrNull { it.islandName == name && it.islandEl === el }
        val liveKeyExpr = live?.let { stateKeyExprs[it.name] ?: kstr(it.name) }
        // SSR wrapper + props for client mounting. Each `:prop="expr"` (a BoundAttribute,
        // excluding `name` and a live state's per-instance `key`) is evaluated server-side and serialized
        // into data-bml-props, which the `@bosca/bml` runtime parses into ctx.props.
        val props = el.attributes.filterIsInstance<BoundAttribute>().filter { it.name != "name" && it.name != "key" }
        line("w.markup(${kstr("<div data-bml-island=\"$clientName\"")})")
        if (isFallthroughRoot(el)) {
            currentClientComponentTag?.let { line("w.markup(${kstr(" data-bml-component=\"$it\"")})") }
            line("w.markup((props[${kstr(FALLTHROUGH_KEY)}] as? String).orEmpty())")
        }
        if (live != null && liveKeyExpr != null) line("w.attr(\"data-bml-state-key\", $liveKeyExpr)")
        if (props.isNotEmpty()) {
            val entries = props.joinToString(", ") { "${kstr(it.name)} to (${it.expression})" }
            line("w.attr(\"data-bml-props\", bosca.bml.render.propsToJson(mapOf($entries)))")
        }
        line("w.markup(${kstr(">")})")
        if (live != null) {
            line("${live.islandObject}.renderInner(ctx, ${live.name}, $liveKeyExpr)")
        } else {
            emitChildren(el.children.filterNot { it is RawTextNode && it.kind == RawKind.ClientScript })
        }
        currentSourceLine = el.span.startLine
        line("w.markup(${kstr("</div>")})")
        // A component instance's state persists right where it renders (a page state's scripts land
        // once, before </body>): client scope emits the adjacent state script the runtime round-trips;
        // server scope initializes the model in the cookie-identified session under the instance key.
        if (live != null && live.isComponentState && !live.siteScoped && liveKeyExpr != null) {
            if (live.serverScoped) {
                val sessionKey = sessionStateKeyExprs[live.name] ?: liveKeyExpr
                line("ctx.session?.putIfAbsent($sessionKey, bosca.bml.render.encodeState(${live.name}))")
            } else {
                line("w.markup(${kstr("<script type=\"application/json\"")})")
                line("w.attr(\"data-bml-state-key\", $liveKeyExpr)")
                live.clientScope?.let { scope -> line("w.markup(${kstr(" data-bml-scope=\"$scope\"")})") }
                if (live.clearOnSignOut) line("w.markup(${kstr(" data-bml-clear-on-sign-out")})")
                line("w.markup(${kstr(">")})")
                line("w.raw(bosca.bml.render.encodeClientState(${live.name}))")
                line("w.markup(${kstr("</script>")})")
            }
        }
    }

    /** Emits only the cache-safe boundary and fallback; the body belongs to [emitDeferredRenderer]. */
    private fun emitDeferredShell(def: DeferredDef) {
        // Author expressions are wrapped in `kotlin.run<DeclaredType>` so the Kotlin compiler checks them
        // against the prop type; a mismatch fails the build instead of every deferred request.
        val entries = def.props.mapNotNull { prop ->
            val input = def.element.attributes.firstOrNull { it.name == prop.name } ?: return@mapNotNull null
            val value = typedPropInput(input, prop.name, prop.type, strict = true) as? StaticPropValue.Code ?: return@mapNotNull null
            val checked = if (value.needsTypeCheck) typedPropValue(value.expression, prop.type) else value.expression
            "${kstr(prop.name)} to $checked"
        }
        val propsExpression = "bosca.bml.render.deferredPropsToJson(mapOf(${entries.joinToString(", ")}))"
        line("w.markup(${kstr("<div data-bml-island=\"${clientIslandName(def.name, def.ownerComponentTag)}\"")})")
        line("w.attr(\"data-bml-deferred\", ${kstr(def.id)})")
        line("w.attr(\"data-bml-props\", $propsExpression)")
        def.element.attributes.firstOrNull { it.name == "key" }?.let { key ->
            when (key) {
                is BoundAttribute -> line("w.attr(\"data-bml-id\", (${key.expression}))")
                is StaticAttribute -> when (val parts = key.value) {
                    null -> diagnostics.add(
                        Diagnostic(Severity.Error, "A deferred island key needs a value, such as key=\"row-{ id }\"", key.span),
                    )
                    else -> line("w.attr(\"data-bml-id\", ${attrConcatExpr(parts)})")
                }
                else -> Unit
            }
        }
        if (isFallthroughRoot(def.element)) {
            currentClientComponentTag?.let { line("w.markup(${kstr(" data-bml-component=\"$it\"")})") }
            line("w.markup((props[${kstr(FALLTHROUGH_KEY)}] as? String).orEmpty())")
        }
        line("w.markup(${kstr(" data-bml-deferred-state=\"pending\" aria-busy=\"true\">")})")
        if (def.fallbackValid) emitChildren(def.fallback)
        currentSourceLine = def.element.span.startLine
        line("w.markup(${kstr("</div>")})")
    }

    /**
     * Emit the open tag, coalescing the tag name and all static attributes into a
     * single `w.markup("<tag …>")` call and flushing to `w.attr(...)`/`w.spread(...)`
     * only where a dynamic attribute appears.
     */
    private fun emitOpenTag(el: ElementNode) {
        val pending = StringBuilder("<${el.name}")
        // Stamp the enclosing scoped component's marker so its `<style scoped>` selectors match here.
        currentScope?.let { pending.append(" data-bml-c=\"$it\"") }
        if (isFallthroughRoot(el)) {
            currentClientComponentTag?.let { pending.append(" data-bml-component=\"$it\"") }
        }
        fun flush() {
            if (pending.isNotEmpty()) {
                line("w.markup(${kstr(pending.toString())})")
                pending.setLength(0)
            }
        }
        for (attr in el.attributes) {
            when (attr) {
                is StaticAttribute -> {
                    val emittedName = if (attr.name == "ref") "data-bml-ref" else attr.name
                    val value = attr.value
                    when {
                        value == null -> pending.append(" $emittedName")                               // boolean
                        value.all { it is AttrText } ->                                                // static literal
                            pending.append(" $emittedName=\"${value.joinToString("") { (it as AttrText).value }}\"")
                        else -> { flush(); line("w.attr(${kstr(emittedName)}, ${attrConcatExpr(value)})") } // interpolated
                    }
                }
                is BoundAttribute -> {
                    val emittedName = if (attr.name == "ref") "data-bml-ref" else attr.name
                    flush()
                    line("w.attr(${kstr(emittedName)}, (${attr.expression}))")
                }
                is SpreadAttribute -> { flush(); line("w.spread((${attr.expression}))") }
                is EventAttribute -> actionMarkersExpr(attr)?.let { flush(); line("w.markup($it)") }
            }
        }
        // A component's root element re-emits any attributes the instantiation forwarded (fall-through), so a
        // `@click` on the component lands directly on this real element — no wrapper.
        if (isFallthroughRoot(el)) {
            flush()
            line("w.markup((props[${kstr(FALLTHROUGH_KEY)}] as? String).orEmpty())")
        }
        pending.append(">")
        flush()
    }

    /**
     * The Kotlin expression producing an element's live-island markers (` data-bml-state-key="…"
     * data-bml-method="…"` plus event/args — see [bosca.bml.render.actionMarkers]) for a
     * `@click`/`@submit` whose receiver is a live state; the runtime delegates events off these (the
     * `@event` attribute itself is dropped). Returns null (with a diagnostic) for invalid policy/action
     * syntax or a receiver that is not a live state. Client-side handlers are NOT this: they are standard
     * HTML `on<event>` attributes (plain attributes, passed through), whose page-script functions
     * [BmlClientCodeGenerator] publishes to `window`.
     */
    private fun actionMarkersExpr(attr: EventAttribute): String? {
        val policy = parseActionPolicy(attr) ?: return null
        val action = parseAction(policy.event, attr.expression)
        if (action == null) {
            diagnostics.add(Diagnostic(Severity.Error, "@${attr.event}=\"${attr.expression}\" is not a valid model action", attr.span))
            return null
        }
        val live = liveStates[action.receiver]
        if (live == null) {
            diagnostics.add(Diagnostic(Severity.Error, "@${attr.event}=\"${attr.expression}\" does not reference a live state model", attr.span))
            return null
        }
        val keyExpr = stateKeyExprs[live.name] ?: kstr(live.name)
        val eventArg = if (policy.event == "click") "null" else kstr(policy.event)
        val argsArg = if (action.wireArgs.isEmpty()) "null" else action.wireArgs.joinToString(
            prefix = "listOf<Any?>(", postfix = ")", separator = ", ",
        ) { arg ->
            when (arg) {
                is ActionArg.Field -> "mapOf(${kstr("__bmlField")} to ${kstr(arg.name)})"
                is ActionArg.Expr -> "(${arg.code})"
                is ActionArg.Ctx -> error("ctx is not a wire arg")
            }
        }
        val policyArgs = buildList {
            policy.debounceMs?.let { add("debounceMs = ${it}L") }
            policy.throttleMs?.let { add("throttleMs = ${it}L") }
            if (policy.coalesce) add("coalesce = true")
            if (policy.keepalive) add("keepalive = true")
            if (policy.flushOnPageHide) add("flushOnPageHide = true")
        }
        val suffix = if (policyArgs.isEmpty()) "" else ", " + policyArgs.joinToString(", ")
        return "bosca.bml.render.actionMarkers($keyExpr, ${kstr(action.method)}, $eventArg, $argsArg$suffix)"
    }

    /**
     * `@input.debounce.250.coalesce.keepalive.pagehide` → its DOM event and runtime policy.
     * Debounce and throttle are mutually exclusive because combining them has surprising timing.
     */
    private fun parseActionPolicy(attr: EventAttribute): ActionPolicy? {
        val parts = attr.event.split('.').filter { it.isNotBlank() }
        if (parts.isEmpty()) {
            diagnostics.add(Diagnostic(Severity.Error, "an action requires a DOM event name", attr.span))
            return null
        }
        var debounceMs: Long? = null
        var throttleMs: Long? = null
        var coalesce = false
        var keepalive = false
        var flushOnPageHide = false
        var i = 1
        while (i < parts.size) {
            when (val modifier = parts[i]) {
                "debounce", "throttle" -> {
                    val duration = parts.getOrNull(i + 1)?.toLongOrNull()
                    if (duration == null || duration < 0) {
                        diagnostics.add(Diagnostic(Severity.Error, "action modifier '$modifier' requires a non-negative millisecond value", attr.span))
                        return null
                    }
                    if (modifier == "debounce") debounceMs = duration else throttleMs = duration
                    i += 2
                    continue
                }
                "coalesce" -> coalesce = true
                "keepalive" -> keepalive = true
                "pagehide" -> flushOnPageHide = true
                else -> {
                    diagnostics.add(Diagnostic(Severity.Error, "unknown action modifier '$modifier' on @${attr.event}", attr.span))
                    return null
                }
            }
            i++
        }
        if (debounceMs != null && throttleMs != null) {
            diagnostics.add(Diagnostic(Severity.Error, "an action cannot combine debounce and throttle", attr.span))
            return null
        }
        return ActionPolicy(parts.first(), debounceMs, throttleMs, coalesce, keepalive, flushOnPageHide)
    }

    /**
     * The value of an attribute written with `{ … }` interpolations. A single interpolation keeps its
     * expression's type (an HTML attribute renders a Boolean as presence); several parts are always
     * string concatenation, even when the first part is an interpolation (`"{ count } items"`,
     * `"{ a }{ b }"`), so numeric parts are joined rather than added.
     */
    private fun attrConcatExpr(parts: List<AttrPart>): String {
        val joined = parts.joinToString(" + ") { part ->
            when (part) {
                is AttrText -> kstr(part.value)
                is AttrInterpolation -> "(${part.expression})"
            }
        }
        return if (parts.size > 1 && parts.first() !is AttrText) "\"\" + $joined" else joined
    }

    // ── localization markup: t / t:count / t:<attr> ─────

    /** The localization facts one element declares: the pinned key, the count binding, attr pairings. */
    private class TMarkup(
        val key: String?,
        val countExpr: String?,
        val attrPairs: List<Pair<String, String>>, // (paired attribute name, message key)
    )

    /** Null when the element carries no `t`/`t:` markup at all. */
    private fun parseTMarkup(el: ElementNode): TMarkup? {
        var key: String? = null
        var countExpr: String? = null
        val attrPairs = mutableListOf<Pair<String, String>>()
        var any = false
        for (attr in el.attributes) {
            when {
                attr is StaticAttribute && attr.name == "t" -> {
                    any = true
                    val text = staticString(el.attributes, "t")
                    if (text.isNullOrBlank()) {
                        diagnostics.add(Diagnostic(Severity.Error, "t=\"…\" needs a literal, non-empty message key", attr.span))
                    } else {
                        key = text
                    }
                }
                attr is StaticAttribute && attr.name == "t:count" -> {
                    any = true
                    diagnostics.add(Diagnostic(Severity.Error, "t:count needs a count expression: t:count=\"items.size\"", attr.span))
                }
                attr is BoundAttribute && attr.name == "t:count" -> {
                    any = true
                    countExpr = attr.expression.trim().takeIf { it.isNotEmpty() }
                    if (countExpr == null) {
                        diagnostics.add(Diagnostic(Severity.Error, "t:count needs a count expression: t:count=\"items.size\"", attr.span))
                    }
                }
                attr is StaticAttribute && attr.name.startsWith("t:") -> {
                    any = true
                    val paired = attr.name.removePrefix("t:")
                    val pairKey = attr.value
                        ?.takeIf { v -> v.all { it is AttrText } }
                        ?.joinToString("") { (it as AttrText).value }
                    if (pairKey.isNullOrBlank()) {
                        diagnostics.add(Diagnostic(Severity.Error, "t:$paired=\"…\" needs a literal message key", attr.span))
                    } else {
                        attrPairs.add(paired to pairKey)
                    }
                }
            }
        }
        return if (any) TMarkup(key, countExpr, attrPairs) else null
    }

    private fun emitLocalizedElement(el: ElementNode, t: TMarkup) {
        if (el.namespace == null && el.name in knownComponents) {
            diagnostics.add(
                Diagnostic(
                    Severity.Error,
                    "t/t: attributes are reserved on component instantiations — localize inside the component or pass a prop",
                    el.span,
                ),
            )
            return
        }
        // Strip the t vocabulary and swap each paired attribute to a runtime lookup whose
        // fallback is the authored value.
        val pairedByName = t.attrPairs.toMap()
        val localizedPairs = mutableSetOf<String>()
        val transformed = mutableListOf<Attribute>()
        for (attr in el.attributes) {
            when {
                attr is StaticAttribute && (attr.name == "t" || attr.name.startsWith("t:")) -> Unit // compile-time only
                attr is BoundAttribute && attr.name.startsWith("t:") -> Unit                        // t:count
                attr is StaticAttribute && attr.name in pairedByName -> {
                    val text = attr.value?.takeIf { v -> v.all { it is AttrText } }
                        ?.joinToString("") { (it as AttrText).value }
                    if (text == null) {
                        diagnostics.add(
                            Diagnostic(
                                Severity.Error,
                                "t:${attr.name} pairs with a plain-text ${attr.name} attribute — compute dynamic values with t() instead",
                                attr.span,
                            ),
                        )
                        transformed.add(attr)
                    } else {
                        val template = decodeEntities(text).replace(WHITESPACE_RUN, " ").trim()
                        val pairKey = pairedByName.getValue(attr.name)
                        recordI18n(
                            BmlI18nEntry(
                                key = pairKey, message = template,
                                origin = BmlI18nOrigin.ATTRIBUTE, file = sourcePath, line = attr.span.startLine,
                            ),
                            attr.span,
                        )
                        transformed.add(BoundAttribute(attr.name, resolveOrCall(pairKey, template, "emptyMap()"), attr.span))
                        localizedPairs.add(attr.name)
                    }
                }
                else -> transformed.add(attr)
            }
        }
        for ((name, _) in t.attrPairs) {
            if (name !in localizedPairs && el.attributes.none { it is StaticAttribute && it.name == name }) {
                diagnostics.add(
                    Diagnostic(
                        Severity.Error,
                        "t:$name has no matching $name attribute — the authored value is the source text and fallback",
                        el.span,
                    ),
                )
            }
        }
        val stripped = el.copy(attributes = transformed)
        // Keep fall-through identity: if this element was a conditional component root, the copy is now it.
        fallthroughRoots = fallthroughRoots.map { if (it === el) stripped else it }

        if (t.key == null) {
            if (t.countExpr != null) {
                diagnostics.add(Diagnostic(Severity.Error, "t:count requires t=\"key\" on the same element", el.span))
            }
            emitElement(stripped) // attribute-only localization: the body renders normally
            return
        }

        val isVoid = (registry.resolve(el.name, el.namespace) as? TagResolution.Html)?.isVoid == true
        if (isVoid || el.selfClosing) {
            diagnostics.add(
                Diagnostic(Severity.Error, "t=\"${t.key}\" needs authored content — the source text and runtime fallback", el.span),
            )
            emitElement(stripped)
            return
        }

        // Fold the authored content into message templates.
        val args = LinkedHashMap<String, String>() // placeholder name -> expression
        val forms = linkedMapOf<String, String>()  // CLDR category (UPPERCASE) -> template
        var message: String? = null
        val categoryEls = el.children.filterIsInstance<ElementNode>().filter { it.namespace == "t" }
        if (categoryEls.isNotEmpty()) {
            if (t.countExpr == null) {
                diagnostics.add(Diagnostic(Severity.Error, "plural forms need a count binding — add t:count=\"…\"", el.span))
            }
            val stray = el.children.filter { node ->
                node !in categoryEls && node !is CommentNode && !(node is TextNode && node.value.isBlank())
            }
            if (stray.isNotEmpty()) {
                diagnostics.add(
                    Diagnostic(Severity.Error, "a plural t element may contain only <t:zero|one|two|few|many|other> forms", el.span),
                )
            }
            for (cat in categoryEls) {
                if (cat.name !in CLDR_CATEGORIES) {
                    diagnostics.add(
                        Diagnostic(Severity.Error, "<t:${cat.name}> is not a CLDR plural category (zero|one|two|few|many|other)", cat.span),
                    )
                    continue
                }
                if (forms.containsKey(cat.name.uppercase())) {
                    diagnostics.add(Diagnostic(Severity.Error, "duplicate <t:${cat.name}> form", cat.span))
                    continue
                }
                foldMessage(cat.children, t.countExpr, args, cat.span)?.let { forms[cat.name.uppercase()] = it }
            }
            if ("OTHER" !in forms) {
                diagnostics.add(
                    Diagnostic(Severity.Error, "<t:other> is required — the general form every language falls back to", el.span),
                )
            }
        } else {
            val folded = foldMessage(el.children, t.countExpr, args, el.span)
            when {
                folded == null -> Unit // fold errors already reported
                t.countExpr != null -> forms["OTHER"] = folded // shorthand: authored text is the OTHER form
                else -> message = folded
            }
        }

        recordI18n(
            BmlI18nEntry(
                key = t.key,
                message = message,
                pluralForms = forms.toMap(),
                placeholders = args.map { BmlI18nPlaceholder(it.key, it.value) },
                origin = BmlI18nOrigin.ELEMENT,
                file = sourcePath,
                line = el.span.startLine,
            ),
            el.span,
        )

        emitOpenTag(stripped)
        val argsExpr = argsMapExpr(args)
        when {
            message != null ->
                line("w.text(bosca.bml.i18n.Messages.resolveOr(ctx.messages, ctx.locale, ${kstr(t.key)}, ${kstr(message)}, $argsExpr))")
            forms.isNotEmpty() && t.countExpr != null -> {
                val formsExpr = forms.entries.joinToString(", ") { "bosca.bml.i18n.PluralCategory.${it.key} to ${kstr(it.value)}" }
                line(
                    "w.text(bosca.bml.i18n.Messages.resolvePluralOr(ctx.messages, ctx.locale, ${kstr(t.key)}, " +
                        "(${t.countExpr}).toLong(), mapOf($formsExpr), $argsExpr))",
                )
            }
        }
        currentSourceLine = el.span.startLine
        line("w.markup(${kstr("</${el.name}>")})")
    }

    /**
     * Folds authored content (text + `{ }` interpolations only) into one message template —
     * entities decoded (templates render escaped through `w.text`), whitespace runs collapsed
     * (the catalog string translators see should not carry the markup's indentation), and each
     * interpolation lowered to a named `{placeholder}`. Null (with diagnostics) when the content
     * is not foldable.
     */
    private fun foldMessage(
        children: List<Node>,
        countExpr: String?,
        args: LinkedHashMap<String, String>,
        span: Span,
    ): String? {
        val sb = StringBuilder()
        var ok = true
        for (node in children) {
            when (node) {
                is TextNode -> sb.append(decodeEntities(node.value))
                is CommentNode -> Unit
                is InterpolationNode -> {
                    if (node.raw) {
                        diagnostics.add(
                            Diagnostic(Severity.Error, "{@ raw } interpolation is not allowed inside a t element — translations render escaped", node.span),
                        )
                        ok = false
                    } else {
                        sb.append('{').append(placeholderFor(node.expression.trim(), countExpr, args)).append('}')
                    }
                }
                else -> {
                    diagnostics.add(
                        Diagnostic(
                            Severity.Error,
                            "a t element may contain only text and { } interpolations — move nested markup out or use t()",
                            node.span,
                        ),
                    )
                    ok = false
                }
            }
        }
        if (!ok) return null
        val template = sb.toString().replace(WHITESPACE_RUN, " ").trim()
        if (template.isEmpty()) {
            diagnostics.add(Diagnostic(Severity.Error, "t element has no authored text — the source text and runtime fallback", span))
            return null
        }
        return template
    }

    /**
     * Names one interpolation's placeholder: the count expression is always `{count}`; a simple
     * dotted path takes its trailing identifier (`user.firstName` -> `firstName`); anything else
     * is positional. Identical expressions share a name; colliding names get numeric suffixes.
     */
    private fun placeholderFor(expr: String, countExpr: String?, args: LinkedHashMap<String, String>): String {
        if (countExpr != null && expr == countExpr.trim()) {
            args["count"] = expr
            return "count"
        }
        args.entries.firstOrNull { it.value == expr }?.let { return it.key }
        val base = if (SIMPLE_PATH.matches(expr)) expr.substringAfterLast('.') else args.size.toString()
        var name = base
        var suffix = 2
        while (args.containsKey(name)) {
            name = "$base${suffix++}"
        }
        args[name] = expr
        return name
    }

    private fun argsMapExpr(args: Map<String, String>): String =
        if (args.isEmpty()) "emptyMap()"
        else args.entries.joinToString(", ", "mapOf<String, Any?>(", ")") { "${kstr(it.key)} to (${it.value})" }

    private fun resolveOrCall(key: String, template: String, argsExpr: String): String =
        "bosca.bml.i18n.Messages.resolveOr(ctx.messages, ctx.locale, ${kstr(key)}, ${kstr(template)}, $argsExpr)"

    /** One key, one message: an in-file redeclaration with different source text is an error. */
    private fun recordI18n(entry: BmlI18nEntry, span: Span) {
        val existing = i18nByKey[entry.key]
        if (existing != null) {
            if (existing.message != entry.message || existing.pluralForms != entry.pluralForms) {
                diagnostics.add(
                    Diagnostic(Severity.Error, "key '${entry.key}' is declared elsewhere with different source text — one key, one message", span),
                )
            }
            return
        }
        i18nByKey[entry.key] = entry
        i18nEntries.add(entry)
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun staticString(attrs: List<Attribute>, name: String): String? {
        val attr = attrs.filterIsInstance<StaticAttribute>().firstOrNull { it.name == name } ?: return null
        val value = attr.value ?: return null
        if (!value.all { it is AttrText }) return null
        return value.joinToString("") { (it as AttrText).value }
    }

    private fun isHtmlContentType(contentType: String): Boolean =
        contentType.substringBefore(';').trim().equals("text/html", ignoreCase = true)

    /**
     * Reconstruct an attribute's literal text, rendering `{ expr }` parts back as
     * `{expr}` — used for route patterns whose `{name}` placeholders the attribute
     * lexer reads as interpolations.
     */
    private fun reconstructLiteral(attrs: List<Attribute>, name: String): String? {
        val attr = attrs.filterIsInstance<StaticAttribute>().firstOrNull { it.name == name } ?: return null
        val value = attr.value ?: return null
        return value.joinToString("") {
            when (it) {
                is AttrText -> it.value
                is AttrInterpolation -> "{${it.expression}}"
            }
        }
    }

    private val routeParamPattern = Regex("""\{([A-Za-z_][A-Za-z0-9_]*)}""")

    /** Route `{name}` placeholders, in order, deduped — e.g. `/lists/{id}/c/{cid}` -> [id, cid]. */
    private fun routeParams(route: String): List<String> =
        routeParamPattern.findAll(route).map { it.groupValues[1] }.distinct().toList()

    /**
     * The known-component tags referenced (recursively) in [nodes], deduped, in first-seen order.
     * Literal `renderFragment("tag")` targets in inline client scripts count as references: the
     * page must link their assets, and bml-server authorizes their actions against this closure.
     */
    private fun collectComponentRefs(nodes: List<Node>): List<String> {
        val out = linkedSetOf<String>()
        fun walk(ns: List<Node>) {
            for (n in ns) when (n) {
                is ElementNode -> {
                    if (n.namespace == null && n.name in knownComponents) out += n.name
                    walk(n.children)
                }
                is RawTextNode -> if (n.kind == RawKind.ClientScript) {
                    ClientScriptScanner(n.content).renderFragmentTargets()
                        .filter { it in knownComponents }
                        .forEach { out += it }
                }
                is ForNode -> walk(n.children)
                is IfNode -> { n.branches.forEach { walk(it.children) }; n.elseChildren?.let { walk(it) } }
                else -> Unit
            }
        }
        walk(nodes)
        return out.toList()
    }

    /** Component references evaluated during the shell render; deferred subtrees are separate requests. */
    private fun collectEagerComponentRefs(nodes: List<Node>): List<String> {
        val out = linkedSetOf<String>()
        fun walk(ns: List<Node>) {
            for (n in ns) when (n) {
                is ElementNode -> {
                    if (isDeferredIsland(n)) {
                        walk(deferredFallbackChildren(n))
                        continue
                    }
                    if (n.namespace == null && n.name in knownComponents) out += n.name
                    walk(n.children)
                }
                is ForNode -> walk(n.children)
                is IfNode -> {
                    n.branches.forEach { walk(it.children) }
                    n.elseChildren?.let(::walk)
                }
                else -> Unit
            }
        }
        walk(nodes)
        return out.toList()
    }

    private fun isDeferredIsland(element: ElementNode): Boolean =
        element.namespace == null && element.name == "island" && staticString(element.attributes, "render") == "deferred"

    private fun clientIslandName(name: String, componentTag: String?): String =
        componentTag?.let { "component:$it:$name" } ?: name

    private fun firstSlot(nodes: List<Node>): ElementNode? {
        for (node in nodes) when (node) {
            is ElementNode -> {
                if (node.namespace == null && node.name == "slot") return node
                firstSlot(node.children)?.let { return it }
            }
            is ForNode -> firstSlot(node.children)?.let { return it }
            is IfNode -> {
                node.branches.forEach { branch -> firstSlot(branch.children)?.let { return it } }
                node.elseChildren?.let { children -> firstSlot(children)?.let { return it } }
            }
            else -> Unit
        }
        return null
    }

    /** Only the authored fallback is evaluated while rendering a deferred island's eager shell. */
    private fun deferredFallbackChildren(element: ElementNode): List<Node> =
        element.children.filterIsInstance<ElementNode>()
            .firstOrNull { it.namespace == null && it.name == "fallback" }
            ?.children
            .orEmpty()

    /** Collect `import …` lines from every `<script server>` (recursively) — hoisted to the file top. */
    private fun collectServerImports(nodes: List<Node>, out: MutableSet<String>) {
        for (n in nodes) when (n) {
            is RawTextNode -> if (n.kind == RawKind.ServerScript) {
                n.content.lineSequence().map { it.trim() }.filter { it.startsWith("import ") }.forEach { out += it }
            }
            is ElementNode -> collectServerImports(n.children, out)
            is ForNode -> collectServerImports(n.children, out)
            is IfNode -> {
                n.branches.forEach { collectServerImports(it.children, out) }
                n.elseChildren?.let { collectServerImports(it, out) }
            }
            else -> Unit
        }
    }

    /** Whether the page (recursively) contains any `<script client>` — drives `clientModule`. */
    private fun hasClientScript(node: Node): Boolean = when (node) {
        is RawTextNode -> node.kind == RawKind.ClientScript
        is ElementNode -> node.children.any(::hasClientScript)
        is ForNode -> node.children.any(::hasClientScript)
        is IfNode ->
            node.branches.any { b -> b.children.any(::hasClientScript) } ||
                (node.elseChildren?.any(::hasClientScript) ?: false)
        else -> false
    }

    /**
     * Existing component DOM is the client mount point. A component may choose its one root through
     * `<if>/<else-if>/<else>`; every possible branch root must therefore receive fall-through and
     * `data-bml-component`, while still emitting no wrapper.
     */
    private fun componentRootElements(nodes: List<Node>): List<ElementNode> {
        for (node in nodes) when (node) {
            is ElementNode -> if (node.namespace != null || node.name != "inject") return listOf(node)
            is IfNode -> {
                // Only an exhaustive conditional guarantees one branch root on every render.
                if (node.elseChildren != null) {
                    val roots = node.branches.map { componentRootElements(it.children) } +
                        listOf(componentRootElements(node.elseChildren))
                    if (roots.all { it.isNotEmpty() }) return roots.flatten()
                }
            }
            else -> Unit
        }
        return emptyList()
    }

    private fun isFallthroughRoot(element: ElementNode): Boolean =
        fallthroughRoots.any { it === element }

    /** Client scripts owned by the component itself, excluding independently mounted nested islands. */
    private fun hasScopedClientScriptOutsideIsland(nodes: List<Node>, insideIsland: Boolean = false): Boolean =
        nodes.any { node ->
            when (node) {
                is RawTextNode -> node.kind == RawKind.ClientScript && !insideIsland &&
                    node.attributes.any { it is StaticAttribute && it.name == "scoped" }
                is ElementNode -> hasScopedClientScriptOutsideIsland(
                    node.children,
                    insideIsland || node.namespace == null && node.name == "island",
                )
                is ForNode -> hasScopedClientScriptOutsideIsland(node.children, insideIsland)
                is IfNode -> node.branches.any { hasScopedClientScriptOutsideIsland(it.children, insideIsland) } ||
                    (node.elseChildren?.let { hasScopedClientScriptOutsideIsland(it, insideIsland) } ?: false)
                else -> false
            }
        }

    private fun line(s: String) {
        currentSourceLine?.let { mappings.add(BmlLineMapping(outputLine, it)) }
        repeat(indent) { sb.append("    ") }
        sb.append(s).append('\n')
        outputLine++
    }

    private fun blank() {
        sb.append('\n')
        outputLine++
    }

    private fun kstr(s: String): String = kotlinStringLiteral(s)
}
