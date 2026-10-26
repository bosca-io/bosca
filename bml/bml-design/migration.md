# Migrating Git UI components

The Design Library contains the 41 presentation components extracted from Git UI. Declare the
dependency and one unaliased root import as described in the [library README](README.md). Keep
the application's document, state, forms, actions, destinations, permissions and transport there.
Nested pages and components use the same short tags.

Replace component calls and remove their old local definitions together. Local definitions take
precedence over imported exports, so a successful build with a remaining copy does not establish
that the library is being used. The extraction does not require forwarding components or an alias.

## Tag mapping

Props and projected children retain their roles, with the caller adjustments below. The
[component reference](README.md#components) lists the current props, defaults and attribute targets.

| Git UI tag | Library tag | Caller adjustment |
| --- | --- | --- |
| `ui-alert` | `alert` | Keep tone, hidden and compact state. |
| `ui-app-header` | `app-header` | Supply the brand, product, destination and projected navigation. |
| `ui-badge` | `badge` | Set `--purple` in consumer CSS to customize the purple tone. |
| `ui-bosca-mark` | `bosca-mark` | The SVG paths are preserved; colors use the `--mark-*` variables. |
| `ui-button` | `button` | Keep native type, disabled/loading state, classes and declarative events. |
| `ui-card` | `card` | Keep title, padding and projected content. |
| `ui-checkbox` | `checkbox` | Keep native form attributes and change events. |
| `ui-comment-editor` | `comment-editor` | Native attributes target the textarea. `name` defaults to `body`; `describedBy` adds external descriptions. |
| `ui-confirm-dialog` | `confirm-dialog` | Keep application-owned confirmation actions in projected content. |
| `ui-copy` | `copy` | Keep the exact value and label; `copyresult` reports clipboard success or failure. |
| `ui-copy-field` | `copy-field` | Keep the value, field label and copy label. |
| `ui-dialog` | `dialog` | Keep request/completion state and projected forms. Use native escapes for native dialog elements. |
| `ui-disclosure-heading` | `disclosure-heading` | Keep it inside the caller's native `details` element. |
| `ui-empty-state` | `empty-state` | Keep title, message, icon and projected actions. |
| `ui-field` | `field` | Keep the label, hint and projected native control. |
| `ui-icon` | `icon` | Glyphs, size and stroke-width props are preserved. |
| `ui-icon-button` | `icon-button` | Keep the accessible label, native button attributes and click action. |
| `ui-icon-link` | `icon-link` | Keep the accessible label, destination and native link attributes. |
| `ui-input` | `input` | Keep native form attributes, controlled value and declarative events. |
| `ui-json-editor` | `json-editor` | Keep the field name/value; optionally supply label, mode, readonly/disabled state, error and preservation key. |
| `ui-loading-state` | `loading-state` | Keep label, compact state and additional native attributes. |
| `ui-page-heading` | `page-heading` | Use the library `NavigationItem` and explicitly supply any product breadcrumb. |
| `ui-page` | `page-shell` | Use `page-shell` for the visual main container; `page` remains BML's route declaration. |
| `ui-panel` | `panel` | Keep projected content; native attributes target its section. |
| `ui-popover` | `popover` | Keep label, icon, width and projected content; the library owns disclosure positioning and dismissal. |
| `ui-search-input` | `search-input` | Keep value, name and input events. Autofocus remains enabled by default; pass `:autofocus="false"` when needed. |
| `ui-select` | `select` | Keep native options and change events. Mark persistent async choices explicitly and use the public search types. |
| `ui-section` | `section` | Keep projected content; use `html:section` for an unstyled native section. |
| `ui-section-heading` | `section-heading` | Keep title, subtitle and projected actions. |
| `ui-segmented-control` | `segmented-control` | Use `List<SegmentOption>` with stable values, labels and optional icons/disabled state. Keep the native hidden form value and bubbling `segmentchange` event. |
| `ui-source-view` | `source-view` | Pass requested lines explicitly; Git's editable and preview workflows remain in Git. |
| `ui-spinner` | `spinner` | Keep size; the spinner remains decorative. |
| `ui-switch` | `switch` | Keep label, checked/disabled state and native form/change behavior. |
| `ui-table-scroll` | `table-scroll` | Keep the projected native table; optionally provide a contextual scroll-region label. |
| `ui-tabs` | `tabs` | Use the library `NavigationItem`; destinations remain native links. |
| `ui-time` | `time` | Formatting props and server formatting are preserved; use `html:time` for native markup. |
| `ui-toast` | `toast` | Keep a stable key and increment the notice sequence for each new message. |
| `ui-tree` | `tree` | Keep local/server expansion ownership; optionally provide a preservation key. |
| `ui-tree-item` | `tree-item` | Keep paths, labels, depth and native destinations/actions. |
| `ui-tooltip` | `tooltip` | Keep content, placement, delay and the projected trigger. |
| `ui-view-tabs` | `view-tabs` | Use `List<ViewTab>` with values independent of display labels. Keep caller-owned selected state; `viewchange` carries the stable value. |

Audit intentional native elements when enabling the import. `button`, `input`, `select`, `section`,
`time` and `dialog` also name library components. Use `html:` for their native forms, including
matching closing tags. Props, merged classes and additional attributes use BML's `attrs` support;
components no longer need server scripts that filter named props out of an attribute map.

## Navigation and search types

Replace imports of Git's former navigation model with `bosca.design.model.NavigationItem`.
It still carries `label`, `href` and `selected`. `page-heading` renders the supplied trail and current
crumb; Git explicitly adds its own product entry. Other products supply their own entries.

Async select transport stays in the application. Import the linked declarations with:

```ts
import type { SelectOption, SelectPage, SelectSearch } from "@bosca/bml-design/select";
```

A bubbling `selectsearch` event supplies `query`, `offset`, `resolve` and `reject`. Resolve an option
array or a `SelectPage` with explicit previous/next offsets, and report failed requests through
`reject`. Keep server contracts, authentication and response mapping in the application adapter.
Choices that must remain available across result pages use `data-persistent` on their native option:

```html
<select name="owner" label="Owner" searchable async>
  <option value="any" data-persistent>Any owner</option>
</select>
```

## Styles, spacing and assets

Move reusable presentation with its component. The library includes component styles, native control
sizing, pending button presentation, tooltip surfaces, projected table rules, modal form classes,
dynamic select choices and editor styles. The shared rules for projected table/modal/select content
stay under the owning library marker. Application document layout, auth screens, grids, diff/log
presentation and route composition remain application CSS.

Use the [public CSS variables](css-tokens.md) on the document or a themed container. Surface/text
colors, accents, feedback colors, fonts and radii inherit through the components. `--control-border`
can give neutral controls a stronger boundary while `--line` and `--line-2` retain subtle surface
dividers. Control background/radius overrides are optional and otherwise follow the local surface
and radius variables. Shared `--control-height-md`, `--control-height-sm`, `--control-height-xs` and
`--control-gap` defaults preserve 32/30/24px controls and 6px content spacing. Application layout gaps
and margins remain explicit application CSS.

The library declares the original Geist and Geist Mono faces, packages their WOFF2 subsets and
retains the font and dependency notices. Select the document font in application CSS. Remove copied
fonts, old private font routes and extracted global rules once their callers use the library.
Do not target generated scope IDs to restyle a component.

## Browser behavior and application adapters

The compiled dependency brings its reachable client helpers. Remove the old extracted helpers
after migrating their callers; do not import private paths from the library source checkout.
Keep domain adapters such as Git reference search, search URL state, discussion actions, editable
CodeMirror state, previews and diff workspace behavior in Git.

`source-view` owns the read-only CodeMirror viewer. It infers language from `path`, accepts an
explicit `language`, and takes a positive requested `line` from its caller. It does not inspect Git
URL parameters. `json-editor` owns the structured engine and native form value; applications retain
validation and save actions. Basic pages do not reach either editor's implementation assets through
the module-wide import alone.

Give preserved controls distinct keys for independent instances, and change the key when the
application intends to replace local editor state. A toast's initial sequence is a baseline;
retained server text does not announce again after navigation or remount. Increase its sequence for
every new notice, including repeated text. Copy controls retain native labels and manual-copy
recovery when clipboard access fails. Forms, links, native select values and escaped editor content
provide their documented script-disabled behavior.

The [isolated consumer](../examples/design-library/README.md) exercises these contracts in
production and development, including two consumer themes, native fallback, declarative actions,
retained state and component disposal. Git's route and browser checks cover its application adapters
and the 1920/1440/1024/390px layout matrix.

## Fill and full-screen workspaces

Use `workspace` and `fullscreen-button` from the unaliased library import. `page-shell fill`
provides the bounded page column; `workspace-content`, `workspace-fixed` and `workspace-scroll`
are public projected-content classes. `source-view fill` uses the available height instead of
snippet or file-card caps. Git's file pages now consume these primitives and no longer ship a
local expansion helper or full-screen geometry. File actions, editable CodeMirror, Markdown
rendering, drafts and commits remain Git-owned. Put the workspace outside a changing action
island when its expansion should survive updates to the form. See the library README for a
complete authoring example and CSS tokens.

## Shared application shell and profile menu

Compose `app-shell`, the existing `app-header`, `shell-navigation` and `shell-main` instead of
copying Git shell styles. Use `profile` for identity display and `profile-menu` for projected account
actions. Git retains its auth SDK, identity loading, route links and appearance cookie. Generic
context-menu activation and all profile presentation belong to the library. Palette values and
opaque editor/overlay colors remain consumer CSS; `--shell-background` selects the gradient base.

## Complex input values

Pass Kotlin objects to complex controls. `chart.config` is `ChartConfiguration`, `flow-editor.value`
is `FlowGraph`, and `dashboard-grid.value` is `List<DashboardLayoutItem>`; `lockedIds` is `Set<String>`.
Their model types are in `bosca.design.model`. Ordinary models use generated Kotlin serializers
and explicit properties; application node values belong in `FlowNodeData.payload`. Decode submitted values
with `Json.decodeFromString(FlowGraph.serializer(), form.graph)` or
`Json.decodeFromString(ListSerializer(DashboardLayoutItem.serializer()), form.layout)`; use the same serializers for
encoding and application state. `document-editor.value` is a ProseMirror-shaped
`JsonObject`; `json-editor.value` is `JsonElement`. Construct sample values in Kotlin with model
constructors or JSON builders, and bind them with `:value` / `:config`. The components encode their
browser data internally. Native submitted form fields remain JSON text and are decoded by the
receiving application action. JSON editor invalid/empty drafts use its separate nullable `text`
prop; structured values do not use serialized string literals.

Common chart options now use `ChartOptions` and nested Kotlin models; point collections use
`ChartValue` objects, with numeric collection constructors for ordinary series. Flow card data,
ports, sides, edge styling and markers have Kotlin models. Simple presentation choices use string
attributes (for example `size="sm"`, `tone="success"` and `placement="bottom"`).
Use shared `bosca.documents` nodes/marks through `documentValue(document)` when authoring known
content. Preserve custom/submitted document JSON trees to retain unsupported nodes and attributes.

`document-editor` also accepts `:document="article.document"` for a `bosca.content.metadata.model.Document`. It renders only `content.document`; a missing body opens an empty paragraph. A supplied record takes precedence over `value`, which remains the input for open ProseMirror JSON.
