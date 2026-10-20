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
| `/components` | Linked index of all 41 exports and normal unprefixed authoring |
| `/components/controls` | Buttons, inputs, choices, search, segments, accessible local tabs and static searchable selects |
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
retain a dismissed notification's state. Toasts sit near the viewport's bottom edge in this application.

The application stylesheet owns a dark palette with a blue accent and a light palette with neutral
surfaces/text and a green accent. It defines content stacks and section gaps explicitly, including
space between button rows, notes and badges. The sidebar allows the product name to stay on one line.

Every export has a rendered example, prop/type/default reference, native attribute/event and child
contract, keyboard notes, CSS hooks and an escaped authoring snippet. Local composition snippets
include their BML state wiring; the corresponding `Catalog*` models live in this application's Kotlin
sources. The shared [API](../bml-design/README.md) and [CSS token reference](../bml-design/css-tokens.md)
also live with the library. The searchable selects currently use local native options; a server-driven
offset-paged picker and the complete state/accessibility matrix remain to be added to this catalog.

Only the content route reaches the source and structured JSON engines. Other reference pages consume
the same library without eagerly loading those editors. The optional alias declaration stays in its
own example page; normal pages use the single unaliased root import.

Build and verify:

```sh
./gradlew :bml:bml-design-catalog:build
./gradlew :bml:bml-design-catalog:browserTest
```

The ordinary `test` task also includes the browser test. Tests launch separate production and
development JVMs using only built JARs, exercise HTTP assets and actions, and use an isolated headless
Chrome profile. They check navigation, rejected-draft retention, pending and saved form state,
independent instances, CSS-only light appearance, two palettes, narrow layouts, native fallbacks and
the 11 packaged Geist/Geist Mono assets. The suite compares the catalog index and documented prop
names/types/default presence with the library manifest from its built JAR, then exercises all reference
routes at 1440px and 390px. It also checks modal focus/return and failed drafts, local tab/panel
relationships, segment values, repeated toast sequences, keyboard tree traversal, comment validation
and keyboard submission, JSON rejection/save, readonly source, alias identity and script-disabled
content fallbacks. No authenticated product session is used. Runtime failures use the application's
console logging backend and are included in integration-test diagnostics.

Presentation checks measure the rendered overview/foundation examples in both appearances at
1440px and 390px: one-line branding, content/section gaps, document overflow, text contrast of at
least 4.5:1, and at least 3:1 for sampled input borders, switch thumbs and keyboard focus indicators.
They wait for CSS transitions to finish before measuring colors and include translucent badge fills.
These are targeted checks, not a complete accessibility audit. Results are saved as
`build/reports/browser/catalog-<mode>-presentation.json` beside the screenshots.

Chrome defaults to the installed Google Chrome app on macOS or `google-chrome` on Linux. Set
`BML_DESIGN_CHROME_BINARY` for another executable. Desktop/phone screenshots are written under
`build/reports/browser/`.
