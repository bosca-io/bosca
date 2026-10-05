# Bosca BML — IntelliJ plugin

IDE support for `.bml` files. It is the `:bml:bml-ide` subproject of the workspace Gradle build.

## Build & run

Run from the workspace root:

```bash
./gradlew :bml:bml-ide:compileKotlin   # compile against the IDE SDK
./gradlew :bml:bml-ide:buildPlugin     # package bml/bml-ide/build/distributions/bml-ide-*.zip
./gradlew :bml:bml-ide:runIde          # launch a sandbox IDE with the plugin loaded
./gradlew :bml:bml-ide:verifyPlugin    # plugin-structure / compatibility checks
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
- **Formatting and navigation** — BML formatting, embedded-body formatting, component references,
  Kotlin declaration navigation, and CSS class references/usages when the CSS plugin is available.
- **Language injection** (the payoff):
  - Kotlin into `<script server>` bodies and `{ … }` interpolation → full completion,
    highlighting, and error-checking inside embedded Kotlin.
  - Kotlin into attribute values — bound (`:href="u"` / `@click="f"`: whole value) and
    interpolated (`class="btn-{ v }"`: each `{ … }` region independently).
  - TypeScript into `<script client>` bodies (in IDEs that bundle JavaScript/TypeScript;
    looked up by language ID, so it degrades gracefully in Community).

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
