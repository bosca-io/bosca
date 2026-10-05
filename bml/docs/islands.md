# Islands

An **island** is a region that renders complete HTML on the server (SEO-safe) and
then mounts a small TypeScript component onto that existing DOM — progressive
enhancement, never client-only content.

```bml
<island name="sortable" client="ts" :id="id">
  <ul ref="items">
    <for item in listView.items>
      <li :data-id="item.id">{ item.label }</li>
    </for>
  </ul>

  <script client>
    import Sortable from "sortablejs"
    import { reorderList } from "./graphql/ReorderList"

    const id = ctx.props.id as string
    const list = ctx.refs.items
    const sortable = Sortable.create(list, {
      group: ctx.scoped("items"),         // unique per instance — no cross-list dragging
      onEnd: async () => {
        const ids = [...list.children].map((li) => (li as HTMLElement).dataset.id!)
        const { list: view } = await reorderList({ id, ids })
        ctx.replace(list, view)           // patch THIS instance's region
      },
    })
    ctx.onUpdate(() => sortable.option("disabled", false))  // re-arm after a swap
    ctx.onUnmount(() => sortable.destroy())
  </script>
</island>
```

## Attributes

| Attribute | Meaning |
| --- | --- |
| `name` | the island's name within its page/component |
| `client="ts"` | mount the inner `<script client>` module on the web target |
| `hydrate="load\|idle\|visible"` | reserved mount policy; the current runtime mounts on load |
| `render="request\|prerender\|deferred"` | eager SSR (`request`; `prerender` is currently equivalent) or a private render after the shell loads |
| `:prop="expr"` / `prop="literal"` | props serialized to the client (`ctx.props.<name>`) |

## Deferred server rendering

Use `render="deferred"` when an island's server-rendered HTML must be kept out of a cacheable page
response. The initial response contains the island boundary and its `<fallback>` only. Once the
browser runtime starts, it posts the boundary's explicit props and current page context to BML Server.
BML Server resolves the caller's current token, installation, analytics session, route parameters,
query, and locale, then renders the island body. The fragment response always uses
`Cache-Control: private, no-store`.

```bml
<page route="/account/{accountId}" cache="shared" maxAge="30" staleWhileRevalidate="300">
  <html>
    <head><title>Account</title></head>
    <body>
      <h1>Account</h1>

      <island name="account-summary" render="deferred" :id="accountId">
        <prop name="id" type="String" required/>

        <fallback>
          <section aria-live="polite">
            <p>Loading account…</p>
            <button type="button" data-bml-deferred-retry>Retry</button>
          </section>
        </fallback>

        <script server provides="account">loadAccount(id)</script>
        <section>
          <h2>{ account.displayName }</h2>
          <p>{ account.summary }</p>
        </section>
      </island>
    </body>
  </html>
</page>
```

Each deferred input needs a direct `<prop>` declaration with an explicit `type` and a matching island
attribute. Props are
the only values copied from the shell render into the later request; enclosing page/component local
variables do not cross that boundary. Route params are bound again from the posted pathname, and
request data remains available through `RenderContext`. The compiler converts a static input to the
prop's declared type: `count="3"` on an `Int` prop is the number `3`, `open="false"` on a `Boolean` is
`false`, and a bare attribute is `true`. Text that cannot be read as that type (`count="three"`), text
mixed with an interpolation on a non-string prop, and static text for a collection prop are compile
errors; bind those with `:name="…"`. Every input, bound or static, is also type-checked against its
`<prop>` type when the generated page compiles. Deferred props must be JSON-shaped values:
strings, booleans, finite numbers, null, lists, primitive arrays, and string-keyed maps. BML's wire
format preserves Kotlin numeric, character, list, map, and primitive-array types for the deferred
renderer, including inside lists and maps. Client scripts receive Kotlin `Long` values as JavaScript
`bigint` values so values outside JavaScript's safe-integer range remain exact. Maps keep their
iteration order through the deferred request; client scripts receive them as plain objects, whose
integer-like keys (`"2"`, `"10"`) follow JavaScript's own ordering. Declare those wire
types directly: type aliases and custom application types must be expanded to their supported
JSON-shaped type. On a shared page these props are part of the public cached shell, so they must not
contain private data.

The fallback stays in place if the request fails. It is static server-rendered markup; the compiler
rejects client scripts and declarative `@event` actions there, including behavior introduced through
components. Client refs belong in the deferred
body, which mounts after replacement. The boundary receives
`data-bml-deferred-state="error"` and `data-bml-deferred-error`; an element marked
`data-bml-deferred-retry` retries only its nearest boundary. `refreshDeferred(root?)` requests a fresh
render for the current identity. After a completed sign-in or sign-out, call
`clearBmlClientState()` so BML replaces the old identity's server session and reloads the page to
recompute shell markup, deferred props, and fallback content. Identity cleanup and
back/forward cache restoration clear
inserted private DOM before reloading it. Sibling deferred boundaries load concurrently. When deferred
content uses `server-session` state, the page response establishes that session before the browser starts
the deferred requests; those requests never create or replace it, and a successful stateful render renews
the same cookie. Such a page is private and cannot use shared shell caching. If a deferred request finds
no session, the runtime reloads once to obtain one; a second `410 Gone` for the same URL in that tab means
the browser is not keeping the session cookie, so the boundary enters its error state instead of
reloading again. A successful private render re-arms the reload for a later expiry. Sibling boundaries
that miss the session together share that one reload. If a `beforeunload` prompt cancels it, those
boundaries stay loading until the page is reloaded. Eager islands and actions
outside deferred boundaries mount while the private requests are still in flight. Nested deferred
boundaries are discovered after their parent fragment arrives.

Server actions work inside deferred content. Declare the live model inside the deferred scope so its
initialization, state marker, dispatcher, and optional `server-session` state are all created by the
private request:

```bml
<island name="private-cart" render="deferred">
  <script server provides="cart" scope="server-session">my.site.CartModel()</script>
  <island name="cart-view">
    <p>{ cart.itemCount } items</p>
    <button @click="cart.clear">Clear</button>
  </island>
</island>
```

Shared page caching is an independent opt-in. For example,
`cache="shared" maxAge="30" staleWhileRevalidate="300"` renders the successful HTML shell in
public/anonymous context and sends:

```http
Cache-Control: public, max-age=0, must-revalidate
Cloudflare-CDN-Cache-Control: public, max-age=30, stale-while-revalidate=300
ETag: "<strong validator>"
```

`staleWhileRevalidate` defaults to `maxAge`. Once the fresh interval expires, Cloudflare may serve
the cached shell immediately while it conditionally revalidates in the background. `s-maxage` is not
sent because it implies proxy revalidation and prevents this stale response. The shell cannot
use `requireAuth`, eager `server-session` state, or identity-based feature flags. The compiler rejects
`ctx.featureFlags` or `currentRenderContext().featureFlags` anywhere the shell evaluates it:
`<if>` conditions, `<script server>` bodies, interpolations, loop iterables, attribute expressions,
`@event` action arguments (evaluated into the action marker during the render), and deferred-island
inputs, including through components. Kotlin strings and comments are ignored, and deferred bodies are
allowed because they render on a later private request. It receives no caller
token, cookies, installation ID, or analytics session, and emits no per-visitor cookies. Query values
remain part of the URL cache key, and the URL alone selects the shell, so no edge `Vary` support is
needed. The shell's locale comes from `?lang`, or the site default; the visitor's locale cookie and
`Accept-Language` are not consulted. On a site with more than one locale, a request without `?lang`
would need `Accept-Language` negotiation, so that request renders privately (`private, no-store`, with
the visitor's identity) instead of producing a shared shell. Link localized pages with `?lang` (for
example in `hreflang` alternates) to share them at the edge. Development responses and every error,
redirect, or not-found outcome use `no-store` instead. Ordinary HTML pages, rendered with the visitor's
identity, send `Cache-Control: private, no-cache`, so an edge rule that caches HTML never stores them;
non-HTML pages such as feeds and sitemaps keep their own caching.

By default BML hashes the completed public shell for its strong `ETag`. Applications that keep
Bosca-backed public data in memory should pass a `BmlSharedCacheRevisionProvider` to `BmlServer`.
For a page backed by one metadata or collection result, return its Bosca `etag` and `modified`
instant. For a list, `BmlSharedCacheRevision.fromNewestModified` selects the newest `modified`
instant and combines the `etag` values of every item tied at that instant; every visible item must
supply both values or the helper falls back to a completed-body validator. That list rule is valid
when every content or membership change advances a visible item; otherwise use the containing
collection's `etag` and `modified` values. BML combines
both validator values with the route variant and a deterministic deployment revision covering the
build ID (the build time, from the Gradle plugin), project version, generated page and component
source, asset map, and bundled CSS/JS bytes. Replicas
with the same deployment therefore produce the same shell `ETag`. For a site without a localization
source, BML can answer a matching `If-None-Match` with 304 before rendering or GraphQL work. Localized
sites render before validation so the validator covers the exact catalog snapshot used by the shell.
BML verifies a pre-render data revision again after rendering and hashes the completed body if the
cached snapshot changed during the render. After a
Bosca change event, update or evict the in-memory value and its validator
together. Cloudflare observes it on the first request after the fresh interval expires; that
triggering request still receives the old shell while the background revalidation completes.

```kotlin
BmlServer(
    project = project,
    pages = BmlPages.all,
    sharedCacheRevisionProvider = { request ->
        publicPageCache.revision(request.pageRoute, request.params, request.query, request.locale)
            ?.let { cached ->
                BmlSharedCacheRevision(
                    etag = cached.etag,
                    lastModified = cached.modified,
                )
            }
    },
)
```

Deferred islands do not make a page public by themselves. An ordinary page can use deferred islands
without `cache="shared"`, and a shared page still needs the same public-data review as any other edge
cached response. CDN rules must bypass `/_bml/**`, authentication, action, and proxy requests. Purge
cached shells when a deployment changes generated renderer IDs or referenced asset versions. The
Cloudflare Cache Rule must mark only reviewed public shell routes eligible, respect origin cache
headers, avoid an Edge Cache TTL override, and exclude `/_bml/**`, GraphQL/proxy, authentication,
action, and other private routes. No **Vary** configuration is required: shared shells never vary by
request headers.

The deferred and action endpoints check the page's `requireAuth` and that the renderer or state
belongs to the posted page, but they do not run the page's own `<script server>` code. Guards written
there (for example `redirectTo` when a record is not visible) do not cover deferred bodies or actions;
put authorization in the data those requests read, as Bosca's GraphQL API does.

## Client runtime (`@bosca/bml`)

| API | Purpose |
| --- | --- |
| `defineIsland(name, (ctx) => …)` | register an island's client setup; runs once per instance with its own `ctx`. An island declared inside a component is named `component:<tag>:<name>` (its `data-bml-island` value), so same-named islands in different components never collide; the compiler registers `<script client>` island bodies under that name. |
| `defineComponent(tag, (ctx) => …)` | register a wrapper-free component setup (normally compiler-generated from `<script client scoped>`) |
| `ctx.root` | this instance's root element (scope DOM access here) |
| `ctx.props` / `ctx.id` | server-passed props / unique instance id |
| `ctx.refs` / `ctx.ref(name)` / `ctx.refsAll(name)` | compiler-typed `ref="…"` elements / dynamic or repeated lookup |
| `ctx.on(target, type, listener)` | add an event listener with automatic unmount cleanup |
| `ctx.dispatch(model.method(args…), options?)` | call this scope's server model; the compiler generates and types the dispatcher |
| `ctx.scoped(name)` | namespaced key (Sortable `group`, storage, events) |
| `ctx.replace(target, html)` | patch a region (Element or root-relative selector) from server HTML, then fire `onUpdate` |
| `ctx.onUpdate(cb)` / `ctx.onUnmount(cb)` | re-arm after a replace / cleanup |
| `mountAll(scope?)` / `unmountAll(scope?)` | mount/unmount islands under a scope |
| `refreshDeferred(root?)` | privately re-render one deferred boundary or all deferred boundaries |
| `renderFragment(component, props)` | re-render a component on the server and get its HTML (sliver update) |
| `bosca.query / bosca.mutate` | typed GraphQL against the configured endpoint (token via the auth library) |
| `configureBosca({ endpoint, getToken, getInstallationId, getAnalyticsSessionId })` | set the GraphQL endpoint, token provider, and optional installation and analytics session providers |
| `bmlContractCall(name, method, args)` | call a `<contract>` method (optional server-side composition) |

After initializing browser analytics, connect its sink to BML in the site's client entry:

```ts
import { configureBosca } from '@bosca/bml'

// `sink` is the BoscaSink returned by the site's analytics initialization.
configureBosca({
  getInstallationId: () => sink.installationId,
  getAnalyticsSessionId: () => sink.sessionId,
})
```

BML reads the getters for each GraphQL, action, contract, and fragment request and attaches
`X-Installation-ID` and `X-BA-Session-ID` when provided. The server uses a valid installation header
before the `bml_iid` cookie and forwards both identities to GraphQL, including calls made during
rendering. Missing or blank IDs add no header.

BML's request lifecycle resolves the analytics session from `X-BA-Session-ID`, then the
`bml_asid` cookie. When neither supplies an ID, a private page render, the `/_bml/identity`
bootstrap, and the same-origin action, contract, sliver, and GraphQL proxy endpoints generate a ULID
and set it with an initial five-minute deadline (`bml_asid_exp`) as browser session cookies, separate
from the `bml_session` island cookie. A shared shell cannot carry that cookie, so its runtime makes
one private `/_bml/identity` request when no browser analytics identity exists, then starts all
deferred renders concurrently. Deferred render requests accept the established installation and
analytics identities but never create either one, and shared shell responses never set them.
While the bootstrap is pending, same-origin BML requests wait for it so they do not each create a
separate identity; a failed bootstrap fails those requests and the next one retries it. After a
bootstrap succeeds, BML does not repeat it for the same identity even if the browser discards the
cookies. Requests to a cross-origin GraphQL endpoint never wait, because they do not carry these
cookies. Existing
IDs are forwarded without setting either cookie or interpreting their deadline; expiration and
rotation belong to the client.
Neither cookie has `Max-Age` or `Expires`. Browser
analytics reads `bml_asid` once during initialization. The SDK then owns session rotation and writes
new IDs back to that cookie. Activity advances the persisted deadline. Later page navigations
reuse an unexpired session; an expired or missing deadline starts a new one. Explicit session
headers remain authoritative for client requests.
Anonymous analytics neither reads nor updates this shared cookie.

### Wrapper-free component setup

Put `<script client scoped>` in a component when its behavior belongs to each component instance.
BML marks the component's existing root (it adds no wrapper), generates DOM types for authored refs,
and unmounts listeners registered with `ctx.on` when the root leaves the document:

```bml
<component tag="copy-button">
  <button ref="button" type="button">Copy</button>
  <script client scoped>
    const button = ctx.refs.button // HTMLButtonElement
    ctx.on<MouseEvent>(button, "click", () => navigator.clipboard.writeText(location.href))
  </script>
</component>
```

Refs are root-local and may sit on the component root itself. Use `refsAll` for a ref authored in a
loop. A page-level `<script client>` remains a normal module for genuinely page-wide behavior.

### Preserving DOM across updates

Declarative actions and `ctx.replace` normally replace the rendered region. Add a stable
`data-bml-preserve` key when an element's existing DOM should survive that update:

```bml
<for item in feed.items>
  <article data-bml-preserve="item-{ item.id }">…</article>
</for>
```

When the next server fragment contains the same key on the same element type, BML moves the
existing element into its new position instead of recreating it. Its root attributes are refreshed
from the server, while its child DOM, focus, media state, and client enhancements remain intact.
Keys must be unique within the updated region. Retained nodes receive `data-bml-retained`, while
new preservation roots receive `data-bml-added`; these markers can suppress old one-shot entrance
animations and highlight only the new batch. `ctx.onUpdate` receives the same `added` and `retained`
preservation roots.

Declarative action elements also receive `aria-busy="true"` while their request is in flight;
buttons are disabled for the same interval.

## Server re-render (sliver updates)

When you want the **server** to render an update — keeping all logic and markup in one place,
no client-side templating — POST the new state and swap the returned HTML. Request/response,
no websockets:

```bml
<island name="counter" :start="0">
  <div data-view><counter-view :count="0"/></div>
  <button>increment</button>
  <script client>
    let n = Number(ctx.props.start ?? 0)
    ctx.root.querySelector("button")?.addEventListener("click", async () => {
      n++
      ctx.replace("[data-view]", await renderFragment("counter-view", { count: n }))
    })
  </script>
</island>
```

`renderFragment(tag, props)` POSTs the props to `/_bml/render/<tag>`; the server re-renders that
**one component** with `propsFromJson` decoding the state back to typed props, and returns the HTML
fragment (with scope markers, no re-injected `<style>`). It's the **same component code** as the
first render, so the markup stays consistent. Call it with a string-literal tag in a `<script client>`:
the compiler adds that component to the page's component closure, so the page links its CSS and
client code, and bml-server accepts the component's server actions for that page. A tag computed at
runtime or passed from an imported module is not visible to the compiler, and neither is a call that
follows a regular-expression literal containing a quote on the same line; reference the component in
the page's markup instead, or its actions return `404`. Wire it up by passing the component renderers
to the server:

```kotlin
BmlServer(
  pages = bml.generated.BmlPages.all,
  components = bml.generated.BmlComponents.all,
  componentRenderers = bml.generated.BmlComponents.renderers, // enables /_bml/render/<tag>
  …
).start()
```

The forwarded `Authorization` token lets the re-rendered component fetch data server-side, exactly
like a first render.

## Instance isolation (reuse-safe)

Every island occurrence is an isolated instance: the setup's `ctx` is scoped to its
own DOM (`ctx.root`), the compiler rewrites in-island `id`s to be unique, and
`ctx.scoped(name)` namespaces anything otherwise-global. So two instances of the same
island on one page never collide.

## Eager rendering + live data

The default `render="request"` mode emits complete server HTML with the page. `render="prerender"`
is reserved and currently uses that same request-rendered path. Client code can fetch fresh data after
mount and replace an island region through `ctx.replace(...)`.

> The client runtime (`@bosca/bml`) is implemented in `bml-runtime`
> (TypeScript, vitest-tested: island lifecycle + GraphQL client). The compiler/server
> emit the SSR markers + island wrappers it mounts onto; per-island bundling
> uses esbuild.

## Declarative and programmatic server actions

The zero-client-code path: a **live state** is either a `@Serializable`, constructor-typed `provides`
model or an explicitly typed `<inject server>` reflected by exactly one view `<island>`. `@event` on
any element (inside the island or not) runs a method on the model **server-side**; the server persists
the model and re-renders the island, and the runtime swaps the view in. No `<script client>` at all.

```bml
<page route="/cart">
  <script server provides="cart" scope="server-session">bosca.bml.sample.Cart()</script>
  <island name="cart-view">
    <p>{ cart.itemCount } item(s) in your cart</p>
  </island>
  <button @click="cart.addItem">Add item</button>
  <button @click="cart.clear">Clear</button>
</page>
```

Every live model has one `scope`:

- `page` is the default. The model JSON round-trips through the rendered page only; navigation or refresh
  uses the newly server-rendered model, which keeps GraphQL-backed state current.
- `client-session` mirrors the serialized model to browser `sessionStorage` and restores it on the next
  render. That saved model is authoritative for the browser session; BML does not merge it with new server data.
- `client-local` mirrors the serialized model to browser `localStorage`. It has the same client-authoritative
  restore behavior but survives tab closure and browser restarts. Do not use client storage for secrets.
- `server-session` keeps the model in the cookie-identified BML server session. Its value never reaches the client.

The default may be written explicitly when the freshness requirement should be visible in the source:

```bml
<script server provides="devices" scope="page">my.site.loadDevices()</script>
<script server provides="draft" scope="client-session">my.site.DraftModel()</script>
<script server provides="preferences" scope="client-local">my.site.PreferencesModel()</script>
<script server provides="accountDraft" scope="client-local" clear-on-sign-out>my.site.AccountDraft()</script>
<script server provides="cart" scope="server-session">my.site.CartModel()</script>
```

Client-backed models are readable by same-origin JavaScript. Add the bare `clear-on-sign-out` marker
only to `client-session` or `client-local` models whose data belongs to the signed-in identity.
After an explicit identity change completes, `clearBmlClientState()` cancels old actions, clears only
marked browser models, asks the next stateful render to replace the opaque server session, and reloads
by default so identity-dependent shell inputs are recomputed. Pass `{ reload: false }` only when the
page shell, deferred props, and fallback content are all identity-independent and the page has no
`server-session` state. Do not bind either form directly to the auth client's `signedOut` event: that
event can also follow a recoverable refresh failure. Unmarked BML browser storage and unrelated
application storage remain untouched.

```ts
async function signOut(): Promise<void> {
  await auth.signOut()
  clearBmlClientState()
}
```

Server-session values expire through the configured cache TTL (30 minutes by default). The default
in-memory store also expires page entries and keeps at most 256 page entries per active session;
site entries are not part of that page bound. Each state key is stored independently under the opaque
session id. If the session or one page entry has expired, the action returns `410 Gone` and the runtime
reloads without replaying it. Reload creates a new session or initializes only the missing page
entry in a still-live session, preserving unrelated site state.

### Arguments

Action expressions take three kinds of arguments, decoded positionally on the server against the
model method's own parameter types (String / Int / Long / Boolean / Double):

| Argument | Bound | Example |
| --- | --- | --- |
| Kotlin expression | at render time, into the element's `data-bml-args` marker | `@click="list.remove(item.markId, ctx)"` inside a `<for>` |
| `form.<field>` | at event time, from the bound form's field (checkbox → boolean) | `@submit="wall.share(form.groupId, form.title, ctx)"` |
| `ctx` | server-side — the dispatcher's `RenderContext` (models that reach the data plane want it) | `@click="mark.toggle(ctx)"` |

`@submit` binds the form's submit event (prevented; fields are read live), so a form inside the
island clears on the post-action re-render.

Every DOM event is supported (`@input`, `@change`, `@ended`, custom events, and so on). Action
modifiers move common persistence behavior into the runtime:

```bml
<input @input.debounce.250.coalesce="search.update(form.query)"/>
<video @timeupdate.throttle.30000.coalesce.keepalive.pagehide="playback.save"/>
```

- `debounce.<ms>` keeps the latest call after a quiet period.
- `throttle.<ms>` sends at most once per interval and retains the latest pending call.
- `coalesce` turns calls arriving during an active request into one follow-up call with the latest arguments.
- `keepalive` makes the fetch unload-safe; `pagehide` flushes a scheduled call with keepalive.

`debounce` and `throttle` are mutually exclusive. Unknown modifiers, invalid action expressions,
discarded action receivers, and inconsistent argument counts are compile errors.

When arguments come from client state, dispatch from an island or scoped component script. BML
rewrites the model expression to a generated server dispatcher while preserving ordinary TypeScript:

```bml
<script client>
  ctx.on<CustomEvent<{ position: number }>>(ctx.root, "save-position", (event) => {
    ctx.dispatch(playback.save(event.detail.position), {
      throttleMs: 30_000,
      coalesce: true,
      keepalive: true,
      flushOnPageHide: true,
    })
  })
</script>
```

### Component models

A `<component>` can declare its own live state — one model **per instance**, so a card in a
`<for>` loop carries its own toggle without a page-level "uber model". The view island needs a
per-instance `:key` (props are in scope):

```bml
<component tag="like-button">
  <prop name="id" type="String" required/>
  <prop name="on" type="Boolean" default="false"/>
  <script server provides="like">myapp.LikeModel(id, on)</script>
  <island name="like-view" :key="id">
    <button @click="like.toggle(ctx)">{ like.count }</button>
  </island>
</component>
```

Component states register under a `"<tag>.<provides>"` prefix and dispatch by their full
per-instance key (`"<tag>.<provides>:<key>"`) — wire `BmlServer(componentIslandDispatchers =
bml.generated.BmlIslands.componentDispatchers)`. Island content may reference **only the model**
(plus loop variables): the same markup re-renders on dispatch, when props no longer exist.

Use `scope="site"` on a root component when the same model must be available across pages. Site
scope remains component-bound; it does not create a global service. It changes the state identity
from pathname-qualified to site-stable, requires durable `client-session`, `client-local`, or
`server-session` model storage, and permits a page to mount only the component action without its
view. A site component has one stable model identity, so its island does not take `:key` and the
component should render at most once on a page. The runtime restores and renders the model only
where the view exists.

This supports converting anonymous browser state during authentication without touching unrelated
pages. The model owns the local items, `sync(ctx)` writes them to the authenticated server model and
resets itself to its default, and the runtime removes the now-default local snapshot. Make `sync`
idempotent: a client can retry after the server commits but before the response arrives. Browser-backed
state is untrusted input, so `sync` must validate and deduplicate item IDs and the server must enforce
the three-item access rule rather than trusting the stored count.

```bml
<component tag="free-access" scope="site">
  <prop name="showUsage" type="Boolean" default="false"/>
  <script server provides="access" scope="client-local">my.site.FreeAccessModel()</script>

  <if showUsage>
    <island name="free-access-usage">
      <p>{ access.used } of 3 free items used</p>
    </island>
  </if>

  <main>
    <script client scoped>
      const unsubscribe = auth.on("signedIn", () => ctx.dispatch(access.sync(ctx)))
      ctx.onUnmount(unsubscribe)
    </script>
  </main>
</component>
```

Generated client dispatch names `access` explicitly, so scoped components with multiple models
route programmatic actions to the correct state. `ctx` is injected on the server and is not sent as
an action argument.

Rules of thumb: one view island per state (v1); a state key must resolve to a constructor-typed
`provides` or an explicitly typed `<inject server>`; anything the re-render needs must live in the
model (a method can refetch via `ctx.gql` and assign back to a `var`).
