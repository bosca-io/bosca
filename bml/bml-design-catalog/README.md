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

| Route | Examples |
| --- | --- |
| `/` | Unprefixed import/authoring, buttons and feedback, links into the catalog |
| `/foundation` | Labelled form with server validation, pending/success/error feedback, native choices, button sizes, loading/disabled states, badges, empty content and a scrolling table |
| `/themes` | The same form in separate dark and consumer-light palettes, with independent state |
| `/comments` | Independent dark/light comment drafts, pending/rejected posts, native reset and controlled replacements, readonly/disabled/paused/long/escaped variants |
| `/overlays` | Independent modal forms, retained drafts, rejected confirmations, popover forms and menus, sizes and trigger variants |
| `/json` | Independent dark/light JSON drafts, retained engine state, pending/rejected saves, native reset, controlled options and tree/text/table/empty/invalid/readonly/disabled variants |
| `/source` | Independent dark/light source viewers, requested lines, language/wrap changes, preserved state, content replacement and compact/bare/empty layouts |
| `/surfaces` | Panels, padded/unpadded/untitled cards, retained native disclosures, empty/loading/table views and long labels in two palettes |
| `/feedback` | Independent copy outcomes, tooltip placements, repeated toast tones, long notifications and native selectable values |
| `/navigation` | Native route destinations, retained overflow menus, projected actions, changing/current/long lists, clipping and header spacing in two palettes |
| `/trees` | Local file disclosure, server-loaded children, failed-load retry, retained scroll/focus, disabled folders and native links in two palettes |
| `/views` | Search queries, native category values and caller-owned tab panels, retained drafts, rejected searches, changing tab lists and long/empty/readonly/disabled variants |
| `/choices` | Local required/custom choices, multiple native values, tag limits, loading/disabled controls and controlled defaults in two palettes |
| `/selection` | Server-filtered, offset-paged picker in independent dark/light roots, with failed-search retry, loading/disabled controls, native reset and toast saves |
| `/components` | Linked index of all 41 exports and normal unprefixed authoring |
| `/components/controls` | Buttons, inputs, choices, search, segments, accessible local tabs, native selects and the paged picker's authoring source |
| `/components/layout` | Surfaces, headings, native disclosure, application header and route tabs |
| `/components/feedback` | Alerts, badges, empty/loading states, icons, mark, tooltips, copy controls, repeatable notifications and timestamps |
| `/components/overlays` | Native modal focus/return, a failed confirmation that retains its draft, and a popover |
| `/components/content` | Scrolling table, keyboard trees, validated comment composer, JSON save/rejection, readonly/disabled editors and a source viewer |
| `/components/aliases` | A separate, optional aliased spelling beside the normal short name |

The appearance switch changes application CSS through the native checkbox state. It requires no
theme service or JavaScript and does not change the library dependency. Palette sections retain their
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
that key. Server-loaded directories preserve their native buttons so Right Arrow can enter a new
child after loading. Moving focus elsewhere during loading leaves it there. Native disabled folder
buttons are skipped by roving navigation. Without scripts, every rendered file link retains its native
Tab stop. Static cases cover empty trees, unavailable directories, truncated names and wrapped/deep
labels. The tree and tree-item references include the complete composition source. Declarative tree
item actions bind to their row; native attributes and preservation keys target its button or link.

The `/views` composition uses native query and category values to filter deterministic local
examples. The application owns accepted results, rejected drafts, selected views and uniquely linked
tab panels. Editing a query or category keeps previous results until the next accepted search.
Refresh retains query DOM, caret/focus and unsaved category choices. Controlled replacements change
supplied values; changing the tab list changes only its preservation key. Search outcomes use toasts.
Repeated controls disable autofocus. Static examples include readonly/disabled queries, empty and
unmatched groups and long scrolling labels. The search, segment and tab references include the
complete composition source. Keyboard choices reveal their selected option; segmented rows grow
when a visible scrollbar needs space. Search borders use the consumer's control-border color.

The `/source` examples keep a stable preservation key for surrounding updates and change it for
content revisions. The application owns requested lines and server options; the compiled viewer owns
selection, scroll, search and local wrapping. Refresh feedback uses a toast. The source reference shows
the complete local composition, and the page includes short/medium/long compact snippets, compact
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

The content route reaches both source and structured JSON engines. The source route reaches only
the standalone source viewer; the JSON route reaches only the structured editor. Ordinary control
pages exclude both engines. The optional alias declaration stays in its
own example page; normal pages use the single unaliased root import.

Build and verify:

```sh
./gradlew :bml:bml-design-catalog:build
./gradlew :bml:bml-design-catalog:browserTest
./gradlew :bml:bml-design-catalog:accessibilityTest
./gradlew :bml:bml-design-catalog:typecheckClient
```

The ordinary `test` task also includes the browser test. Tests launch separate production and
development JVMs using only built JARs, exercise HTTP assets and actions, and use an isolated headless
Chrome profile. They check navigation, rejected-draft retention, pending and saved form state,
independent instances, CSS-only light appearance, two palettes, narrow layouts, native fallbacks and
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

The ordinary test suite also runs axe-core against all 21 routes in packaged production and
development applications at 1440px and 390px, in both document appearances. Additional scans cover
open choices, navigation menus, dialogs, tooltips, error toasts, validation, pending/loaded tables and
example-state disclosures. Confirmed violations fail the test without disabling rules. Findings
requiring manual review remain in `catalog-<mode>-accessibility.json`; a passing scan does not replace
screen-reader or complete manual accessibility acceptance. The pinned axe-core dependency is used
only by the isolated browser tests. The suite also checks native keyboard scrolling of reference code.

Chrome defaults to the installed Google Chrome app on macOS or `google-chrome` on Linux. Set
`BML_DESIGN_CHROME_BINARY` for another executable. Desktop/phone screenshots are written under
`build/reports/browser/`.
