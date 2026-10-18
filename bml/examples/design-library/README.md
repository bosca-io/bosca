# Design library consumer

An isolated BML application using `project(":bml:bml-design")`, one unaliased `imports.bml`, and
nested pages/components. It renders two palettes against the same compiled dependency, application
state, projected child content, native control escapes, and optional aliases. Forms and click actions
use ordinary BML declarations. It has no Git dependency or backend connection.

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

Chrome defaults to the installed Google Chrome app on macOS or `google-chrome` on Linux. Set
`BML_DESIGN_CHROME_BINARY` for another executable. Screenshots land in this project's
`build/reports/browser/`. The fixture is an application and is excluded from aggregate Maven
publication.
