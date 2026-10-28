# Bosca Design Library catalog

An independent BML application consuming `:bml:bml-design` through one unaliased `imports.bml`.
It owns its documents, navigation, CSS palettes and local form state. It has no Git or backend-service
dependency, copied control implementations, manually registered library components or private product
asset routes. The workspace's aggregate publication task excludes this application.

Run from the workspace root:

```sh
./gradlew :bml:bml-design-catalog:run
```

Open `http://localhost:9093/`. Pass another port with `--args=9094` if needed.

## Deployment

The deployment artifact is a GraalVM native executable with its production JavaScript, shared
chunks, styles, fonts and application resources embedded. It runs without a JVM, Node, a Bosca API,
database, workspace checkout or external asset directory:

```sh
./gradlew :bml:bml-design-catalog:nativeCompile
bml/bml-design-catalog/build/native/nativeCompile/bml-design-catalog 9093
```

Native compilation targets the build host. The Linux/amd64 container image is built on a Linux
x86_64 runner, following the other native Bosca services. From that host, build and run locally:

```sh
PUSH=false BOSCA_IMAGE_REGISTRY=bosca scripts/release/build-image.sh bml-design-catalog local
docker run --rm -p 9093:9093 bosca/bml-design-catalog:local
```

The image defaults to production mode on port 9093; a port argument overrides 9093. The Dockerfile
uses a small runtime image and copies only the native executable. Its Dockerfile-specific ignore
rules keep the source tree and build dependencies out of the context.
`.bosca/pipelines/release-image-bml-design-catalog.yaml` builds and publishes `bml-design-catalog`
through the existing tagged image-release process.

`helm/bml-design-catalog` provides the standalone Deployment and ClusterIP Service. It is also an
optional `bosca-services` dependency, disabled by default. In the deployment's own values file,
enable the site and configure its public host:

```yaml
gateway:
  enabled: true
bml-design-catalog:
  enabled: true
  image:
    tag: <published-image-version>
  gateway:
    enabled: true
    hostnames: [design.example.com]
```

The services umbrella adds the HTTPS route, TLS listener and HTTP-to-HTTPS redirect. All page,
island-action and asset paths stay on that origin. Apply these values to the existing services
release and namespace using the deployment's normal Helm upgrade process. A standalone chart
installation exposes the Service; its external routing belongs to that deployment.

Examples keep their server-session state in memory. The chart defaults to one replica and Recreate
rollouts, and a restart resets demonstration state. Multiple replicas require a shared BML session
store. This site does not store product data or need backend credentials.

Run the native deployment regression from the workspace root:

```sh
./gradlew :bml:bml-design-catalog:nativeCompile --build-args=-Ob :bml:bml-design-catalog:nativeCatalogTest
```

`-Ob` is a quick validation build; omit it for release optimization. The test copies only the native
executable into an isolated directory and checks production JS/CSS, selected grammar and font
resources, chart/flow interactions and the catalog's form/editor/navigation actions. Image-builder
tests stub publishing commands; Helm tests verify ports, probes, image versions, naming overrides
and Gateway routing without a cluster rollout. Native heap flags can be passed through the chart's
`extraArgs`, for example `[-Xmx256m]`.

## Examples

| Route | Examples |
| --- | --- |
| `/` | Unprefixed import/authoring, buttons and feedback, links into the catalog |
| `/foundation` | Labelled form with server validation, pending/success/error feedback, native choices, button sizes, loading/disabled states, badges, empty content and a scrolling table |
| `/themes` | The same form in separate dark and consumer-light palettes, with independent state |
| `/comments` | Independent dark/light comment drafts, pending/rejected posts, native reset and controlled replacements, readonly/disabled/paused/long/escaped variants |
| `/overlays` | Independent modal forms, retained drafts, rejected confirmations, popover forms and menus, sizes and trigger variants |
| `/json` | Independent dark/light JSON drafts, retained engine state, pending/rejected saves, native reset, controlled options and tree/text/table/empty/invalid/readonly/disabled variants |
| `/flows` | Independent VueFlow graphs, named-port connections, native forms, retained viewports, read-only, rejection and reset |
| `/dashboards` | Movable/resizable widgets, locked layouts, keyboard handles, native forms and responsive stacking |
| `/charts` | Chart.js built-in types, CSS colors, responsive canvases, data tables and local selection toasts |
| `/components/visualization` | Dashboard, flow and chart API references and authoring |
| `/documents` | Independent TipTap documents, rich text formatting, native JSON form values, rejected saves, reset and readonly/disabled states |
| `/source/languages` | Kotlin, TypeScript, JavaScript and BML syntax examples |
| `/source` | Independent dark/light source viewers, requested lines, language/wrap changes, preserved state, content replacement and compact/bare/empty layouts |
| `/workspaces` | A fill-height document workspace with source/preview, full screen, internal scrolling and appearance inheritance |
| `/surfaces` | Panels, padded/unpadded/untitled cards, retained native disclosures, empty/loading/table views and long labels in two palettes |
| `/feedback` | Independent copy outcomes, tooltip placements, repeated toast tones, long notifications and native selectable values |
| `/navigation` | Native route destinations, retained overflow menus, projected actions, changing/current/long lists, clipping and header spacing in two palettes |
| `/trees` | Local file disclosure, server-loaded children, failed-load retry, retained scroll/focus, disabled folders and native links in two palettes |
| `/views` | Search queries, native category values and caller-owned tab panels, retained drafts, rejected searches, changing tab lists and long/empty/readonly/disabled variants |
| `/choices` | Local required/custom choices, multiple native values, tag limits, loading/disabled controls and controlled defaults in two palettes |
| `/selection` | Server-filtered, offset-paged picker in independent dark/light roots, with failed-search retry, loading/disabled controls, native reset and toast saves |
| `/components` | Linked index of all 51 exports and normal unprefixed authoring |
| `/components/controls` | Buttons, inputs, choices, search, segments, accessible local tabs, native selects and the paged picker's authoring source |
| `/components/layout` | Surfaces, headings, native disclosure, application header and route tabs |
| `/components/feedback` | Alerts, badges, empty/loading states, icons, mark, tooltips, copy controls, repeatable notifications and timestamps |
| `/components/overlays` | Native modal focus/return, a failed confirmation that retains its draft, and a popover |
| `/components/content` | Scrolling table, keyboard trees, validated comment composer, JSON save/rejection, readonly/disabled editors and a source viewer and TipTap documents |
| `/components/aliases` | A separate, optional aliased spelling beside the normal short name |

The appearance switch changes application CSS through the native checkbox state. A catalog-owned
cookie remembers the choice across navigation and reload; the server renders that saved state before
scripts load, and restored history pages reconcile it on return. Without scripts, the saved choice is
still rendered and the switch changes the current page. It does not change the library dependency. Palette sections retain their
own colors when the surrounding document changes appearance. The form's BML actions use local
session state and never call a product API. Native labels, required fields, choices and navigation
remain available without scripts; server form actions require BML's browser runtime.

Save, comment and JSON completion messages use the library toast, as do rejected confirmation actions.
Validation remains connected to its input or editor. The examples have no inline save counters or
success rows. Each new result increments its instance's notification sequence; unrelated updates
retain a dismissed notification's state. Returning to a page or reloading retains saved form values
without replaying its old notification; a new action can show the same text again. Leaving the page
closes the current notice, including cached Browser Back navigation. Toasts sit near the viewport's
bottom edge in this application.

The application stylesheet owns a dark palette with a blue accent and a light palette with neutral
surfaces/text and a green accent. Each palette sets `--control-border` independently of its surface
borders, keeping neutral control boundaries visible while dividers stay subtle. It defines content
stacks and section gaps explicitly, including space between button rows, notes and badges. The sidebar
allows the product name to stay on one line.

Every export has a rendered example, prop/type/default reference, native attribute/event and child
contract, keyboard notes, CSS hooks and an escaped authoring snippet. Local composition snippets
include their BML state wiring; the corresponding `Catalog*` models live in this application's Kotlin
sources. The shared [API](../bml-design/README.md) and [CSS token reference](../bml-design/css-tokens.md)
also live with the library. The `/selection` composition uses the public `SelectSearch`/`SelectPage`
types and BML's same-origin contract client. `CatalogChoiceSearch` filters deterministic local options
before applying three-item offset pages, returning explicit previous/next offsets. It has no product
API or GraphQL dependency. The complete state/accessibility matrix remains open.

Each picker retains its native form value when another result page is shown. Its state disclosure
can reject searches, disable the control or demonstrate loading; turning rejection off allows the same
query to succeed. Search feedback stays in the control, validation stays beside its field and saves use
toasts. The complete option list remains available in the native select without scripts. The select
reference includes the actual composition source and a link to the working page.

The `/choices` page uses local options with a native required component field, an optional custom
label and a separate multiple-value tag form. Component saves and rejected operations use toasts;
the multiple example demonstrates repeated native values without a save action. Successful component
saves change its key and custom-label key so native reset restores the new defaults. Tags keep their
own preserved native state. Replace defaults changes all three keys; ordinary refresh retains their
DOM, selection, query/caret/focus and popup state. Controls can change required/custom/disabled/loading
state and tag limits. Static cases cover empty, compact, loading, disabled, long labels and tag limits.
Both local and server-paged compositions are shown in the select authoring reference.

The `/surfaces` page composes panels, section headings, section dividers and native disclosures
with local empty, loading and table views. Loading and rejected operations use local example data;
completion messages use toasts. The application reserves a 280px data area so pending actions keep
their footprint, while the labelled native table region contains horizontal and vertical overflow.
A stable native disclosure retains its local note, and unchanged tables have stable preservation keys.
State controls demonstrate rejected loads, long labels and compact loading. Both palettes also show
padded, unpadded and untitled cards, a plain disclosure and a minimal empty state. The panel reference
includes the complete local composition. The catalog owns content gaps and projected-body gutters.

The `/feedback` page combines copy fields, quiet copy actions, four tooltip placements and all four
notification tones in independent palettes. The application handles `copyresult` and issues a toast;
manual-copy guidance remains beside a selectable readonly value if clipboard access is unavailable.
Stable keys retain controls across refreshes. Replacing values updates clipboard data and descriptions;
changing the field key installs the new native value. State controls demonstrate long scrollable
notifications and repeated mount/unmount. A clipped ancestor demonstrates top-layer tooltip placement.
The copy API includes the complete local composition. Browser checks stub clipboard outcomes inside
an isolated profile, preserving the system clipboard.

The `/navigation` page gives each palette a full-width route example with an optional narrow
container. The application supplies public `NavigationItem` values and revision keys. Refresh uses
a toast and retains the open menu, focused link and unchanged DOM; changing current flags, labels
or destinations changes the key. A projected refresh action keeps its pending footprint. Wide
containers can show every destination, while narrow containers retain the current link beside More.
Static cases cover empty/short/unselected lists, long current labels, clipping and header gutters.
Every link points to an actual catalog page or reference anchor. The tabs reference includes the
complete local composition. Component-index and example page introductions use the same typography.

The `/trees` examples supply local component links and a separately loaded folder in each palette.
The application owns paths, selected files, loading, rejection and toast sequences. Local trees keep
stable keys for disclosure, scroll and focus through surrounding updates; replacing files changes
that key. Right Arrow opens a closed directory without moving focus. Server-loaded directories
preserve their native buttons; another Right enters the first child after loading. Moving focus
elsewhere during loading leaves it there. Native disabled folder
buttons are skipped by roving navigation. Without scripts, every rendered file link retains its native
Tab stop. Static cases cover empty trees, unavailable directories, truncated names and wrapped/deep
labels. The tree and tree-item references include the complete composition source. Declarative tree
item actions bind to their row; native attributes and preservation keys target its button or link.

Both palettes also include a complete thirteen-level hierarchy in a bounded tree, with a wrapped
selected file and an unavailable sibling folder. The dedicated deep-tree browser check records the
actual browser version and accessibility roles, names, levels, selection and expanded states. It
exercises keyboard traversal, collapsed descendants, disabled folders, native destinations and
script-disabled links at 1440px and 390px. Full-rule tree accessibility reports retain axe's warnings
about levels above six. Browser metadata checks do not establish spoken screen-reader compatibility;
spoken assistive-technology qualification remains release work. Enhanced rows expose branch group
ownership and sibling metadata while retaining their server-rendered DOM positions.

The `/views` composition uses native query and category values to filter deterministic local
examples. The application owns accepted results, rejected drafts, selected views and uniquely linked
tab panels. Editing a query or category keeps previous results until the next accepted search.
Refresh retains query DOM, caret/focus and unsaved category choices. Controlled replacements change
supplied values; changing the tab list changes only its preservation key. Search outcomes use toasts.
Repeated controls disable autofocus. Static examples include readonly/disabled queries, empty and
unmatched groups and long scrolling labels. The search, segment and tab references include the
complete composition source. Keyboard choices reveal their selected option; segmented rows grow
when a visible scrollbar needs space. Search borders use the consumer's control-border color.
Panels change visibility immediately and retain their own DOM through the view action response;
the result-panel key changes with accepted search content. Delayed-response checks cover panel and
link focus, forward/reverse Tab traversal and focus left outside the example in both palettes and
viewport sizes. The local-only reference also makes plain-text panels keyboard reachable.

The `/documents` examples submit ProseMirror JSON through their own form actions. TipTap supplies
general rich text editing; saved/rejected outcomes use toasts, local drafts survive refreshes, and
controlled replacements install new content. Unsupported application-specific nodes report an error
and retain their original JSON. Both palettes use the same component.

The `/source` examples keep a stable preservation key for surrounding updates and change it for
content revisions. The application owns requested lines and server options; the compiled viewer owns
selection, scroll, search and local wrapping. Refresh feedback uses a toast. The source reference shows
the complete local composition with BML syntax colors, and the page includes short/medium/long compact snippets, compact
without a toolbar, wrapped markup and empty source. Neither source text nor viewer state is sent to
a product API.

The `/json` examples reuse the basic reference form with optional state controls. `CatalogJson`
owns those options and toast sequences, while `CatalogContent` supplies the same validation used by
the basic content examples. Rejected drafts and the last saved value remain separate. Readonly values
stay in form data; disabled values are excluded. Pending forms become readonly, and native reset
restores the latest server value. The page includes independent table, empty, malformed, readonly,
disabled and escaped-string cases. A small catalog-owned client helper retains each Example states
disclosure while its server-owned controls update; the picker and source examples use it too.

The `/comments` page reuses the reference comment composition with optional state controls in two
independent palettes. The application owns validation, simulated post rejection, availability and draft
revisions. Validation stays beside the field; rejected actions and successful posts use toasts. The
working examples clear a successful draft by changing its ID; the basic reference retains the posted
value. Stable IDs retain local edits through unrelated updates, while changed supplied values become
the latest native reset default. Readonly, disabled, paused submission, validation, long drafts and
escaped markup also have individual examples. No comment is published to a product.

The `/overlays` page demonstrates a validated name form, action rejection and retry, a retained
server-requested draft, rejected confirmations and independent popover forms and menus. Modal
validation stays beside its field; operation results use toasts. The retained draft uses a stable
preservation key and server-owned open/completion attributes. Its children stay browser-owned
through surrounding updates. A new open request reopens a dismissed draft. Projected modal forms
use the library's scrolling `modal-fields` body and separate `modal-footer`. The popover uses a
nested island for its local form within a preserved container. The native details open state stays
in the browser-owned children, and the helper restores its top-layer surface after reinsertion.
Rejection toasts remain inside the active modal so their dismiss controls are keyboard reachable.
The native fallback fits its container; the clipping example applies only to enhanced panels. The page also shows
all three dialog sizes, text/link/block/plain/ghost triggers and a disabled trigger.

The content route reaches source, structured JSON and document engines. Other component reference
routes load the source viewer for colored authoring snippets. The documents route reaches only TipTap. The source route reaches only
the standalone source viewer; the JSON route reaches only the structured editor. Ordinary control
pages exclude both engines. The optional alias declaration stays in its
own example page; normal pages use the single unaliased root import.

The cold-page resource regression disables the browser cache and inspects actual JavaScript and
CSS downloads on nine ordinary, editor and visualization routes in production and development.
It includes shared modules and selected language grammars, and rejects optional engines or styles
on pages that do not use them. Nested widgets count toward their containing page: the dashboard
examples include charts. Reports are written to `build/reports/browser/catalog-<mode>-resources.json`.

Build and verify:

```sh
./gradlew :bml:bml-design-catalog:build
./gradlew :bml:bml-design-catalog:browserTest
./gradlew :bml:bml-design-catalog:accessibilityTest
./gradlew :bml:bml-design-catalog:typecheckClient
```

The ordinary `test` task also includes the browser test. Tests launch separate production and
development JVMs using only built JARs, exercise HTTP assets and actions, and use an isolated headless
Chrome profile. Independent methods run in a bounded pool of four (`-Pbml.browser.workers=1` selects
serial execution). The full-document accessibility tests cover every route in both palettes and
runtime modes at desktop and phone widths, including editor, visualization, dashboard and tree
states. Each combination is audited once, with contrast evidence required for each group of routes.
Each child binds its own ephemeral port; readiness requires its successful bind log
and a catalog HTTP response. JVM fixture requests use HTTP/1.1, matching Chromium on the loopback
origin. Fixture checks also run both modes together beside occupied IPv4 and
IPv6 endpoints. They check navigation, rejected-draft retention, pending and saved form state,
independent instances, saved light/dark appearance, two palettes, narrow layouts, native fallbacks and
the 11 packaged Geist/Geist Mono assets. Notification regressions cover dismissed/expired/visible
toasts through native navigation and Browser Back, reload, retained form values, unrelated updates
after returning and new actions with repeated message text. The suite compares the catalog index and documented prop
names/types/default presence with the library manifest from its built JAR, then exercises all reference
routes at 1440px and 390px. It also checks modal focus/return and failed drafts, local tab/panel
relationships, segment values, repeated toast sequences, keyboard tree traversal, comment validation
and keyboard submission, JSON rejection/save, readonly source, alias identity and script-disabled
content fallbacks. Picker checks exercise real contract requests, page boundaries, minimum-query
suppression, disabled options, busy and empty states, failure/retry, stale responses, retained DOM/query
state, independent instances, loading/disabled form rules, native reset and validation, long labels,
short-screen popup fit and script-disabled options. Source checks cover snippet heights, bare/empty
layouts, read-only behavior, keyboard search, requested-line selection, retained root/focus/scroll/local wrapping through
a surrounding action, server option changes, content replacement, repeated hide/show, independent
instances, escaped markup, keyboard exit, syntax/gutter contrast, stack spacing and script-disabled
fallbacks at desktop/phone widths. JSON checks cover exact rejected drafts, immediate pending
submission, native readonly/disabled form rules, reset and controlled replacements, local/server mode
choices, independent instances, retained engine/focus, repeated disposal, native fallbacks, escaped
strings, 320px editor viewports, 16px action spacing and 24px stacked card gaps. Sampled themed JSON
text, toolbar and invalid-draft feedback contrast is measured at both widths. `build` also runs strict TypeScript checking of
generated catalog islands against the runtime and linked producer declarations.
Comment checks cover blank and composing keyboard input, exact invalid/rejected draft retention,
caret/focus preservation, pending locks, rejection/retry, fresh drafts after success, controlled values,
reset, native readonly/disabled form rules, independent instances, repeated removal/remount and
script-disabled fields. Desktop/phone geometry and themed text, field-error and placeholder contrast
are sampled as well. Comment placeholders use the shared muted-text token in consuming CSS.
Overlay checks exercise native modal names, Tab containment and focus return; disabled triggers;
field validation; held pending action geometry; exact rejected drafts and successful retry; keyboard
access to modal rejection toasts; server-issued requests and completion; retained draft DOM, focus
and caret; nested cancel/Escape; popup form submission and surrounding updates; menu navigation;
all sizes and trigger styles; repeated disposal and listener cleanup; desktop/phone bounds, scrolling
bodies, fixed footers, independent palettes, sampled text contrast and visible native fallbacks.
Local-choice checks cover native required validation and keyboard selection, disabled options and
groups, repeated multiple values, custom choices, tag removal and Backspace, retained query/selection,
held pending saves, rejection/retry, controlled reset defaults, live custom-choice availability,
loading/disabled form rules, repeated disposal, independent palettes, empty feedback, narrow long tags,
sampled text contrast and native listbox fallback geometry. Local filtering makes no search requests.
Search/view checks cover native form values, clear events, roving keyboard navigation, accepted
results and rejected drafts, pending geometry, retained query/caret/focus and category choices,
controlled values, tab-list revisions, caller-owned panel relationships, independent roots and
repeated listener disposal. Desktop/phone checks measure search and segmented heights, form/panel
spacing, internal horizontal scrolling, text/placeholder and search-border contrast. Script-disabled
checks retain native query/category values, initial results and the initial panel.
Tree checks cover labelled roles, depths and selected/current links, roving arrows/Home/End,
type-ahead and repeated letters, local disclosure and scroll/focus retention, independent roots,
rejected loading and retry, pending geometry/busy state, entry into newly loaded children, focus
continuity outside the tree, local key changes, native destinations and repeated disposal. Both
widths check 30px rows, 16px composition gaps, contained scroll, wrapped/deep names, palette contrast
and script-disabled keyboard-reachable links.
Route-navigation checks cover native links and current flags, unique overflow relationships,
keyboard and Tab dismissal, outside focus, retained popup/link focus through surrounding and
projected actions, pending geometry, focus following a destination into the visible row, list/key
changes, long labels, repeated listener/observer disposal and native overflow navigation. Desktop,
phone and short-screen checks measure row fit, 16px spacing, header gutters, popup bounds, clipped
ancestors and text contrast. Script-disabled checks retain all native scrolling links. Browser
checks also compare computed description and introduction typography on Components and Selection.
No authenticated product session is used. Runtime failures use the application's
console logging backend and are included in integration-test diagnostics.

Presentation checks measure the rendered overview/foundation examples in both appearances at
1440px and 390px: one-line branding, content/section gaps, document overflow, text contrast of at
least 4.5:1, and at least 3:1 for sampled input borders, switch thumbs and keyboard focus indicators.
They wait for CSS transitions to finish before measuring colors and include translucent badge fills.
These are targeted checks, not a complete accessibility audit. Results are saved as
`build/reports/browser/catalog-<mode>-presentation.json` beside the screenshots.

The ordinary test suite also runs axe-core against all catalog routes in packaged production and
development applications at 1440px and 390px, in both document appearances. Additional scans cover
open choices, navigation menus, dialogs, tooltips, error toasts, validation, pending/loaded tables and
example-state disclosures. Confirmed violations fail the test without disabling rules. Findings
requiring manual review remain in `catalog-<mode>-accessibility-1440.json` and
`catalog-<mode>-accessibility-390.json`. Each width runs in a separate browser process with the same
deadline; both appearances and runtime modes remain covered. A passing scan does not replace
screen-reader or complete manual accessibility acceptance. Each scan also records DOM and browser
accessibility-tree evidence for popup `aria-controls` references, full option names/listbox context
and symbolic tag-removal names. Those checks require unique component-owned targets, matching
exposed relationships and selected/disabled states, and `Remove` followed by the selected label.
The original axe findings stay intact alongside that evidence; other manual findings remain open.
The pinned axe-core dependency is used only by the isolated browser tests. The suite also checks
native keyboard scrolling of reference code. Focused selection acceptance presses Enter on a named
tag-removal button, checks its native values and returned combobox focus, then reopens the list for
the preserved-query checks:

```sh
./gradlew :bml:bml-design-catalog:test --tests 'bosca.design.catalog.CatalogApplicationTest.selection names retain native keyboard removal and focus'
```

Every retained contrast finding also records its computed foreground and composited solid background,
requiring at least 4.5:1. Gradients, opacity, filters, blending and differing text-fill colors require
explicit review rather than an assumed solid-color result. The original axe findings remain intact.
A separate regression scrolls representative footer/reference text and source gutters into view and
opens highlighted choices and tooltips in both palettes. It requires an unoccluded text rectangle,
measures colors, reruns the original contrast rule, and retains any short-text, symbol or overlay
findings. Low-contrast, gradient and opacity probes must fail and restore the exact fixture style.
Reports are saved as `catalog-<mode>-contrast-visibility.json`. Run the focused check from the root:

```sh
./gradlew :bml:bml-design-catalog:test --tests 'bosca.design.catalog.CatalogApplicationTest.contrast findings retain readable colors when scrolled into view'
```

The JSON table regression enters 500 records in text mode and immediately switches views. It checks
virtual scrolling and return, logical row positions, full-column spacer spans and exclusion from the
accessibility tree, small-table counts, empty arrays and records, changing columns, nested values,
native form data and cell edits in both palettes at desktop/phone widths. Caller actions also switch
modes on the same retained editor. Run it from the workspace root:

```sh
./gradlew :bml:bml-design-catalog:test --tests 'bosca.design.catalog.CatalogApplicationTest.virtual JSON tables retain pending text and logical row positions'
```

Empty-widget acceptance checks tab/segment lists and tree bodies with scripts enabled and disabled,
in both document appearances and desktop/phone widths. It inspects actual accessibility nodes,
hidden geometry, retained native segment values, populated controls, adjacent empty-state guidance
and CSS visibility after tree items are removed and restored:

```sh
./gradlew :bml:bml-design-catalog:test --tests 'bosca.design.catalog.CatalogApplicationTest.empty lists do not expose inaccessible widgets'
```

Source-search acceptance measures current and other matches, including nested comment/string syntax,
in both palettes, document appearances and desktop/phone widths. Live foreground/background overrides
must preserve source text, dimensions and outside/sibling syntax colors:

```sh
./gradlew :bml:bml-design-catalog:test --tests 'bosca.design.catalog.CatalogApplicationTest.source search highlights retain readable text across palettes'
```

The panel regression checks 30px controls, UI typography, target gaps and wrapping, native search
actions and Tab order, live control-height/font overrides, and compact/bare growth and restoration:

```sh
./gradlew :bml:bml-design-catalog:test --tests 'bosca.design.catalog.CatalogApplicationTest.source search controls fit normal and compact viewers'
```

Gradle prepares the pinned Chromium automatically for Chrome-based tests. The first run downloads
the browser; subsequent runs reuse Playwright's cache. Set `BML_DESIGN_CHROME_BINARY` to use an
existing executable and bypass preparation. Desktop/phone screenshots are written under
`build/reports/browser/`.

An optional Firefox qualification uses the installed Firefox app on macOS or `firefox` on Linux.
Set `BML_DESIGN_FIREFOX_BINARY` for another executable. It runs the packaged catalog in production
and development with fresh headless profiles, including a profile with application scripts disabled.
The ordinary test and Chrome browser tasks do not require Firefox. Run from the workspace root:

```sh
./gradlew :bml:bml-design-catalog:firefoxTest
```

This task always executes against the installed browser. The thirteen-level tree fixture checks
real browser keyboard input, two-step disclosure, focus, disabled rows, native links, declared branch
ownership, sibling metadata and desktop/phone geometry in both palettes. Enhanced pages receive
full-document axe scans with their original incomplete findings retained. The script-disabled profile
uses synchronous DOM, keyboard, font and layout checks; Firefox does not run asynchronous axe in that
mode. Reports, browser logs and screenshots use the
`catalog-<mode>-firefox-tree` prefix in `build/reports/browser/`. These Gecko behavior and DOM checks
complement the Chrome accessibility snapshots; spoken screen-reader qualification remains separate.

On failure the browser driver saves `catalog-<mode>-driver-failure-<timestamp>-<sequence>.json`
before cleanup. It retains the original error, recent protocol operations and timings, owned Chrome
process metadata and browser/renderer health probes. A blocked renderer also gets a bounded native
stack sample on macOS and an interrupting JavaScript stack capture. Diagnostic failures stay in that
report; the original test still fails. No acceptance request is retried and its ten-second deadline
stays unchanged. Controlled pending-promise and busy-renderer probes verify this distinction using
isolated Chrome instances. The debugger is enabled before acceptance so it can interrupt a busy V8
evaluation; pause-event collection happens only after a failed renderer probe.

Flow/chart acceptance runs actual VueFlow drags and keyboard connections plus all eight Chart.js
built-in types, CSS series colors, controlled updates, native form rules, reset, repeated disposal,
independent dark/light roots and script-disabled alternatives at desktop/phone widths:

```sh
./gradlew :bml:bml-design-catalog:test --tests 'bosca.design.catalog.CatalogApplicationTest.flows and charts*' --tests 'bosca.design.catalog.CatalogApplicationTest.*accessibility*' --tests 'bosca.design.catalog.ChartTableTest'
```

The full-document accessibility reports include visualization scans with expanded node/connection
controls and chart data tables.

The `/dashboards` examples use `dashboard-grid` and `dashboard-item` with Studio's cell-coordinate
model. Each palette contains a real Chart.js widget, a retained note input and a locked summary.
The application owns layout saving and rejection; completion feedback uses toasts. Compact handles
support pointer gestures, keyboard movement/resizing and Escape cancellation. Narrow containers stack
without rewriting desktop coordinates. Widget DOM and local content survive geometry and surrounding
updates. Focused acceptance covers collisions/locks, native forms and reset, controlled layout,
independent roots, CSS gaps, invalid input, repeated disposal and script-disabled content:

```sh
./gradlew :bml:bml-design-catalog:test --tests 'bosca.design.catalog.CatalogApplicationTest.dashboard layouts*' --tests 'bosca.design.catalog.CatalogApplicationTest.*accessibility*'
```

The `/shell` page consumes `app-shell`, `profile` and `profile-menu` from the linked library. It
supplies its own destinations, light/dark palettes and local display-name/account actions, without
an auth or Git dependency. The shared components own gradient layout, sidebar/header spacing,
identity presentation, context activation, menu keyboard navigation and focus restoration.

Complex component inputs use Kotlin model objects and JSON trees. The catalog constructs chart
configurations, graphs and dashboard layouts with `bosca.design.model` types, then binds them
normally. Document and JSON values use Kotlin JSON builders. Native forms submit JSON text;
their receiving actions decode it. JSON editor malformed/empty examples use the explicit `text`
draft prop. See the [structured input contract](../bml-design/README.md#structured-component-inputs).

The examples construct segmented options, local tabs, common chart settings/points and flow card data/ports as Kotlin objects. Standard document examples reuse `bosca.documents` through `documentValue`; custom/submitted content stays lossless JSON. API references show the simple string props and structured object contracts.

The document page includes a directly bound `bosca.content.metadata.model.Document`; the editor extracts its ProseMirror body. The other examples retain open JSON drafts and caller-owned forms.
