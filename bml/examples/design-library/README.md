# Design library consumer

An isolated BML application using `project(":bml:bml-design")`, one unaliased `imports.bml`, and
nested pages/components. It renders two palettes against the same compiled dependency, application
state, projected child content, native control escapes, and optional aliases. Forms and click actions
use ordinary BML declarations. It has no Git dependency or external backend connection.

Run from the workspace root:

```sh
./gradlew :bml:examples:design-library:run
```

Open `http://localhost:9092/`; `/aliases` compares aliased and unprefixed imports, and `/foundation`
exercises checkboxes, switches, feedback states and table scrolling. For incremental
development and browser reload:

```sh
./gradlew :bml:examples:design-library:bmlDev
```

Application CSS and the full HTML document are owned here. The library supplies packaged defaults
and component styles through the normal BML asset pipeline. The server uses the compiler-generated
aggregate registries and packaged application assets.

The `/themes` page tests the [public CSS token contract](../../bml-design/css-tokens.md) with the same
unprefixed controls in default and customized roots. Base surface/radius overrides flow into controls
without repeated aliases. Shared heights, content gaps, focus rings and shadow/backdrop colors change
through consumer CSS. Checks cover explicit aliases, open overlays, live class changes, independent
neighbors and native select presentation with scripts disabled. JSON checks also override only the
editor accent and verify its derived hover, selection and focus colors.

```sh
./gradlew :bml:bml-design:bmlValidateLibrary :bml:examples:design-library:test
```

Tests start separate JVMs with JAR-only classpaths, serve real HTTP, and run an isolated headless
Chrome profile in development and production. They exercise labels and native input attributes,
32/30/24px controls, two simultaneous themes, action forwarding and state isolation, pending
geometry/accessibility, alias identity/resource deduplication, and native elements with scripts
disabled. Additional compiler checks cover local precedence, import collisions, and typed prop
diagnostics against the actual design-library artifact.

The foundation page checks native form values, controlled checkbox/switch change actions, disabled
controls, independent instances, switch accessibility names, status/alert semantics, feedback colors
from consuming CSS, and keyboard scrolling inside a narrow table region. These controls also work
as native inputs with scripts disabled.

The `/layout` page uses the library-owned `NavigationItem`, caller-supplied breadcrumbs,
responsive header/navigation, panels/sections and native disclosures. Its projected heading actions
update application state. Consuming CSS changes the mark colors and panel radius. Browser checks
cover desktop/phone geometry, active-navigation visibility, disclosure keyboard operation and
the preserved Bosca SVG paths. Native sections remain available through `<html:section>`.

The `/actions` page demonstrates native icon buttons/links, labelled form submission and tooltips
in two themed roots. Checks cover pointer and keyboard use, viewport placement, clipped ancestors,
live CSS overrides, description IDs, changed labels/triggers, delayed-show cancellation, pending
geometry, repeated mount/unmount and listener cleanup. Icon labels and link navigation remain
available when scripts are disabled.

The `/overlays` page exercises dialogs, confirmation and popovers with caller-owned form/state.
Checks cover modal names and focus, Tab containment, Escape/backdrop/cancel dismissal and return
focus, disabled triggers, pending geometry, failed-submit drafts, completion/reopen and server-issued
requests. A preserved modal keeps an unsaved draft across unrelated updates. Popovers inherit two
consumer palettes, escape clipped ancestors, fit desktop/phone/short viewports, retain nested action
updates, and provide menu keyboard navigation. Repeated mounting checks listener and ResizeObserver
cleanup. With scripts disabled, popovers remain native details disclosures.

The `/clipboard` page checks exact copied values, accessible/icon feedback, stable visible/quiet
geometry, denied/unavailable clipboard recovery, readonly selection, successful retry, feedback
timeouts, overlapping results, preserved value/label updates and disposal during an in-flight copy.
Two themes and desktop/phone layouts use the same library exports. Browser tests stub clipboard
outcomes to avoid changing the system clipboard; rendering, server actions and lifecycle are real.
Script-disabled values remain manually selectable.

The `/notifications` page issues notices through caller-owned actions and monotonic sequences.
Checks cover live-region priorities, repeated text, same/older/empty sequences, failed actions,
retained DOM and announcements/timeouts across unrelated updates, hover/focus pausing, return focus,
new-notice timeouts, reduced motion, two live CSS themes, long-message keyboard scrolling and narrow/
short viewport fit. Document visibility is controlled through an isolated getter/event override for
its pause/resume check. Repeated disposal verifies timer, listener and ResizeObserver cleanup,
and remounting a retained nonempty message does not replay the earlier notice.

The `/selection` page uses search input, segmented controls and local view tabs. Checks cover native
form values, one clear/input event, caller-controlled autofocus, readonly/disabled search, typed
drafts/caret/focus through unrelated patches, changed controlled props, rejected search recovery,
roving arrows/Home/End and wrapping, local view events, caller-owned panel relationships, empty and
unmatched selections, independent roots and live CSS overrides. Repeated unmounts check root and
document listener cleanup. Script-disabled search and initial segmented values remain native. Long groups verify that keyboard
focus reveals the selected option and a horizontal scrollbar does not clip segmented buttons.

The `/navigation` page uses native route tabs, shared `NavigationItem` values and projected actions.
Checks cover current/overflow destinations, keyboard navigation and dismissal, container/viewport
refits, focused-link continuity, retained menus across unrelated action patches, changed lists, long
current labels, empty/short/unselected lists, header spacing, clipped/short-screen popups and live
consumer themes. Disposal checks listeners and ResizeObservers. A separate destination page verifies
native navigation from overflow links and script-disabled scrolling rows.

The `/selects` page covers native single/multiple values, required validation, disabled/loading
controls, keyboard choices, local search/custom choices and tag removal/limits. Its application-owned
`SelectChoices` BML contract serves isolated HTTP pages, failures and delayed responses; the generic
control only emits `selectsearch`. Browser checks cover minimum query/debounce, offsets, explicit
persistent options, error/retry and stale results, native values across pages, remote multiple/custom
choices, preserved query/caret/focus, native reset, live CSS themes, clipping/short screens and disposal.
Script-disabled checks retain styled native selects and form values. The adapter imports
`SelectSearch` from `@bosca/bml-design/select` with `import type` and uses the existing same-origin
contract client. Its TypeScript configuration includes the generated client tree, where the library
linker extracts declarations from the dependency JAR. The type checks cover `SelectOption`,
`SelectPage` and `SelectSearch`, including invalid option values and pagination offsets.

From the workspace root, prepare and check the consumer TypeScript with:

```bash
./gradlew :bml:examples:design-library:bmlPrepareClient
node bml/bml-runtime/node_modules/typescript/bin/tsc --project bml/examples/design-library/tsconfig.json
```

Chrome defaults to the installed Google Chrome app on macOS or `google-chrome` on Linux. Set
`BML_DESIGN_CHROME_BINARY` for another executable. Screenshots land in this project's
`build/reports/browser/`. The fixture is an application and is excluded from aggregate Maven
publication.

The `/composition` page combines local and server-loaded trees, plain comment forms and timestamps.
Its own state supplies directory actions, delayed submissions, permission changes, rejected drafts
and controlled values. Two themes exercise native links, initial local disclosure, roving focus,
type-ahead, focus into asynchronously added children and preservation across server updates.
Comment checks cover native names/values, blank/readonly/disabled/permission states, sizing,
Ctrl+Enter, pending feedback, failure/retry, current descriptions, retained draft/caret/focus,
controlled values and native reset. Repeated removal/remount checks listeners and observers.
Disabled native directories are skipped during keyboard traversal. Script-disabled checks retain
comment form values, native Tab stops for every rendered file link and server timestamps.
Timestamp tests cover English UTC/default, custom offset formats, relative past/future/older dates,
escaped invalid values and invalid-pattern errors. Desktop and phone screenshots cover this page.

The `/source` page consumes the compiled read-only CodeMirror viewer with no CodeMirror npm
dependency in this application. It covers explicit and inferred languages, requested lines independent
of URL parameters, wrapping, compact/bare and empty/plain-text cases, escaped native fallback,
search, read-only keyboard behavior, preserved state/focus and changed caller options. Its two
palettes and live CSS overrides exercise editor, gutter, active-line, search and syntax colors.
Removal/remount checks CodeMirror destruction, observers and document listeners. HTTP/archive
checks prove that ordinary button/card pages exclude viewer JS/CSS while source pages include it.
Git also consumes this viewer; its editable and preview workflows remain in the Git application.


The `/json` page consumes the compiled structured JSON editor. Its two independent caller-owned
forms retain exact invalid drafts across failed submissions and unrelated updates, and support
controlled replacements, native reset, tree/text/table views, readonly/disabled states and pending
submission feedback. The browser checks real tree-cell editing and immediate text submission,
keyboard exit, two palettes/live CSS changes, repeated disposal and non-overlapping desktop/phone
viewports. With scripts disabled, escaped textarea drafts remain native form values. Archive and HTTP
checks ensure basic and source-only pages exclude JSON editor assets. Git consumes the same library
editor and retains its own configuration validation and actions.
