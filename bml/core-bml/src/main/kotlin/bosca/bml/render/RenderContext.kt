package bosca.bml.render

import bosca.bml.features.FeatureFlags
import bosca.bml.graphql.GraphQLClient

/**
 * First-party cookie shared by BML and browser analytics. It contains only an opaque installation
 * id used for stable anonymous attribution; it is not an authentication token.
 */
public const val BML_INSTALLATION_COOKIE: String = "bml_iid"

/** Server-issued analytics session shared with browser analytics; distinct from the island session. */
public const val BML_ANALYTICS_SESSION_COOKIE: String = "bml_asid"

/** Analytics inactivity deadline in epoch milliseconds; itself a browser session cookie. */
public const val BML_ANALYTICS_SESSION_EXPIRY_COOKIE: String = "bml_asid_exp"

/**
 * Cookie the Bosca auth SDK (`@bosca/auth-client-browser`) stores the caller's Bosca JWT in — the
 * platform's ONLY security token. Page navigations carry no `Authorization` header, so the server
 * falls back to this cookie when resolving the passthrough token for SSR. The server never mints,
 * refreshes, or inspects it. BML's own cookies (`bml_session`) are session management, not auth.
 */
public const val BOSCA_TOKEN_COOKIE: String = "_bat"

/**
 * Per-render context passed to compiler-generated BML render functions. Carries
 * the output [writer], the [gql] data-plane client generated server
 * scripts use, the caller's [token] (passthrough), and the matched
 * route's [params] (e.g. `{id}` from `/lists/{id}`), which the server fills from
 * Bosca's router and the generated render binds into scope.
 */
class RenderContext(
    val writer: HtmlWriter = HtmlWriter(),
    gql: GraphQLClient? = null,
    val token: String? = null,
    val params: Map<String, String> = emptyMap(),
    /**
     * The request's cookies (name -> value). Sites read their own client-managed state from here
     * (preferences, collections, theme) during SSR; the server itself only interprets
     * [BOSCA_TOKEN_COOKIE], [BML_INSTALLATION_COOKIE], and its own session cookie.
     */
    val cookies: Map<String, String> = emptyMap(),
    /** The request's query-string parameters (first value per name), e.g. `?q=grid` -> `{"q": "grid"}`. */
    val query: Map<String, String> = emptyMap(),
    /**
     * The render's locale, resolved per request by the server —
     * `?lang` -> [BML_LOCALE_COOKIE] -> `Accept-Language` (full RFC 4647 negotiation, see
     * [BmlLocales]) -> site default — and always one of the site's configured locales. Email
     * renders carry the recipient's locale instead. Formatting and plural rules consume it
     * directly; markup wants the BCP-47 tag string — use [lang] (`Locale.toString()` prints
     * `es_419`-style underscores, which is not a valid HTML `lang` value).
     */
    val locale: java.util.Locale = BmlLocales.DEFAULT,
    /**
     * The localized-string source for this render — the site's bound localization
     * project, cached. The ambient `t()` helpers resolve against it under [locale]. Defaults to
     * [bosca.bml.i18n.MessageSource.Empty]: every lookup falls through, so `t()` renders keys
     * and `t`-attributed markup renders its authored text — unlocalized sites are unaffected.
     */
    val messages: bosca.bml.i18n.MessageSource = bosca.bml.i18n.MessageSource.Empty,
    /**
     * When false, scoped components do NOT inline their `<style>`; the server instead links served
     * per-component stylesheets ("linked" asset mode). Defaults true for zero-config SSR / email /
     * plain-text, where there is no asset server to link to.
     */
    val inlineStyles: Boolean = true,
    /**
     * The server-side session for `scope="server-session"` live-island state, when this render involves any.
     * Generated page code stores server-scoped `provides` here at first paint and the action dispatcher
     * loads/persists them on `@click`. Null for renders with no server state.
     */
    val session: BmlSession? = null,
    /** Whether a live-state action currently has a mounted view that needs re-rendering. */
    val renderLiveStateView: Boolean = true,
) {
    /**
     * The caller-token-bound GraphQL data-plane client — NEVER null. With no endpoint configured
     * (`BmlServer(graphqlEndpoint = null)`, bare `RenderContext()` in tests) this is a fail-fast
     * stub whose `execute` names the fix, so loaders guard on real conditions (sign-in,
     * visibility) instead of null-dancing. Also reachable ambiently as
     * [client]`()` (`currentRenderContext().gql`).
     */
    val gql: GraphQLClient = gql ?: MissingGraphQLClient

    /**
     * Feature flags evaluated through the same caller-token-bound GraphQL data plane as page data.
     * Results are cached only for this render context. BML Server obtains an installation id from
     * Bosca and adds it to [cookies] before constructing page contexts. Message and direct render
     * contexts have no anonymous browser identity, so feature evaluation is unavailable there.
     */
    private val featureFlagFacade: FeatureFlags = FeatureFlags(
        gql = this.gql,
        installationId = cookies[BML_INSTALLATION_COOKIE],
        token = token,
    )
    private var allowFeatureFlags: Boolean = true

    val featureFlags: FeatureFlags
        get() {
            check(allowFeatureFlags) {
                "A shared-cache shell cannot evaluate request-identity feature flags; move the evaluation into a deferred island"
            }
            return featureFlagFacade
        }

    /** Prevents a public shared-cache render from evaluating request-identity feature flags. */
    fun disallowFeatureFlags() {
        allowFeatureFlags = false
    }

    /**
     * The render's [locale] as its BCP-47 tag — the form markup wants:
     * `<html lang="{ ctx.lang }">`. Named after the HTML attribute (and the `?lang` override).
     */
    val lang: String get() = locale.toLanguageTag()

    /**
     * The path of the HTTP request being rendered, without its query string. [BmlServer] fills
     * this for page renders so layouts can emit request-specific metadata such as canonical URLs.
     * Non-HTTP renderers retain the root-path default.
     */
    var requestPath: String = "/"

    /**
     * When a page's server script sets this — e.g. an auth guard sending a signed-out caller to
     * the login page — the HTTP server discards the rendered body and redirects to this location
     * instead (302 by default, or 301 when [redirectPermanent] is true). Null renders normally.
     * Renderers outside an HTTP server (email, plain text) ignore it.
     */
    var redirectTo: String? = null

    /**
     * Whether [redirectTo] is a permanent redirect. This is deliberately separate from the
     * destination so ordinary auth and workflow redirects remain temporary by default.
     */
    var redirectPermanent: Boolean = false

    /**
     * When a page's server script sets this — the requested entity does not exist (a stale link,
     * a mistyped id) — the HTTP server discards the rendered body and responds with a real 404,
     * rendering the site's configured not-found page instead. False (the default) renders
     * normally. Renderers outside an HTTP server (email, plain text) ignore it.
     */
    var notFound: Boolean = false

    // Tracks which scoped components have already inlined their <style> this render, so a component
    // used N times on a page emits its CSS exactly once.
    private val emittedStyles = mutableSetOf<String>()

    // A site component can still be reached more than once through a render closure, so emit one
    // authoritative client-state or server-state marker per model in each render context.
    private val emittedStateMarkers = mutableSetOf<String>()

    // ── email-target collection (`bml-inline`) ────────────────────────
    // Non-null only for email renders (the generated email object calls [beginEmailCollection]).
    // `<style bml-inline>` blocks collect here instead of writing a <style> tag, and are inlined
    // into element style attributes by EmailRenderer AFTER the body renders — which is why this
    // works through components: they render into the same context. On non-email renders the
    // collectors are inert and the generated code falls back to a normal <style> tag.
    private var emailCss: StringBuilder? = null
    private var emailImages: LinkedHashMap<String, String>? = null

    /** Arm the email collectors; called once by a generated email template before its body renders. */
    fun beginEmailCollection() {
        emailCss = StringBuilder()
        emailImages = LinkedHashMap()
    }

    /** Collect a `<style bml-inline>` block. False = not an email render (emit the tag instead). */
    fun collectEmailCss(css: String): Boolean {
        val collector = emailCss ?: return false
        collector.appendLine(css)
        return true
    }

    /** Record that an `<img bml-inline>` actually rendered, keyed by its generated Content-ID. */
    fun collectEmailImage(cid: String, source: String) {
        emailImages?.put(cid, source)
    }

    /** Everything `<style bml-inline>` collected this render, for EmailRenderer inlining. */
    val collectedEmailCss: String get() = emailCss?.toString().orEmpty()

    /** The `bml-inline` images this render used, in first-render order. */
    val collectedEmailImages: List<bosca.bml.email.EmailImage>
        get() = emailImages?.map { bosca.bml.email.EmailImage(it.key, it.value) } ?: emptyList()

    /**
     * Returns true the first time [key] is seen this render (and records it), false afterwards — and
     * always false in linked mode ([inlineStyles] == false), where the server links the CSS instead.
     */
    fun useStyleOnce(key: String): Boolean = inlineStyles && emittedStyles.add(key)

    /** True the first time a site-state marker for [key] is emitted during this render. */
    fun useStateMarkerOnce(key: String): Boolean = emittedStateMarkers.add(key)

    override fun toString(): String = writer.toString()
}

/**
 * The `gql` of an endpoint-less render: any query explains the misconfiguration instead of
 * NPE-ing or forcing null checks on every caller.
 */
private object MissingGraphQLClient : GraphQLClient {
    override suspend fun execute(
        query: String,
        variables: kotlinx.serialization.json.JsonObject?,
        operationName: String?,
        token: String?,
    ): kotlinx.serialization.json.JsonElement = throw IllegalStateException(
        "No GraphQL endpoint is configured — construct BmlServer(graphqlEndpoint = …) " +
            "(or pass RenderContext(gql = …) when rendering directly).",
    )
}
