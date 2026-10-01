# Getting started with BML

BML (Bosca Markup Language) builds **server-rendered websites, emails, and push templates** from `.bml`
files — HTML-like markup with embedded server Kotlin and client TypeScript. See the
[grammar](/bml-reference/grammar) for the full language and [examples](/bml-reference/examples) for samples.

## Project layout

```
your-site/
├── build.gradle.kts
├── src/main/bml/
│   ├── components/         # reusable <component> declarations
│   └── home.bml            # a <page route="/"> with its document skeleton
└── src/main/graphql/       # GraphQL operations (optional)
```

## Apply the Gradle plugin

```kotlin
plugins {
    kotlin("jvm")
    id("io.bosca.bml")
}

bml {
    sourceDir.set(layout.projectDirectory.dir("src/main/bml"))
    packageName.set("mysite.generated")
}

dependencies {
    implementation("io.bosca:core-bml")   // render runtime + GraphQL client
}
```

## How a build works

1. **`compileKotlin`** treats `.bml` files as compilation sources. The BML K2 compiler plugin parses
   them and contributes generated Kotlin render objects directly to the compilation in memory.
2. The same compilation writes generated client TypeScript, message resources, and metrics inputs
   under `build/generated/bml/` when the source uses those features.
3. Each generated page implements `BmlPageRenderer` and is annotated `@BmlPage(route = "…")`.
   Set `bml { generateKotlinSources = true }` to mirror generated `.kt` and source-map files into
   `build/generated/bml/kotlin` for inspection and debugging; those files are not compile inputs.

Server code sees the render's `RenderContext` two ways: as the `ctx` value in scope inside
`<script server>` / `@click` expressions, and **ambiently** via
`bosca.bml.render.currentRenderContext()` — the server installs it on the coroutine context
around every render and action, so site Kotlin arbitrarily deep in the call stack can read it
without threading a `ctx` parameter. The GraphQL data-plane client has its own shorthand,
`bosca.bml.render.client()` (`currentRenderContext().gql`), and is **never null**: with no
endpoint configured, executing it fails fast with a message naming the fix, so loaders guard on
real conditions (sign-in, visibility) instead of null-checking. Code invoking a renderer
directly (tests, custom hosts) wraps with `withRenderContext(ctx) { … }`.

## Write a page

```bml
<page route="/">
  <html lang="en">
    <head><title>Home</title></head>
    <body>
      <script server provides="greeting">"Hello, world"</script>
      <h1>{ greeting }</h1>
    </body>
  </html>
</page>
```

`<script server provides="greeting">` binds its last expression into the page scope;
`{ greeting }` writes it (HTML-escaped). See the [tag reference](/bml-reference/tag-reference).
Pages currently own their complete document skeleton. Template and `layout=` syntax is reserved but
does not wrap page output.

Host-provided dependencies use an explicit name and type and stay private to their enclosing
page/component/message render:

```bml
<inject name="search" type="com.example.SearchService" init="load" />
```

The generated renderer calls `bosca.di.provide<com.example.SearchService>()`, binds the result as
`search`, and calls `search.load()`. Omit `init` for lookup only, or add `provider="key"` to select a
named Bosca DI binding.

If the injected value is an `@Serializable` view model, add the bare `server` marker to use it as
live state exactly like a constructor-typed `<script server provides="…">`:

```bml
<inject server name="headerAuth" type="com.example.HeaderAuthViewModel" />
<island name="header-auth">
  <span>{ headerAuth.displayName }</span>
</island>
<button @click="headerAuth.signOut">Sign out</button>
```

BML serializes `headerAuth` for the page, decodes it when the action is posted, runs `signOut()` on
the server, and re-renders the island. Add `scope="client-session"`, `scope="client-local"`, or
`scope="server-session"` when the default page lifetime is not appropriate. `client-local` uses
browser `localStorage`, survives browser restarts, and must not hold secrets or sensitive user data.
Add the bare `clear-on-sign-out` marker only when that browser-backed model belongs to the signed-in
identity and should be removed when `clearBmlClientState()` handles an explicit identity change.
Cleanup clears marked browser state without disturbing unmarked browser state, replaces the opaque
server session on the next stateful render, and reloads by default so server-rendered shell inputs are
recomputed for the new identity. After an explicit sign-out completes, use
`clearBmlClientState()` to obtain one fresh signed-out render; do not attach cleanup
directly to the auth client's broader `signedOut` event.
For a component model intentionally shared across routes, declare `<component scope="site">`; this
keeps the model component-owned while giving its durable state one cross-page identity.

## Serve it

Embed `bml-server`: pass the generated pages (each implements `BmlPageRenderer`) and start the
server. `bml-server` registers them directly on Bosca Server's router — Bosca does the routing,
including `{param}` patterns; the server threads path params + the request token into the render.

```kotlin
import bosca.bml.project.CompiledProject
import bosca.bml.server.BmlServer

fun main() {
    val pages = listOf(mysite.generated.HomePage)   // generated objects implement BmlPageRenderer
    BmlServer(
        CompiledProject("my-site", "1.0.0"),
        pages,
        port = 8080,
        graphqlEndpoint = "http://localhost:8080/graphql",
        installationEndpoint = "http://localhost:8081/api/v1/installation",
    ).start()
}
```

`installationEndpoint` is the analytics collector's full installation-registration URL; it is
not derived from `graphqlEndpoint`. If the constructor argument is omitted, `bml-server` reads
`BML_INSTALLATION_ENDPOINT`. Leave both unset when the site does not use installation identity.

## Develop with hot reload

The BML Gradle plugin adds `bmlDev` to projects that also apply Gradle's `application`
plugin:

```bash
./gradlew bmlDev
```

`bmlDev` builds the application and client bundles, starts its configured main class in BML
development mode, and then lets Gradle watch the real inputs of that task graph. An edit to BML,
Kotlin, GraphQL, resources, client TypeScript/CSS, or a configured local client runtime triggers an
incremental rebuild. After a successful build, Gradle snapshots the application classes and
resources and the persistent launcher loads them in a fresh child classloader. The new `BmlServer`
configuration atomically replaces the current page/component/contract state; Netty keeps the same
PID and port, and server-scoped sessions remain intact. The server announces the new generation over
SSE so open browser tabs reload themselves. A compilation failure leaves the last good generation
serving while Gradle keeps watching; fixing the source swaps the next successful generation in.

The hot-swap boundary is the application output: BML-generated code, project Kotlin, generated
GraphQL code, and project resources. Client bundles are rebuilt and served from their live output
directory. Changes to BML framework or other runtime dependency jars require stopping and starting
`bmlDev`, because those libraries intentionally stay in the parent classloader.

In IntelliJ, run **Tasks → bml → bmlDev** from the Gradle tool window (or create a Gradle run
configuration for the `bmlDev` task). The task enters Gradle's reloadable-deployment mode itself;
no `--continuous` flag or separate `bosca` CLI process is required after a successful initial
build. Stopping the IntelliJ run configuration stops both the Gradle watch and the managed server
JVM.

The ordinary `run` task remains useful for one compile-and-run cycle. The BML plugin enables its
development-mode SSE and current client-bundle directory automatically, but `run` alone does not
watch sources or hot-swap application classes.

## CLI

The `bosca` CLI scaffolds and compiles BML projects:

```bash
bosca bml init my-site          # scaffold a project
bosca bml compile my-site       # generate .kt once (.bml → build/generated/bml/kotlin)
bosca bml dev my-site           # delegate to ./gradlew bmlDev
```

`bosca bml dev` delegates directly to the project's wrapper (or `gradle` on `PATH` when there is
no wrapper) and its `bmlDev` task. Gradle therefore owns the same incremental compile, bundle,
persistent server JVM, child-classloader swap, and browser reload lifecycle used from IntelliJ.
Use `--gradle-task` when the project exposes that lifecycle under a custom task path.

For one-shot source generation without an application lifecycle, use `bosca bml compile`.

## Data via GraphQL

All data is fetched through the **Bosca GraphQL API** — never direct Bosca services.
The endpoint is configurable and the caller's token is forwarded (passthrough), so
local dev can point at a remote Bosca and the same code runs unchanged once deployed.
In a `<script server>` use the in-scope GraphQL client; islands call it client-side too.
When both `io.bosca.bml` and `io.bosca.graphql` are applied, the build generates Kotlin operations
and browser TypeScript operations from the same `src/main/graphql` documents. Client modules import
typed functions from `./graphql/<OperationName>`; `bmlBundleClient` orders generation before esbuild.

## Feature flags

Feature flags are evaluated through Bosca's GraphQL API and can be used directly as BML control flow:

```bml
<if flag="new-checkout">
  <checkout-v2/>
<else-if flag="checkout-layout" variation="compact">
  <compact-checkout/>
<else-if flag="checkout" :when='flag.value is kotlinx.serialization.json.JsonObject && !flag.degraded'>
  <configured-checkout/>
<else>
  <checkout-v1/>
</if>
```

The boolean and variation forms cover common cases. `:when` accepts Kotlin and binds `flag` to the
complete `FeatureFlagEvaluation`, so it can inspect structured `value` data or targeting metadata.
Server scripts and site Kotlin can use the same facade directly:

```kotlin
val flags = bosca.bml.render.currentRenderContext().featureFlags
val evaluation = flags.evaluate("checkout")
val enabled = flags.enabled("new-checkout")
```

The facade also provides `evaluateAll`, `variation`, `string`, `number`, and raw `value` helpers.
Results are cached for the current render. BML Server obtains a stable anonymous installation id
from Bosca and stores it in the browser-readable `bml_iid` first-party cookie; authenticated
requests still carry their normal token, allowing Bosca to resolve the principal.

Feature evaluation is fail-closed: an unavailable flag request is logged and becomes a degraded,
unknown evaluation with a blank variation key and JSON `null` value, so boolean and variation
conditions select their ordinary fallback branch instead of preventing the page from rendering.
Explicit helper defaults are honored as usual.

Client islands use the site's existing configured `FeatureFlagClient` from
`@bosca/analytics-client-browser/core`. BML does not create a second browser feature client.

The Bosca-issued `bml_iid` cookie is available before response scripts run and is canonical because
the page's variation has already been selected with it. Bosca analytics reads that cookie first, so
anonymous conversion events join the same assignment. Outside BML, analytics retains its existing
`__iid` storage and registration behavior and writes newly registered IDs to the same cookie.
Authenticated sites use the analytics client's normal `BoscaSink.setUserId(principalId)` integration;
feature flags add no second user-identity mechanism.

Flag values are not copied into every analytics event. Bosca records the variation assignment when
the flag is evaluated, and analytics events carry the stable principal or installation join key.
This keeps event payloads bounded while preserving variation attribution during aggregation.

Feature-flag conditions are unavailable in `<message>` renders because messages currently have no
recipient feature identity. BML rejects direct message conditions; a separately compiled component
that attempts an evaluation receives the same degraded, unknown fallback used for other unavailable
feature evaluations, avoiding assignments to the message producer instead.

## Locales

**The site's Bosca localization project defines its locales** — the project's source language is
the default and its declared target languages are the rest, so the site and its translators can
never drift apart, and adding a language in Bosca reaches the site without a redeploy. Every
request then negotiates its locale: an explicit `?lang` override, else the site-managed
`bml_locale` cookie, else full `Accept-Language` negotiation (RFC 4647 — q-weights, `en;q=0`
exclusions, `es-MX` → `es` truncation, broad `pt` served by a `pt-BR`-only site), always
constrained to the project's list. A site with no localization project is single-locale `en`
and negotiation is inert.

The result is `ctx.locale` (a `java.util.Locale` — feed it to formatters) with the BCP-47 tag as
`ctx.lang`; deep site code reads it ambiently via `currentLocale()`. Pages own their `<html>`
skeleton, so set the document language from it:

```html
<html lang="{ ctx.lang }">
```

A locale-switcher is site code, not framework code: set the `bml_locale` cookie from client JS
(like `bml_token`) and reload. Language direction for RTL locales can be derived from the
platform language registry's attributes when a site needs it.

### Localized strings

Bind the site to its localization project and every `t`/`t:` lookup renders that project's
`PUBLISHED` translations (see grammar.md §8.1 for the authoring syntax):

```bash
BML_LOCALIZATION_PROJECT=acme-site        # project name or UUID — also defines the locales
BML_LOCALIZATION_TOKEN=<service token>    # catalog reads are server-initiated
BML_LOCALIZATION_TTL_SECONDS=300          # refresh cadence (0 = every read, for dev)
BML_LOCALIZATION_STATES=PUBLISHED         # widen (e.g. +IN_REVIEW) for preview environments
```

Catalogs cache in-process with stale-while-revalidate; an unreachable Bosca degrades to the
authored fallbacks, never a failed page. Client islands can localize too: `loadMessages()` +
`t()` from `@bosca/bml` fetch the merged catalog from `/_bml/i18n/<locale>.json` (zero extra
bytes for pages that don't use it). Message projects use the same machinery on the message server
(`BML_GRAPHQL_ENDPOINT` + `BML_MESSAGE_LOCALIZATION_*`), rendering each message in the recipient's
`bosca.profiles.locale` setting; Studio's Email Preview has a locale picker.

The authoring loop: build, then

```bash
bosca bml i18n extract                    # what the compiled project declares
bosca bml i18n push --project <uuid>      # create keys + seed source-language text
bosca bml i18n status --project <uuid>    # translation coverage per language
```

Native images must bundle locale data (`-H:+IncludeAllLocales` — without it, number/date
formatting silently degrades to root-locale output; see the bml-message-server build file).
All locales deliberately: the localization project defines the platform's locales as runtime
data, so an enumerated build-time list would silently break any language added after the build.

## Targets

The same language compiles to four presentation targets:

- **web** — full HTML + islands (default),
- **email** — inlined CSS, table-safe, no JS (`EmailRenderer`),
- **plain text** — a readable text projection (`PlainTextRenderer`),
- **push** — localized title/body plus provider-neutral delivery metadata.

## Message templates

A **message project** authors each communication as a `<message key="…">` unit. Shared server
Kotlin sits at the message level; sibling `<email>` and `<push>` regions opt into channels:

```html
<message key="welcome">
  <script server provides="m">message.payload(WelcomePayload.serializer())</script>
  <push>
    <title t="welcome.push.title">Welcome!</title>
    <body t="welcome.push.body">Your account is ready.</body>
    <action id="open" default t="welcome.push.action.open">Open Bosca</action>
    <image/>
    <attachments/>
    <conversation/>
  </push>
  <email>
    <subject t="welcome.subject">Welcome, { message.recipientName ?: "friend" }!</subject>
    <html><body><h1>{ m.courseName }</h1></body></html>
  </email>
</message>
```

The compiler lowers each unit to an object implementing
`bosca.bml.message.BmlMessageTemplate` — `renderMessage(message: BmlMessageContext)` returns
`RenderedMessage(email, push)` — and lists them in `bml.generated.BmlMessages`. Email HTML has
scripts stripped and includes a plain-text alternative. The typed payload crosses as JSON and is
decoded inside the template, so hosts stay payload-type-agnostic. Every channel shares the same
payload, locale, message source, and published version. `t="key"` on `<subject>`, push `<title>`,
`<body>`, or `<action>` records the authored fallback in the i18n manifest and resolves it at render time.
The push region also inherits the producer-supplied
`BmlPushOptions` render context, including `BmlPushRichContent` for remote image/media URLs or a
small conversation preview. Localized `<action id="…">` tags select producer-supplied actions and
overlay their visible labels while retaining their destinations and routing data. Mark at most one
action `default` for notification-open behavior. Use an explicit `:options` binding only when the template deliberately overrides
that delivery metadata. `<image/>`, `<attachments/>`, and `<conversation/>` select concepts from
that producer-owned rich content; the tags do not contain mapping code or data. Do not place full
chat history or media bytes in a provider payload.
See `docs/grammar.md` §4.1b.

**Packaging:** a message project is an ordinary standalone BML build; its plain
`jar` IS the deployable unit — compiled templates + `bml.generated.BmlMessages`, with
`core-bml` as a dependency (never bundled: the host shares it via the parent classloader).
The compiler embeds the versioned artifact manifest at
`META-INF/bml/message-manifest.json`
listing the module entry point and every template key, so registries can describe a jar
without classloading it. Published artifacts follow `bosca.bml.message.BmlMessageArtifacts`:
`(type=raw, namespace=bml-message, coordinate=<projectKey>, version)`. Those artifact and deployment
identifiers define the message runtime's artifact contract.

**Publishing:** the project commits a git-ci pipeline (`.bosca/pipelines/*.yaml`)
that, on push to main, runs `./gradlew build` and PUTs the jar to the raw registry
(`/raw/bml-message/api/<projectKey>/<version>/<file>`, auth from `setup-registry`). Push
runs mint their version in-step (`<utc-timestamp>-<short-sha>` — lexicographically
monotonic), so the declarative `artifacts:` block doesn't apply; the step verifies its own
upload. Creating the version makes the registry publish the existing
`ArtifactVersionPublished` event on `bosca.artifacts.version.published` — the runner's
message runtime subscribes there and filters `namespace == "bml-message"`; no new event
machinery. Studio commits reach the same trigger via `RefUpdateNotifier`.

## Measuring page weight

Page size is a build output, not a guess. Two tools ship with the toolchain:

**`./gradlew bmlMetrics`** — reports every page's first-load payload from the compiled
site: the island JS bundle, the CSS chunks of the components the page renders
(transitive, resolved at compile time), and the site's global tier
(`bml { metricsGlobalAssets }`, seeded with the CSS under `src/main/client`), each in raw
and gzip bytes. Prints a table and writes `build/reports/bml/site-metrics.json` for CI
trend lines. The data comes from `build/generated/bml/metrics/` (a `manifest.tsv` plus one
`css/<tag>.css` per styled component), written by the compiler plugin during compilation.

**`bosca bml audit <base-url> [-r /route]... [--json out.json]`** — measures a *running*
site over HTTP: fetches each route's HTML, discovers the assets it declares (stylesheets,
scripts, preloads, images), and records real transfer bytes (compressed, as served),
decoded bytes, and per-request timing. It is deliberately framework-agnostic — point it at
a BML deployment and at the same pages on any other stack for a 1:1 comparison. Assets
referenced from *inside* CSS (fonts, background images) are counted but not fetched, since
browsers download only the subsets they need.

### The production asset shape

In production (`BmlServer(dev = false)`), a page downloads at most **one page stylesheet and one
page bundle** on top of **one global stylesheet and one global script**:

- `/_bml/app.css` — the site's global CSS **plus** the scoped styles of components rendered by
  every page (the normalized shared set).
- `/_bml/css/<slug>.page.css` — the page's remaining component styles, merged in chunk-link order.
- `/_bml/js/<slug>.page.js` — the page's client code and its closure components', bundled as one
  file by `bundle.mjs --manifest … --prod-out …` (run automatically by `bmlBundleClient` into
  `build/generated/bml/js-prod`). **Production deployments must pass that directory as
  `BmlServer(clientDir = …)`** — the per-module dev files are no longer injected.

Production HTML appends one deterministic deployment digest (for example `?_ts=9c48…`) to every
generated CSS and JS URL. The digest covers the project version, generated page and component
source, compiled asset maps, global assets, and bundled client files. Replicas running the same
deployment therefore emit the same URLs, while a relevant source or asset change produces a new URL.
The generated asset responses use `Cache-Control: public, max-age=31536000, immutable`; the token
makes that one-year TTL safe by changing the URL on the next deployment. The underlying routes stay
unchanged. Production JS uses syntax, whitespace, and local-identifier minification without
property-name mangling (public globals, datasets, and generated contracts keep their runtime names),
and production source maps are not emitted. Files in `src/main/client` are also parsed, bundled, and
minified into `build/generated/bml/client-assets`; CSS uses esbuild's CSS parser rather than unsafe
text-level whitespace removal.

Dev mode keeps the per-component chunks and per-module JS so an edit invalidates one small file.
`bmlMetrics` prices the production shape whenever the prod bundles exist.
