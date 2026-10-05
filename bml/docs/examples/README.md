# BML examples

Representative source files for the [current grammar](../grammar.md). These are hand-authored
language, renderer, and integration examples.

The files are meant to live in one project. `lists.bml` uses `list-item.bml` and the generated
client for its `ListOps` contract; `list-ops.kt` shows the matching server implementation and
dispatcher wiring. `task-list.bml` pairs with `task-list-view-model.kt` and reuses the same list row.
Place the BML files under `src/main/bml` and the Kotlin files under `src/main/kotlin/example`.

| File | Kind | Notable constructs exercised |
| --- | --- | --- |
| `list-item.bml` | component | a reusable list row with typed props, scoped CSS, bound and interpolated attributes, `<if>`, and a default `<slot/>` for row actions |
| `lists.bml` | page | a complete HTML document, `route` + typed param `{id:UUID}`, `<script server provides>`, a `<contract>` called through its generated `ListOps` TypeScript client, the `list-item` component, and an isolated Sortable island |
| `list-ops.kt` | Kotlin contract implementation | implements the generated `ListOps` server interface with the request's `GraphQLClient` and wires `ListOpsDispatcher` into `BmlServer` |
| `task-list.bml` | page | a no-client-code live ViewModel with `scope="server-session"`, declarative `@submit`/`@click` actions, `form.title`, island re-rendering, and slotted `list-item` actions |
| `task-list-view-model.kt` | Kotlin ViewModel | the `@Serializable`, mutable server model used by `task-list.bml` |
| `card.bml` | component | `<component tag>`, `<prop>` (`required`, expr `:default="null"`, attr `default="1"`), component-scoped `<script server>`, attribute **spread** `{...attrs}`, `<if>/<else>`, `<slot/>`, bound attr `:href="…"` |
| `anchor.bml` | component | **overriding** a built-in (`tag="a"`), the **`html:` native escape hatch** (`<html:a>`) to avoid recursion, `<prop>`, `<script server provides>`, bound attrs `:href="…"`/`:rel="…"` |
| `site-header.bml` | component | reusable `<component>`, typed prop, native HTML, default `<slot/>`, and HTML entity `&copy;` |
| `welcome-email.bml` | message (email channel) | typed message payload, localized `<subject>`, `<style bml-inline>`, table-safe HTML, **raw output** `{@ … }`, `<if>/<else-if>/<else>`, and `<for>` |

Construct coverage (grammar §): elements/void/self-closing, `QName`/`html:` (§3.1, §7);
static / bound (`:attr="expr"`) / spread attributes (§3.2); `<script server>`/`<script client>`/
`<contract>`/`<style>` raw regions (§3.3); `<page>` (§4.1); component `<slot>` (§4.2);
`<component>`/`<prop>` (§4.3); `<for>`/`<if>`/`<else-if>`/`<else>` (§4.4); `<data>` (§4.5);
`<island>` (§4.6); `provides` scope + route params + props (§5); live ViewModels and declarative
server actions; expressions and `{@ }` raw output (§6, §8); comments `{# #}` (§2.2).

Deferred islands and declarative server actions are covered in the [islands reference](../islands.md).
Features still reserved for future work are listed in grammar §10.
