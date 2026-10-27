# Bosca BML — IntelliJ plugin

IDE support for `.bml` files. It is the `:bml:bml-ide` subproject of the workspace Gradle build.

## Build & run

Run from the workspace root:

```bash
./gradlew :bml:bml-ide:compileKotlin   # compile against the IDE SDK
./gradlew :bml:bml-ide:buildPlugin     # package bml/bml-ide/build/distributions/bml-ide-*.zip
./gradlew :bml:bml-ide:runIde          # launch a sandbox IDE with the plugin loaded
./gradlew :bml:bml-ide:verifyPluginStructure # packaged descriptor and layout checks
./gradlew :bml:bml-ide:verifyPlugin    # binary compatibility with the configured SDK
```

Targets **IntelliJ IDEA Ultimate 2026.1.2** (`useInstaller = false` resolves the published Maven
artifact) with the **IntelliJ Platform Gradle plugin 2.18.1**, **Kotlin 2.4.0**, and the Java 25
toolchain. Ultimate supplies the JavaScript/TypeScript support used for client-script injection tests.

## What it does (v1)

- **File type** — `.bml` is recognized, with an icon.
- **Lexer + parser** — a fully-restartable lexer that tokenizes tags into parts (brackets,
  name, attribute name/`=`/value, tag-level `{…}` spread) while keeping the *injectable*
  regions (script bodies, `{ … }` interpolation) as single tokens. The parser wraps those
  regions in `PsiLanguageInjectionHost` nodes.
- **Syntax highlighting** — tag brackets, tag names, attribute names, attribute values,
  interpolation, comments, and script bodies (distinctly colored).
- **Commenting** — `Ctrl+/` wraps selections in `{# … #}`.
- **BML completion** — context-aware tag, attribute, render/cache mode, component, and component-prop
  suggestions, including deferred islands and shared-cache page attributes.
- **Library imports** — component completion and Go to Declaration combine the BML source root's
  `imports.bml` with file-local imports. Bare tags and aliases use the compiler's import resolver,
  including local overrides, private exports, collisions, and explicit `html:` / `svg:` escapes.
  Dependencies come from the current module's compile classpath; unrelated modules and arbitrary
  source archives do not contribute components. Project dependencies use their compiled manifests
  and original BML source roots. Published dependencies use their own attached sources JARs, checked
  against the binary manifest's source digest. Changes to imports or dependency roots update bindings
  without compiling the consumer. Missing or mismatched sources leave metadata completion available
  and show a message explaining how to download or attach matching sources.
- **Formatting and navigation** — BML formatting, embedded-body formatting, component references,
  Kotlin declaration navigation, and CSS class references/usages when the CSS plugin is available.
- **Tag folding** — collapse multiline tag bodies with the editor's gutter controls or standard
  Collapse/Expand actions. Nested HTML, BML, and bare/namespaced component tags fold independently,
  as do script/style bodies. Opening and closing tags stay visible; tags start expanded.
- **Language injection** (the payoff):
  - Kotlin into `<script server>` bodies and `{ … }` interpolation → full completion,
    highlighting, and error-checking inside embedded Kotlin.
  - Kotlin into attribute values — bound (`:href="u"` / `@click="f"`: whole value) and
    interpolated (`class="btn-{ v }"`: each `{ … }` region independently).
  - TypeScript into `<script client>` bodies (in IDEs that bundle JavaScript/TypeScript;
    looked up by language ID, so it degrades gracefully in Community).

Run `./gradlew :bml:bml-ide:test` from the workspace root for the editor tests. Library acceptance
attaches the actual Design Library binary and sources artifacts to an isolated IDE project, with
no producer checkout or compiled consumer. All public components navigate through bare and
optional aliased opening, closing and self-closing tags to their original attached BML declarations.
Every public prop completes in literal and bound form even without source attachments. Kotlin
navigation reaches attached `NavigationItem`, `ChartPoint` and `ChartTable` source. The suite also
invokes the registered Go to Declaration action, checking the selected original BML/Kotlin file
and declaration offset. Kotlin inside an attached BML source uses that attachment's Kotlin context,
including in a BML-only consumer with a conflicting type in an unrelated module. Other cases
check global import changes, source/version mismatches and module isolation. `buildPlugin`
packages the shared BML parser and manifest/import contracts without their
transitive server dependencies or another Kotlin compiler.

These fixtures use the configured 2026.1.2 SDK. To check the packaged plugin's binary compatibility
with another installed IDE as well, pass its installation directory:

```bash
./gradlew :bml:bml-ide:verifyPlugin -Pbml.ideVerificationPath="/path/to/IntelliJ IDEA.app"
```

The verifier reads the IDE installation and writes reports under `build/reports/pluginVerifier`.
It does not install the plugin or change that IDE's settings. Binary verification and the SDK
fixtures do not establish live manual operation in another IDE.

## Follow-on stages

- **KEFS-style FIR** — consume the compiler's published FIR extension
  (`-Pbml.ideKotlinVersion`) so synthesized declarations resolve in the editor.
- **`.bml` breakpoint debugging** — foundations in place: the compiler emits a `<Object>.kt.map`
  sidecar (generated-`.kt`-line → `.bml`-line, see `BmlSourceMap` in `bml-compiler`), and the
  plugin has `BmlSourceMapIndex` — a unit-tested reader with bidirectional `.bml`↔`.kt` lookup.
  Remaining (needs a live debug session to verify, so not yet built): a JVM `PositionManager`
  that uses the index to (a) set a `.bml` breakpoint at the right `.kt` line and (b) resolve a
  generated-`.kt` stop back to its `.bml` position. (A later refinement is baking JSR-045 SMAP
  into the bytecode so the stock debugger maps natively, without a custom PositionManager.)
- **Structure view and brace matching** for BML's own tag vocabulary.
