# BML tag & syntax reference

Bosca owns the entire tag vocabulary. See the [full grammar](/bml-reference/grammar).

## Expressions & text

| Form | Meaning |
| --- | --- |
| `{ expr }` | server-evaluated Kotlin expression, **HTML-escaped** |
| `{@ expr }` | server expression, **raw** (trusted HTML) |
| `{# … #}` | build-only comment (never emitted) |
| `<!-- … -->` | HTML comment (emitted) |
| `\{ \} \< \\` | literal `{ } < \` in text |

## Attributes

| Form | Meaning |
| --- | --- |
| `name="literal"` | static value (may interpolate: `class="c-{ size }"`) |
| `name` | boolean attribute (bare) |
| `:name="expr"` | **bound** — value is a server expression (no braces) |
| `{...expr}` | spread a `Map<String, Any?>` of attributes |
| `@event="model.method(…)"` | dispatch a live-model method for any DOM event; supports `debounce`, `throttle`, `coalesce`, `keepalive`, and `pagehide` modifiers |

## Special tags (protected — cannot be overridden)

| Tag | Purpose |
| --- | --- |
| `<page route="…" [contentType="…"] [requireAuth] [cache="shared" maxAge="seconds" staleWhileRevalidate="seconds"]>` | a routable SSR response whose body owns the full document skeleton; shared caching renders a public/anonymous HTML shell and supports Cloudflare background revalidation |
| `<route path="…" [contentType="…"] [cache="shared" maxAge="seconds" staleWhileRevalidate="seconds"]>` | a response-oriented route using the same renderer, cache policy, and route registry as `<page>` |
| `<template name="…">` | reserved syntax; template/layout code generation is not active |
| `<slot [name]>` | component children; named slots currently use the default children and emit a warning |
| `<component tag="…" [scope="page\|site"]>` | declare a custom tag or override a built-in; live state has page lifetime by default or site lifetime explicitly |
| `<prop name type [required] [default\|:default]>` | a typed component input |
| `<for item in expr>` / `<for (i, item) in expr>` | iteration |
| `<if expr> … <else-if expr> … <else> … </if>` | Kotlin conditionals (one `</if>`; bare separators) |
| `<if flag="key" [variation="name" \| :when="expr"]>` | feature-flag boolean, variation, or full-evaluation condition |
| `<data provides="name">{ expr }</data>` | sugar for a server binding |
| `<inject [server] name="value" type="Type" [provider="key"] [init="method"] [scope="page\|client-session\|client-local\|server-session"] [clear-on-sign-out]/>` | resolve Bosca DI `provide<Type>()`; `server` promotes a serializable injected view model to live server-action state; sign-out cleanup is opt-in for browser-backed state |
| `<island name client hydrate render="request\|prerender\|deferred" [:prop]>` | interactive island; deferred bodies render privately after shell load (see the [islands reference](/bml-reference/islands)) |
| `<fallback>` | initial/error content for its direct deferred-island parent |
| `<script server [provides="name"] [scope="page\|client-session\|client-local\|server-session"] [clear-on-sign-out]>` | embedded Kotlin; live model scope defaults to the current page; sign-out cleanup is opt-in for browser-backed state |
| `<script client>` | embedded TypeScript (island module) |
| `<contract>` | a Kotlin interface for typed client↔server RPC |
| `<style>` | global CSS (inlined as written) |
| `<style scoped>` | component-scoped CSS — see [Scoped styles](#scoped-styles) |

### Feature flag conditions

```bml
<if flag="new-checkout">
  <checkout-v2/>
<else>
  <checkout-v1/>
</if>

<if flag="checkout-layout" variation="compact">
  <compact-checkout/>
</if>

<if flag="checkout" :when='flag.variationKey == "compact" && !flag.degraded'>
  <compact-checkout/>
</if>
```

The simple form expects a JSON boolean. `variation` compares the selected variation key. `:when`
accepts an arbitrary Kotlin expression and binds `flag` to the complete evaluation, including
`value`, `variationKey`, `experimentId`, and `degraded`. Use either `variation` or `:when`, not both.
Flag and variation keys are literal values; ordinary `<if kotlinExpression>` remains unchanged.
If feature evaluation is unavailable, BML logs the failure and selects the ordinary fallback branch;
the failure does not abort page rendering. A `:when` predicate can inspect `flag.degraded` explicitly.
Feature-flag conditions are unavailable in `<message>` renders because messages do not currently
carry a recipient feature identity.

### Application sitemap

A site can provide a dynamic sitemap as a BML route at the conventional path. The server responds
as `application/xml` and does not inject page CSS, client scripts,
or development reload markup. Emit the XML as trusted raw output:

```bml
<route path="/sitemap.xml" contentType="application/xml">
  <script server provides="sitemap">
    loadSitemapXml()
  </script>
  {@ sitemap }
</route>
```

When the project has no exact `/sitemap.xml` page or route, the server continues to serve the
sitemap from `CompiledProject.discovery.sitemap`. Parameterized routes do not capture the discovery URL.

## Built-in HTML tags

The standard HTML element set is built in (`a`, `div`, `p`, `h1`–`h6`, `ul`/`li`,
`table`/`tr`/`td`, `form`/`input`, `img`, `br`, …). Void elements (`br`, `img`,
`meta`, `link`, `hr`, `input`, …) take no children/closing tag. The names BML reuses
as special tags (`data`, `inject`, `slot`, `template`, `script`, `style`) resolve to the special
tag — reach the native element via the `html:` namespace.

## Overriding a built-in & custom tags

```bml
<component tag="a">
  <prop name="href" type="String" required/>
  <html:a :href="href" rel="noopener"><slot/></html:a>   <!-- html:a = the native anchor -->
</component>
```

- Declaring `<component tag="a">` overrides every `<a>`. Emit the native tag via
  the **`html:` namespace** (`<html:a>`) to avoid recursion.
- Custom tags (`<component tag="card">`) register a new tag with typed `<prop>`s.
- **Protected** tags (the special set above) cannot be overridden — it's a compile error.

## Resolution order

`<name>` resolves: special tag → registered custom/override → built-in HTML → error.
The `html:` namespace always resolves to the native HTML handler, bypassing overrides.

## Scoped styles

A `<style scoped>` inside a `<component>` is **isolated to that component**, Nuxt-style —
you write plain selectors and BML guarantees they can't leak out and outside styles can't
leak in:

```bml
<component tag="badge">
  <prop name="tone" type="String" default="info"/>
  <style scoped>
    .badge { padding: 2px 8px; border-radius: 4px; }
    .badge-hot { background: #ffe0e0; color: #b00020; }
  </style>
  <span class="badge badge-{ tone }"><slot/></span>
</component>
```

How it works:

- The compiler stamps a marker attribute (`data-bml-c="badge"`) onto **every element the
  component renders**, and rewrites each selector to require it — `.badge` becomes
  `.badge[data-bml-c="badge"]`. Descendant/child combinators scope the last compound
  (`.list li` → `.list li[data-bml-c="badge"]`); `@media`/`@supports`/`@container`/`@layer`
  recurse; `@keyframes`/`@font-face` are left untouched.
- Slotted content is scoped to the **caller**, not the component (it's the caller's markup).
- The scoped CSS is inlined **once per page** even if the component renders many times.
- A plain `<style>` (no `scoped`) stays global, exactly as written.

> The scoped CSS is also exposed on the generated component object (`BadgeComponent.styles`,
> `.scope`) so the asset pipeline can serve it as a cached per-component stylesheet instead of
> inlining — see [Asset tiers](#asset-tiers) below.

## Asset tiers

CSS and JS ship in **three tiers**, so a page loads only what it uses and shared chunks stay cached:

| Tier | What | How it's delivered |
|------|------|--------------------|
| **Global** | site-wide `app.css` / `app.js` | linked on every page (`/_bml/app.css`, `/_bml/app.js`) |
| **Per-page** | the page's own `<style>` / `<script client>` | page CSS inlined in document order; page JS bundled to `<Page>.js` |
| **Per-component** | a component's `<style scoped>` (and, later, its island JS) | served as a cached chunk (`/_bml/css/<tag>.css`), linked **only on pages that render the component** |

The compiler records, per page, the components it renders (`BmlPageRenderer.componentTags`) and emits a
`bml.generated.BmlComponents` registry. The server expands that list to the transitive closure (a
component can render other components) and links exactly those component stylesheets — nothing more. In
this **linked mode** the per-component `<style>` is *not* inlined (the scope markers remain, so the
linked stylesheet still matches); zero-config SSR / email rendering keeps inlining instead.

Wire it up by handing the server the generated registries and your globals:

```kotlin
BmlServer(
  project = CompiledProject("my-site", "1.0.0"),
  pages = bml.generated.BmlPages.all,
  components = bml.generated.BmlComponents.all,   // enables linked per-component CSS
  globalCss = File("app.css").readText(),
  globalJs = File("app.js").readText(),
).start()
```
