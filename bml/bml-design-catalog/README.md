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

The appearance switch changes application CSS through the native checkbox state. It requires no
theme service or JavaScript and does not change the library dependency. Palette sections retain their
own colors when the surrounding document changes appearance. The form's BML actions use local
session state and never call a product API. Native labels, required fields, choices and navigation
remain available without scripts; server form actions require BML's browser runtime.

The shared API and [CSS token reference](../bml-design/css-tokens.md) live with the
[design library](../bml-design/README.md). This initial catalog covers the foundation and theme shell;
all-export API pages and advanced interaction/editor compositions are not yet implemented here.

Build and verify:

```sh
./gradlew :bml:bml-design-catalog:build
./gradlew :bml:bml-design-catalog:browserTest
```

The ordinary `test` task also includes the browser test. Tests launch separate production and
development JVMs using only built JARs, exercise HTTP assets and actions, and use an isolated headless
Chrome profile. They check navigation, rejected-draft retention, pending and saved form state,
independent instances, CSS-only light appearance, two palettes, narrow layouts, native fallbacks and
the 11 packaged Geist/Geist Mono assets. No authenticated product session is used.

Chrome defaults to the installed Google Chrome app on macOS or `google-chrome` on Linux. Set
`BML_DESIGN_CHROME_BINARY` for another executable. Desktop/phone screenshots are written under
`build/reports/browser/`.
