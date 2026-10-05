# BML Grammar (v1)

This is the canonical grammar for `.bml` source files. It is the contract the
frontend parser implements and the compiler plugin
lowers.

Notation: `::=` defines a production; `|` alternation; `*` zero-or-more; `+`
one-or-more; `?` optional; `( )` grouping; `'x'` a literal; `UPPER` a lexer
terminal; `« »` a prose constraint.

---

## 1. Overview & file kinds

A `.bml` file is UTF-8 text. It contains a sequence of **top-level nodes**: any
number of file-scoped **code regions** plus exactly one **primary declaration**
(a page, route, or message), *or* one-or-more **component declarations**.

```
File          ::= Bom? TopLevelNode*
TopLevelNode  ::= CodeRegion | Page | Route | Message | Component | Comment | Text«ws-only»
```

Constraints:
- A file MUST contain **at most one routable declaration** (`Page` or `Route`) and **at most one**
  `Message`; a routable declaration and a `Message` MUST NOT share a file (one render unit per file).
- A file containing `Component` declarations MUST NOT also contain a `Page`, `Route`, or `Message`.
- File-scoped `CodeRegion`s (`<script server>`, `<script client>`, `<contract>`)
  apply to the file's primary declaration's scope.
- Non-whitespace text at top level is an error.

File kinds (by content), used for tooling/routing:
- **Routable file** — has a `<page route="…">` or `<route path="…">`. An SSR response entry point.
- **Message file** — has a `<message key="…">` containing `<email>`, `<push>`, or both.
- **Component file** — has one or more `<component tag="…">`. Defines/overrides tags.

Template and `layout=` syntax remains reserved, but the current compiler does not generate template
renderers or wrap pages in layouts. A routable file therefore owns its complete HTML document.

---

## 2. Lexical structure

### 2.1 Whitespace & encoding
- Source is UTF-8; an optional leading BOM is ignored.
- Line terminators: `\n`, `\r\n`, `\r` (normalized to `\n` for offsets, but raw
  bytes are preserved inside raw-text regions).
- Whitespace inside text is preserved by the lexer; the renderer MAY
  collapse runs per target. Whitespace between sibling non-inline tags is not
  semantically significant.

### 2.2 Comments
```
Comment       ::= HtmlComment | BmlComment
HtmlComment   ::= '<!--' «any, not containing '-->'» '-->'
BmlComment    ::= '{#' «any, not containing '#}'» '#}'
```
- `HtmlComment` is **emitted** to HTML output (unless the target strips it).
- `BmlComment` is **build-only** and never emitted. Valid anywhere a node or
  attribute may appear.

### 2.3 Text & escapes
```
Text          ::= (TextChar | Escape | Entity)+
TextChar      ::= «any char except '<', '{', '\', and '#}' / '{#' starts»
Escape        ::= '\{' | '\}' | '\<' | '\\'        « -> literal { } < \ »
Entity        ::= '&' Name ';' | '&#' Digit+ ';' | '&#x' HexDigit+ ';'
```
HTML entities pass through unchanged. To emit a literal `{` or `<`, use the
backslash escape.

### 2.4 Expressions (server-evaluated)
```
Interp        ::= '{' Expr '}'           « HTML-escaped output »
InterpRaw     ::= '{@' Expr '}'          « unescaped/raw HTML output (trusted) »
```
- `Expr` is the **expression sublanguage** (§6); it evaluates server-side
  against the current scope and lowers to Kotlin.
- The `{ … }` braces balance with respect to Kotlin string/char literals and
  nested `{}`; a `}` inside a `"…"`/`'…'`/`"""…"""` literal does not close the
  interpolation.
- `Interp`/`InterpRaw` may appear in `Text` and inside **static** attribute
  values (§3.2). Whole-value dynamic binding uses `:name="expr"`, not braces.

### 2.5 Raw-text regions
The content of `script`, `contract`, and `style` elements is **raw text**: the
lexer does not parse markup or expressions inside them. A raw-text region ends
at the first matching end tag `</name …>` (case-insensitive `name`). The exact
byte span of the content is recorded for source maps (§10).

> Embedded Kotlin/TS that must contain the literal sequence `</script>` inside a
> string should split it (`"</scr" + "ipt>"`); the parser follows the HTML
> raw-text rule and would otherwise terminate early.

### 2.6 Names
```
Name          ::= NameStart NameChar*
NameStart     ::= Letter | '_'
NameChar      ::= Letter | Digit | '-' | '_' | '.'
```
Tag and attribute names are case-sensitive. Built-in HTML tag names are lower-case.

---

## 3. Grammar

### 3.1 Elements
```
Node          ::= Element | RawElement | Control | Comment | Interp | InterpRaw | Text
Element       ::= '<' QName Attr* ( '/>' | '>' Node* '</' QName '>' )
RawElement    ::= ScriptServer | ScriptClient | Contract | Style
QName         ::= ( Name ':' )? Name        « native namespaces: `html:` and `svg:` »
```
- Void/self-closing form `<name … />` has no children.
- The end-tag `QName` MUST match the start-tag `QName`.
- A `html:`-prefixed name (e.g. `<html:a>`) always resolves to the **built-in
  HTML** handler, bypassing any override — see §7.
- A `svg:`-prefixed name always resolves to the native SVG handler. Inside `<svg>`, the complete
  SVG element vocabulary resolves natively as well (including filters, gradients, masks, and `<use>`).
- HTML void elements (`br`, `img`, `meta`, `link`, `hr`, `input`, …) MAY omit
  the closing slash and have no children.

### 3.2 Attributes
```
Attr          ::= StaticAttr | BoundAttr | EventAttr | Spread
StaticAttr    ::= Name ( '=' AttrValue )?
AttrValue     ::= '"' (AttrText | Interp | InterpRaw)* '"'
                | '\'' (AttrText | Interp | InterpRaw)* '\''
BoundAttr     ::= ':' Name '=' ( '"' Expr '"' | '\'' Expr '\'' )
EventAttr     ::= '@' Name ('.' EventModifier)* '=' ( '"' Expr '"' | '\'' Expr '\'' )
EventModifier ::= 'debounce' '.' NonNegativeInteger | 'throttle' '.' NonNegativeInteger
                | 'coalesce' | 'keepalive' | 'pagehide'
Spread        ::= '{' '...' Expr '}'             « spread a Map<String,Any?> of attrs »
```
- **Static** `name="literal"` — the value is a literal that MAY contain `{ expr }`
  interpolations: `class="card card-{ size }"`.
- **Bound** `:name="expr"` — the quoted value is a single server **expression**
  (no braces): `:href="user.url"`, `:disabled="isLocked"`. A boolean expression
  that is `false`/`null` omits the attribute; `true` renders the bare name.
- **Spread** `{...expr}` — merges a `Map<String, Any?>` of attributes.
- **Event** `@event="model.method(…)"` dispatches a method on a live server model. Any DOM event is
  supported; `@submit` prevents ordinary form submission and resolves `form.<field>` arguments at
  event time. Scheduling and unload modifiers are described in [islands.md](islands.md#arguments).

### 3.3 Code regions
```
ScriptServer  ::= '<script' WS 'server' ( WS 'provides' '=' AttrValueStr )? Attr* '>' RAWTEXT '</script>'
ScriptClient  ::= '<script' WS 'client' Attr* '>' RAWTEXT '</script>'
Contract      ::= '<contract' Attr* '>' RAWTEXT '</contract>'
Style         ::= '<style' Attr* '>' RAWTEXT '</style>'
```
- `RAWTEXT` is captured verbatim (§2.5) with byte offsets.
- `<script server>` body is **Kotlin**; `<script client>` body is **TypeScript**;
  `<contract>` body is **Kotlin interface declarations**.

---

## 4. Special tags (semantics)

All special tags are **protected** (§7) and cannot be overridden.

### 4.1 `<page>` and `<route>`
```
Page          ::= '<page' PageAttr+ '>' Node* '</page>'
PageAttr      ::= 'route' '=' '"' RoutePattern '"' | 'contentType' '=' '"' MIME_TYPE '"'
                | 'requireAuth'
                | 'cache' '=' '"shared"' | 'maxAge' '=' '"' PositiveInteger '"'
                | 'staleWhileRevalidate' '=' '"' PositiveInteger '"' | Attr
Route         ::= '<route' RouteAttr+ '>' Node* '</route>'
RouteAttr     ::= 'path' '=' '"' RoutePattern '"' | 'contentType' '=' '"' MIME_TYPE '"'
                | 'requireAuth'
                | 'cache' '=' '"shared"' | 'maxAge' '=' '"' PositiveInteger '"'
                | 'staleWhileRevalidate' '=' '"' PositiveInteger '"' | Attr
MIME_TYPE     ::= «valid HTTP media type with optional parameters»
PositiveInteger ::= «decimal integer greater than zero»
NonNegativeInteger ::= «decimal integer greater than or equal to zero»
RoutePattern  ::= ('/' Segment)*  '/'?
Segment       ::= Name | '{' Name ( ':' TypeRef )? '}'
```
- `route` declares the URL pattern. Path params `{name}` and typed `{name:UUID}`
  become in-scope `val`s (default type `String`).
- `<route path="…">` is the response-oriented form of the same routable declaration. Its literal,
  non-blank `path` becomes the generated renderer's route and supports the same path params.
- Pages emit exactly the markup in their body and therefore own the full document skeleton, including
  `<html>`, `<head>`, and `<title>`. `layout`, page-level `title`, and page-level `render` attributes
  are reserved but currently have no generated behavior; the compiler warns when a page uses them.
- `contentType` sets the HTTP response media type and defaults to `text/html`. The server applies
  stylesheet, client-script, and development-reload injection only to HTML responses.
- `requireAuth` redirects a request without a token to the configured sign-in route. It cannot be
  combined with `cache="shared"`.
- `cache="shared"` with a positive `maxAge` opts a successful HTML shell into shared edge caching.
  `maxAge` is Cloudflare's fresh interval. `staleWhileRevalidate` is the interval during which
  Cloudflare may serve an expired shell while it conditionally refreshes in the background; it
  defaults to `maxAge`.
  The shell renders with public/anonymous context and cannot require authentication, initialize eager
  server-session state, or evaluate identity-based feature flags (the compiler checks `<if>` conditions,
  server scripts, interpolations, loop iterables, attribute expressions, `@event` action arguments, and
  deferred-island inputs, ignoring Kotlin strings and comments). Deferred and action endpoints remain
  private and uncacheable. Query values are part of the URL cache key, and the URL alone selects the
  shell: its locale is `?lang` or the site default. On a site with several locales, a request without
  `?lang` renders privately instead. BML emits browser `Cache-Control` separately
  from `Cloudflare-CDN-Cache-Control`; `s-maxage` is deliberately absent because it disables
  stale-while-revalidate behavior. An optional `BmlSharedCacheRevisionProvider` can derive a
  replica-stable validator from Bosca content ETags and modified times. BML combines that data with
  the route variant and deterministic deployment revision, then verifies it again after rendering;
  a concurrent revision change falls back to hashing the completed response. Localized sites always
  render before conditional validation so the validator covers the catalog snapshot actually used.

### 4.1b `<message>` channel units
```
Message       ::= '<message' MessageAttr* '>' ServerScript* ( Email | Push )+ '</message>'
MessageAttr   ::= 'key' '=' '"' Name '"' | Attr
Email         ::= '<email>' Subject Node* '</email>'
Subject       ::= '<subject' Localized? '>' ( Text | Interp )* '</subject>'
Push          ::= '<push' ( ':options' '=' '"' Expr '"' )? '>' ( PushTitle | PushBody | PushAction | PushRich )* '</push>'
PushTitle     ::= '<title' Localized? '>' ( Text | Interp )* '</title>'
PushBody      ::= '<body' Localized? '>' ( Text | Interp )* '</body>'
PushAction    ::= '<action' 'id' '=' '"' Name '"' Default? Localized? '>' ( Text | Interp )* '</action>'
Default       ::= 'default'
PushRich      ::= '<image/>' | '<attachments/>' | '<conversation/>'
Localized    ::= 't' '=' '"' MessageKey '"'
```
- A message unit compiles to an `object` implementing `bosca.bml.message.BmlMessageTemplate`:
  `suspend fun renderMessage(message: BmlMessageContext): RenderedMessage`. Each unit may declare
  email, push, or both. The email result contains email-safe HTML (scripts stripped via
  `EmailRenderer`) plus a plain-text alternative (`PlainTextRenderer`).
- `key` is the template's stable key within its project; defaults to the file's base name
  (`emails/course-welcome.bml` → `course-welcome`).
- An `<email>` region requires `<subject>`; the rest of its children are the body document.
- A `<push>` region renders a notification from the same server code,
  typed payload, localization context, project, and published artifact version. Its `<title>`
  and `<body>` are required plain-text regions and never appear in the email HTML.
- `t="key"` on `<subject>`, push `<title>`, `<body>`, or `<action>` uses the authored text as the source
  fallback, harvests the entry into `bml/i18n-manifest.json`, and resolves against the same locale
  and `MessageSource` as email markup. Interpolations become named localization placeholders.
- A bare `<push>` inherits the `BmlPushOptions` supplied by the communication producer through
  `BmlMessageContext`. An explicit `:options` binding overrides that context when the template
  intentionally owns the metadata. Each localized `<action>` selects a producer-supplied action by
  stable `id`, overlays only its label, and orders it for presentation. A `default` action is used
  when the notification itself is opened; at most one may be declared. Destinations and action data
  remain producer-supplied metadata. Besides delivery and grouping fields, optional
  `BmlPushRichContent` carries remote media attachments and a conversation preview. Attachments
  are public URLs downloaded by the provider or client; they are not inline bytes. Conversation
  entries should remain a small recent preview with stable channel/message identifiers—the app
  remains the source of truth for full history.
- Rich presentation is selected declaratively inside `<push>`:
  - `<image/>` promotes the first producer-supplied image attachment to the provider-native image.
  - `<attachments/>` retains the producer-supplied image, video, and audio attachment envelope.
  - `<conversation/>` retains the bounded conversation preview for capable clients.
  These tags are empty and accept no attributes. They never construct URLs, messages, or history;
  the producer supplies that data, while the template decides which presentation concepts it uses.
- Inside shared server code the message context is in scope as **`message`** (the render context
  stays `ctx`): `message.payload(MyPayload.serializer())` decodes the typed payload, and standard
  fields (`message.recipientName`, `message.locale`, …) drive optional clauses.
- Every compiled message is listed in `bml.generated.BmlMessages` (`BmlMessageModule`).
- No routes, no islands, no `<script client>` — communication units have no JS by construction.

**`bml-inline` (email inlining).** Two tags accept a `bml-inline` marker in email renders:

- `<style bml-inline>…</style>` — instead of emitting a `<style>` tag (which many email
  clients strip), the CSS is applied as **inline `style` attributes** on matching elements
  after the body renders. Works through components (they render into the same context).
  Simple selectors inline (`tag`, `.class`, `#id`, `tag.class`; an element's own `style`
  attribute wins on conflict); at-rules (`@media`, `@font-face`, `@import`) cannot be
  inlined and are retained as a `<style>` block in `<head>` for clients that honor one.
  On a non-email render the tag falls back to a normal `<style>`.
- `<img bml-inline src="logo.png"/>` — **embeds the bundled image in the email** instead of
  referencing it remotely: the HTML gets `src="cid:…"` and the mailer attaches the asset as
  an inline `multipart/related` part, so it displays even before the client loads remote
  images. The `src` must be a static, bundle-relative `public/` path (not a URL, not an
  `{ email.assetsUrl }` interpolation — drop the prefix to inline). Only images that
  actually render attach: one inside a false `<if>` adds nothing.

### 4.2 Slots and reserved template syntax
```
Template      ::= '<template' WS 'name' '=' '"' Name '"' Attr* '>' Node* '</template>'
Slot          ::= '<slot' ( WS 'name' '=' '"' Name '"' )? ( '/>' | '>' Node* '</slot>' )
```
- `<slot/>` renders the children passed to a component. Slot fallback bodies are not currently used.
- A named `<slot name="x"/>` currently warns and renders the same default component children.
- `<template>`, `layout=`, named slot provision, and explicit `<use>` calls are reserved syntax. The
  compiler does not generate template renderers; pages own their complete document skeleton.

### 4.3 `<component>` and `<prop>`
```
Component     ::= '<component' WS 'tag' '=' '"' Name '"' ( WS 'scope' '=' '"' ComponentScope '"' )? Attr* '>' ComponentBody '</component>'
ComponentScope ::= 'page' | 'site'
ComponentBody ::= ( Prop | CodeRegion | Comment )* Node*
Prop          ::= '<prop' WS 'name' '=' '"' Name '"' PropAttr* ( '/>' | '>' Node* '</prop>' )
PropAttr      ::= 'type' '=' '"' TypeRef '"' | 'required' ( '=' AttrValue )?
                | 'default' '=' AttrValue | ':default' '=' '"' Expr '"'
```
- `<component tag="card">` registers a tag `card` (or **overrides** a built-in,
  e.g. `tag="a"`, unless protected). Tag names need not contain a hyphen.
- Component state ownership defaults to `scope="page"`. `scope="site"` gives its live models a
  stable cross-page identity; those models must use `client-session`, `client-local`, or
  `server-session` storage and may omit a view when a page only needs a headless action. A site
  component is a singleton state owner and therefore does not use an island `:key`.
- `<prop>` declares a typed input; props are in-scope `val`s in the component
  body and its `<script server>`. The compiler checks every value passed to a declared prop:
  - Static text is converted to a `Boolean`, `Int`, `Long`, `Short`, `Byte`, `Double`, `Float`, `Char`,
    or `Number` prop at compile time (`count="3"` passes `3`). For those scalar types, text that is not
    a valid literal, text mixed with an interpolation, and a bare attribute on a non-`Boolean` prop are
    compile errors; bind those with `:name="…"`. For any other type (an application class, alias, or
    interface) the static value is passed as written, text or `true`, and checked by the Kotlin
    compiler, so `href="/docs"` works for `typealias Url = String` while `row="primary"` for a class
    fails to compile.
  - Bound expressions and interpolation-only values are checked against the declared type by the Kotlin
    compiler. A prop with a default also accepts `null`, which selects the default. For a component
    declared in another file, the check applies when the type resolves without imports (Kotlin's
    built-in types or package-qualified names); short names that rely on that file's imports are
    checked at render time as before.
  - A literal `default="…"` is taken as text for `String`/`CharSequence` props and converted for
    numeric and `Boolean` props (`default="1.5"` on a `Float` is `1.5f`); any other default is Kotlin
    source, as are defaults that are not valid literals (`default="isAdmin"`). Use `:default` for an
    explicit expression.
  `<prop>` body, if present, is fallback content
  for a slot-typed prop. Unknown attributes passed to the component that are not
  declared props are collected and available via the implicit `attrs` binding.

### 4.4 Control flow
```
Control       ::= For | If
For           ::= '<for' WS ForBinding WS 'in' WS Expr '>' Node* '</for>'
ForBinding    ::= Name | '(' Name ',' Name ')'      « (index,item) for lists; (key,value) for maps »
If            ::= '<if' WS ( Expr | FeatureFlagCondition ) '>' Node* ElseIfClause* ElseClause? '</if>'
ElseIfClause  ::= '<else-if' WS ( Expr | FeatureFlagCondition ) '>' Node*
ElseClause    ::= '<else>' Node*
FeatureFlagCondition ::= 'flag' '=' QuotedLiteral
                         ( WS 'variation' '=' QuotedLiteral | WS ':when' '=' QuotedExpr )?
```
- `<for item in expr>` iterates an `Iterable`/`Sequence`/`Map`. `(i, item)`
  yields the zero-based index (or map key) alongside.
- `<if expr> … </if>` may contain `<else-if expr>` clauses and a final `<else>` as
  **bare in-block separators** (no own close tag); the whole conditional is closed
  by a single `</if>`. Nested `<if>` blocks are fully self-contained.
- A condition containing a bare `>`/`<` must be parenthesized (e.g. `<if (a > b)>`);
  `>=`, `<=`, and `->` are recognized directly.
- A feature flag can be used as a boolean (`<if flag="new-checkout">`), matched by variation
  (`<if flag="checkout-layout" variation="compact">`), or evaluated with arbitrary Kotlin
  (`<if flag="checkout" :when='flag.variationKey == "compact" && !flag.degraded'>`). Inside
  `:when`, `flag` is the complete `FeatureFlagEvaluation` with `value`, `variationKey`,
  `experimentId`, and `degraded`. `variation` and `:when` are mutually exclusive. Flag and
  variation keys are static non-empty literals so the compiler can diagnose authoring mistakes.
  Unavailable evaluations are logged and fail closed to a degraded, unknown value rather than
  aborting page rendering; boolean and variation forms therefore select their fallback branch.
  The same forms are valid on `<else-if>`. Feature-flag evaluation is not available in `<message>`
  renders because no recipient feature identity is currently defined; the compiler rejects it in
  conditions, server scripts, interpolations, and attribute expressions.

### 4.5 `<data>` and `<inject>`
```
Data          ::= '<data' WS 'provides' '=' '"' Name '"' Attr* '>' Interp '</data>'
Inject        ::= '<inject' ( WS 'server' )? WS 'name' '=' '"' Name '"' WS 'type' '=' '"' KotlinType '"'
                  ( WS 'provider' '=' '"' String '"' )? ( WS 'init' '=' '"' Name '"' )?
                  ( WS 'scope' '=' '"' LiveStateScope '"' )? ( WS 'clear-on-sign-out' )? '/>'
LiveStateScope ::= 'page' | 'client-session' | 'client-local' | 'server-session'
```
- Declarative sugar for a `<script server provides="name">` whose body is the
  single expression. Example: `<data provides="listView">{ bosca.query(GetListView(id = id)).list }</data>`.
- `<inject>` is a non-rendering direct-child declaration on a page, route, component, message, or
  template. It resolves `type` through Bosca DI's `provide<T>()` and binds it as `name` throughout
  that render unit. `provider="key"` selects a named DI binding. `init="method"` calls the injected
  value's zero-argument method after resolution and may target a suspend method. For example,
  `<inject name="search" type="com.example.SearchService" init="load"/>` emits a `search` binding
  followed by `search.load()`. Injections run before server-script initializers, do not leak into
  callers or sibling units, and component injections run as part of each component render.
- A bare `server` marker promotes an injected `@Serializable` view model to the same live-state path
  as a constructor-typed `<script server provides="…">`. When an `@event` targets the binding and one
  `<island>` references it, BML serializes the model, decodes it for action dispatch, invokes the method
  on the server, and re-renders the island. `scope` accepts `page` (default), `client-session`,
  `client-local`, or `server-session`; it is only valid with `server`. The bare
  `clear-on-sign-out` marker is valid only with `client-session` or `client-local` and opts that
  model into `clearBmlClientState()` disposal.

### 4.6 `<island>`
```
Island        ::= '<island' IslandAttr* '>' Node* ScriptClient? Node* '</island>'
IslandAttr    ::= 'name' '=' '"' Name '"' | 'client' '=' '"' 'ts' '"'
                | 'hydrate' '=' '"' HydrateMode '"' | 'render' '=' '"' IslandRenderMode '"' | Attr
IslandRenderMode ::= 'request' | 'prerender' | 'deferred'    « default: request »
HydrateMode   ::= 'load' | 'idle' | 'visible'      « default: load »
```
- An island renders its markup server-side (SEO-complete) and, when `client="ts"`,
  mounts the inner `<script client>` module onto the SSR DOM (hydration-lite).
- The current browser runtime mounts all client islands when it loads. `idle` and `visible` remain
  reserved mount-policy values. Islands degrade to their SSR HTML on the email/plain-text targets
  (no client module emitted).
- **Props**: any attribute other than reserved `name`/`client`/`hydrate`/`render` is an
  island prop — `:id="id"` (dynamic) or `flag="on"` (static). Props are serialized
  to the client module and read as `ctx.props.<name>`.
- **Instance isolation** (reuse-safe): every island occurrence is an isolated
  instance. `defineIsland(name, (ctx) => …)` receives that instance's root as `ctx.root`; DOM access
  stays scoped to that root (prefer classes / `data-*` over global `id`s). The compiler
  rewrites any `id` inside an island to be **instance-unique** so the emitted HTML
  is valid with multiple instances. The runtime supplies `ctx.id` and
  `ctx.scoped(name)` to namespace otherwise-global keys (e.g. SortableJS
  `group`). The islands guide documents the full runtime API.
- **Eager render**: `request` (default) renders the island with the page request. `prerender` is
  accepted as a reserved mode and currently follows the same request-rendered path.
- **Deferred render**: `deferred` emits only the boundary and one optional direct `<fallback>`
  during the page render. Direct `<prop>` declarations define the typed JSON-shaped inputs copied
  from matching island attributes. The browser posts those inputs plus the current path, query, and
  locale to the compiler-registered renderer; it resolves current request identity and returns a
  private, `no-store` HTML fragment. The wire encoding preserves nested Kotlin numeric, character,
  list, map, and primitive-array types; Kotlin `Long` values become JavaScript `bigint` values in
  client scripts so they remain exact. Enclosing render locals do not cross the request boundary.
  Server loaders and live models declared directly in the deferred scope run on the deferred request.
  Fallback content is static server-rendered markup; components used there must also be free of client
  scripts and declarative actions. Client scripts and refs belong in the deferred body.

---

## 5. Code-region scope & data flow

- **`<script server provides="name">`** — Kotlin. The value bound to `name` is the
  value of the block's **last expression** (Kotlin block semantics). Without
  `provides`, the block contributes imports/declarations but binds nothing.
  Multiple server blocks compose top-to-bottom; later blocks see earlier bindings,
  route params, and props.
- A constructor-typed `provides` or explicitly typed `<inject server>` used by a live action has
  exactly one state `scope`:
  `page` (the default) round-trips the model only for the current rendered page, so a fresh SSR
  render wins after navigation or refresh; `client-session` stores the serialized model in browser
  `sessionStorage`, where it remains authoritative until that browser session ends; `client-local`
  stores it in browser `localStorage`, where it survives tab closure and browser restarts; and
  `server-session` stores the model in the cookie-identified BML server session and never sends its
  value to the client. Client storage is readable by same-origin JavaScript and is not for secrets.
  A browser-backed live model may add the bare `clear-on-sign-out` marker when its state belongs to
  the authenticated identity. `clearBmlClientState()` clears marked browser state, preserves unmarked
  browser state, replaces the opaque server session, and reloads by default so the server recomputes
  shell markup, deferred props, and fallback content. Use `{ reload: false }` only when all shell
  content is identity-independent.
- **Generated GraphQL operations** are in scope as Kotlin symbols inside
  `<script server>` (no manual import). BML also enables the GraphQL plugin's TypeScript target;
  client modules import its typed function from `./graphql/OpName`.
- **Feature flags** use `RenderContext.featureFlags`. It provides `evaluate`,
  `evaluateAll`, `enabled`, `variation`, `string`, `number`, and raw `value` helpers; the
  feature-aware `<if>` form lowers to this same API. Evaluations are cached within a render.
- **`<contract>`** — Kotlin interface(s) defining typed client↔server RPC; the
  compiler emits a server dispatcher and a typed TS stub. Optional.
- Scope is lexical: page and component bodies see their own props, route params, `provides` bindings,
  and direct-child `<inject>` declarations.

---

## 6. Expression sublanguage (`Expr`)

`Expr` is a **typed subset of Kotlin expressions** that lowers verbatim into the
generated `.kt`. v1 admits:
- literals (number, string `"…"`/`"""…"""`, char, boolean, `null`);
- identifiers and member access `a.b.c`;
- calls `f(x, y = z)`, including suspend calls (rendering is in a suspend context);
- index `a[i]`; safe-call `a?.b`; elvis `a ?: b`; not-null is discouraged;
- operators `+ - * / %`, comparisons, `&& || !`, `in`, `is`, ranges `a..b`;
- `if (…) … else …` expressions and `when` expressions;
- string templates inside string literals (`"${'$'}{x}"`);
- lambdas `{ it.name }` for higher-order calls.

Excluded in v1 (use a `<script server>` block instead): statements, loops,
local `fun`/`class` declarations, assignments. Diagnostics reject these with a
`.bml`-anchored message.

---

## 7. Tag resolution (summary)

At compile time each element name resolves through the tag registry:
1. project/file **custom or override** component (`<component tag="…">`),
2. built-in **special** tag (§4) — always wins for protected names,
3. built-in **HTML or SVG** tag (SVG vocabulary is native inside `<svg>`),
4. otherwise a compile error (`.bml`-anchored).

**Protected** (never overrideable): `page, template, component, prop, slot, use,
for, if, else-if, else, data, inject, island, script, contract, style`. All other tags
(including `a`, `img`, `form`, …) are **overrideable** by a `<component tag="…">`.

**Native escape hatch:** the `html:` and `svg:` namespaces always resolve to their built-in
handlers, bypassing overrides. This is how an override references the tag it overrides without
infinite recursion, e.g. inside `<component tag="a">` emit `<html:a …>` for the real anchor.

---

## 8. Output escaping & security

- `Interp` (`{ … }`) HTML-escapes its string output. `InterpRaw` (`{@ … }`)
  emits unescaped HTML and is the explicit, auditable trusted-HTML path.
- Attribute interpolations are attribute-escaped; `:href`/`href` values are
  URL-context aware (renderer responsibility).
- The email/plain-text targets apply their own escaping/normalization.

---

## 8.1 Localization (`t` / `t:`)

All localization syntax lives in the `t`/`t:` vocabulary; nothing else in the language is
reserved for it. The vocabulary carries exactly two non-inferable facts: **`t` names the
catalog key, `t:count` binds the number that selects a plural form.** Plural-ness is implied,
never declared. Strings come from the site's bound **Bosca localization project**, which also
defines the site's locales (source language = default); requests negotiate `?lang` →
`bml_locale` cookie → `Accept-Language` (full RFC 4647).

**Element text** — pin a key; the authored children are the source message AND the guaranteed
runtime fallback (keys never render from this path):

```html
<h1 t="welcome.back">Welcome back, { user.firstName }!</h1>
```

The compiler folds the children into one message template (`Welcome back, {firstName}!`):
interpolations become named placeholders (trailing identifier of a simple dotted path;
positional otherwise; identical expressions share a name), entities decode at compile time,
whitespace runs collapse. Children may contain only text and `{ }` interpolations — nested
elements, `{@ }`, and flow tags are compile errors (restructure or use `t()`). One key, one
message: redeclaring a key with different source text is a compile error, in-file or
cross-file.

**Plurals** — `t:count="<expr>"` binds the count; `<t:zero|one|two|few|many|other>` children
carry the authored CLDR forms (`<t:other>` required; an interpolation matching the count
expression folds as `{count}`):

```html
<span t="cart.items" t:count="items.size">
  <t:one>You have one item</t:one>
  <t:other>You have { items.size } items</t:other>
</span>
```

Selection walks: catalog row for the locale's category → the locale's OTHER row → the
fallback chain → the authored forms (source-language rules). Shorthand: `t:count` with plain
children makes the authored text the OTHER form.

**Attribute text** — `t:<attr>="key"` pairs a key with the authored attribute value as source
and fallback: `<input t:placeholder="search.hint" placeholder="Search here">`. Every
`t:<name>` other than `t:count` requires a sibling `<name>` attribute with plain-text value.

**The `t()` function** — the computed-string tier (server scripts, island props, dynamic
keys, email subjects): `t("key")`, `t("key", "name" to v)`, `t("key", count)`. These forms
carry no authored fallback, so a missing key renders the key itself (warned once). For
strings markup can't carry (email subjects, component prop values) prefer the
**authored-default form** `t("key", "Welcome to {appName}", "appName" to m.appName)` — a
missing key formats the authored default instead of rendering the key, and the manifest
scanner harvests that default as the key's source message (so `i18n push` seeds it). A
second string literal followed by `to` is a placeholder pair, not a default. Locale-aware
formatting helpers (`formatNumber/Percent/Currency/Date/DateTime`) are ambient alongside it.

**Escaping**: localized output always renders through the escaped text path — translations
are data, not markup ({@ } is rejected inside `t` elements). `t`/`t:*` attributes and
`<t:category>` tags are compile-time only and never reach the output HTML.

**Authoring loop**: the compiler writes `bml/i18n-manifest.json` into the resources output;
`bosca bml i18n extract|push|status` feeds those keys into the localization project (create +
source-seed only, never deletes). Translators work entirely in the localization workflow;
BML renders `PUBLISHED` translations.

---

## 9. Source-position model

Every token and AST node carries a `Span`:
```
Span ::= { startOffset: Int, endOffset: Int, startLine: Int, startCol: Int, endLine: Int, endCol: Int }
```
- Offsets are byte offsets into the original UTF-8 file.
- Raw-text regions additionally record the content span (excluding the tags) so
  the compiler can emit a source map / line directives from generated `.kt`/`.ts`
  back to `.bml` for diagnostics and **breakpoints**.
- Every diagnostic references a `Span`.

---

## 10. Deferred work

- Exact whitespace-collapsing rules per target.
- Robust raw-text termination for embedded `</script>` in string literals.
- Template/layout code generation, named slots, and `<use name="…"/>` invocation.
- Typed query params on `<page>` and `<route>` routes.
- Streaming/suspense boundaries for slow `<data>`/`<script server>` regions.
