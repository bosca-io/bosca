# Reusable BML libraries and the Bosca BML Design Library

Implementation started, 14 October 2026. Source baseline: bosca-workspace 8fe195d0. BML owns the library capability; the Git UI supplies the first design-library use case, and a second independent BML application proves reuse.

## Outcome

A BML project can publish reusable components as a versioned library. Another BML project declares a normal Gradle dependency, imports its exported components directly or under an optional namespace, and uses those components with their server rendering, typed props, child content, client behavior, styles, assets, and localization intact. Imports can be declared once for the whole BML project or added in individual files. Consumers do not copy component files, reference another checkout’s build directory, repeat shared imports on every page, or manually register each library component.

The first library is the Bosca BML Design Library, proposed Maven coordinate io.bosca:bml-design. It supplies the shared Bosca visual language and UI building blocks extracted from the Git UI. Applications own their routes, data loading, authentication, permissions, and business actions.

## Implementation progress

The first producer foundation is implemented: configured Kotlin package names, explicit library mode and exports, component/source manifests, original BML and Kotlin source attachments, and validation of declarations and artifact contents. The compiler, metadata, Gradle source/publication, and existing sample application tests validate this first implementation. The publication fixture consumes compiled renderers and Kotlin models from an isolated Maven repository after deleting the producer checkout; it does not yet consume library components through BML import declarations. Consumer import resolution (including module-wide `imports.bml`), canonical runtime identities, consumer asset loading, development reloads, and IDE navigation remain to be implemented under the requirements below. The complete library feature is not yet available to BML consumers.

The manifest currently records authored Kotlin prop types and default presence. Resolving public prop type identities across producer imports and dependency boundaries remains part of the component-contract work. The initial binary fixture uses an ordinary Kotlin model and a compiled renderer; serializer/action/DI and native acceptance cases remain outstanding.

The real Gradle fixture established that Kotlin 2.4’s incremental frontend does not invoke the additional-source hook used by BML. BML main JVM compilations use the whole-module frontend; Gradle up-to-date checks and build caching remain available. Changes within a BML module currently recompile its main Kotlin sources together. Kotlin test compilations retain their incremental behavior and consume the compiled main renderers without rewriting their metadata. Compiler resources, TypeScript, metrics/CSS, and optional Kotlin/source maps are declared compilation outputs. Compiler TypeScript and GraphQL TypeScript have separate output directories; `bmlPrepareClient` assembles them under `build/generated/bml/ts` before bundling, preserving `./graphql/...` imports. Custom source directories are resolved lazily, and source archives are ordered after compilation during publication. These build constraints also apply to the development-reload work.

### Library asset packaging

Producer artifacts include generated `<script client>` modules, standalone `.ts`/`.js`/`.css` files under `src/main/client` (including nested files), their imported dependencies, and public resources. Relative imports, bare npm imports, and package import mappings in generated client modules resolve against the original BML file's canonical location. Generated GraphQL and contract relative imports retain their generated-directory fallback. Private npm dependencies are bundled into browser ESM with shared chunks; `@bosca/bml` remains external for the consumer's runtime.

Authored-file discovery skips `node_modules` directories at every depth. Installed packages contribute browser code, CSS, and resources through imports; unused package utilities and stylesheets are not standalone library entries. Kotlin/BML compilation and inline stylesheet extraction use the same boundary, so dependency examples and native code neither produce classes/components nor enter original-source validation. Original source attachments exclude installed package trees from both inherited Kotlin source-set inputs and explicit BML, client, and public copies. Imported dependency changes remain tracked as bundling inputs.

Inline TypeScript is loaded at the canonical BML source path, so esbuild applies that source's TypeScript configuration, including inherited settings. Adjacent helpers retain their own per-file configuration. Generated modules remain distinct when multiple authored BML paths alias one physical file, and source attachments retain each authored path.

Linked npm packages work with both project-local and ancestor `node_modules` installations. Source ownership uses the canonical roots of all installed package directories, including ordinary packages beneath a linked `node_modules` or `@scope` directory. External package stores remain valid dependencies, and stores inside the project do not enter the authored-source inventory. The producer's own sources remain attached when the workspace also installs a link to the producer. Gradle tracks the installed links' targets for rebuilding JavaScript and inline CSS after dependency edits. Standalone stylesheets use `styles/` within the library namespace; scripts and their emitted CSS use `client/`, so `theme.ts` importing CSS can coexist with an independent `theme.css`.

Dependency discovery walks authored sources and installed package trees, follows nested file/directory links, and includes ancestor `node_modules` directories for their canonical targets. This covers external source roots, linked component subdirectories, and dependencies hoisted above linked npm packages. Discovery visits each canonical directory once and skips build/cache directories. Lookup directories and link targets are explicit task inputs, so retargeting a link updates dependency resolution even when both destination trees are already tracked and the linked files are identical. Unchanged builds retain configuration-cache reuse and up-to-date bundling.

Both bundling stages track ancestor `package.json`, `tsconfig.json`, and `jsconfig.json` files, including missing candidates so that configuration creation and removal invalidate output. Configuration discovery follows JSONC `extends` chains, including arrays, relative files, and package configuration targets. Configuration paths and contents are inputs; inherited settings outside the producer and source root participate in rebuilding.

Npm `browser` mappings to `false` and JavaScript/CSS data-URL imports are respected. The source inventory matches esbuild's physical file loads to its final module graph, excluding virtual modules in both inline CSS preparation and browser module bundling. Real source dependencies remain required; deleting a stylesheet after preparation still fails asset packaging.

Configured source trees and public resources follow file and directory symlinks while preserving their authored archive paths. Canonical inputs reported by esbuild map back to those originals without duplicate source entries; distinct authored aliases of one resource remain available at each path. Cyclic directory links and dangling asset links fail packaging.

The parser extracts inline `<style>` bodies before Kotlin compilation. Esbuild resolves `@import` and `url()` dependencies against the original BML file's canonical location, including linked files and directories, then the compiler applies component scoping to the combined CSS. Direct inline package imports select the same source-side installation as adjacent stylesheets. Both ordinary and scoped inline styles retain the prepared CSS in the compiled renderer. Root-relative CSS resources resolve from the producer's `public/` directory. Remote URLs remain remote. Images and fonts use namespaced, content-hashed resource URLs; public files are also retained beneath the library namespace.

CSS scoping preserves source and license comments between rules. Conditional at-rules such as `@media` retain their preludes while their nested selectors receive component markers; `@font-face` and `@keyframes` retain their unscoped definitions.

`META-INF/bml/client-manifest.json` records the coordinate/version namespace, module and stylesheet mappings, top-level global entry points, resource paths, and SHA-256 inventories for emitted files and original asset sources. Browser resources live under `bml/libraries/<coordinate-version-hash>/`. Original BML-adjacent helpers, client files, and public resources accompany the BML/Kotlin source attachment. Archive validation checks these inventories against the selected binary and sources JARs. A library with client assets fails if its bundler is unavailable or returns success without producing a library manifest. Each executed library bundle clears its previous asset and asset-source output first. Publication validation requires the client manifest for standalone client files, public resources, and prepared CSS resources even without inline client scripts and when bundling is skipped. Library builds need the matching `@bosca/bml` npm package or an explicit `bml.clientBundler` path.

This completes producer packaging. Automatic extraction, selection, linking, and serving of these assets by a BML application still depend on the consumer import and registry work. Current publication tests explicitly read the archive and invoke the existing consumer bundler; they do not establish working `<import>` declarations or automatic server asset loading.

### Library asset validation

The real Gradle fixture publishes a library containing scoped/unscoped inline styles, inline client code, relative and project-level TypeScript helpers, nested standalone CSS/TypeScript, a font, and SVG resources. It verifies checksums in the binary and sources JARs, uses custom BML source and build directories, reuses the configuration cache, edits nested dependencies, removes an entry point, checks clean rebuilding, and rejects missing JS/font/source entries before publication. After deleting the producer checkout, a separate Kotlin consumer renders the published component and a browser bundler consumes only its archived ESM plus the consumer runtime. A second regression rejects client-library publication without a bundler. Standalone sources-JAR builds remain usable without compiling or resolving the compiler toolchain.

Five browser bundler regressions cover inline CSS imports and URL suffixes, remote references, nested files, private npm code, shared chunks executing once, rebundling without producer sources/npm dependencies, CSS-only input, distinct coordinate/version namespaces, stale-output cleanup, and missing declared modules.

Ran successfully from the workspace root:

```bash
./gradlew :bml:core-bml:test --tests 'bosca.bml.library.*' :bml:bml-compiler:test :bml:bml-gradle:test :bml:bml-sample:test :bml:bml-ide:test :bml:bml-ide:buildPlugin --rerun-tasks --no-build-cache --console=plain
```

Results: 4 library metadata tests, 357 compiler tests, 26 Gradle tests, 33 sample tests, and 117 IDE tests passed (537 total; no failures, errors, or skips). All 91 scheduled tasks executed, and IDE plugin packaging succeeded. From `bml/bml-runtime`, `npm test` passed all 233 tests across 15 files and `npm run typecheck` passed. `git diff --check` passed. Focused archive and source regressions also passed after correcting the standalone sources-JAR dependency. No coverage report, native smoke test, full imported-component server/browser integration, plugin compatibility verifier, or manual IDE UI check was run.

### Library asset review fix validation

The publication fixture now covers inline and imported conditional CSS, a font definition at the beginning of an imported stylesheet, standalone CSS sharing a basename with a script that imports different CSS, and a hoisted scoped npm workspace package. It verifies separate JavaScript and CSS edits in that package invalidate their outputs with configuration-cache reuse. A workspace link back to the producer preserves its original source attachment and does not introduce its build outputs as dependency inputs. The published component renders and its archived browser module executes after both the producer and linked dependency are removed.

Two additional compiler regressions cover comments before conditional rules, font definitions, keyframes, and licenses. Browser tests now include the CSS output collision and both local and hoisted linked packages, their CSS/image dependencies, and consumption after removal of the producer and dependency sources.

Ran successfully from the workspace root:

```bash
./gradlew :bml:bml-compiler:test :bml:bml-gradle:test :bml:bml-sample:test --rerun-tasks --no-build-cache --console=plain
```

Results: 359 compiler tests, 26 Gradle tests, and 33 sample tests passed (418 total; no failures, errors, or skips). All 71 scheduled tasks executed. From `bml/bml-runtime`, `npm test` passed 236 tests across 15 files and `npm run typecheck` passed. The original isolated Node reproductions now pass, and `git diff --check` passed. Native, full imported-component server/browser acceptance, coverage, and IDE checks were not rerun for these fixes.

### Browser-disabled dependency validation

Two runtime regressions verify `browser` mappings to `false` for relative JavaScript imports, a Node built-in import, and CSS imports, plus failure when a real stylesheet disappears after preparation. The disabled-module regression failed before the fix. The real publication fixture now includes a linked npm package with disabled JavaScript and CSS files, validates the published archives, and executes the archived browser module after deleting the producer and dependency.

Ran `./gradlew :bml:bml-gradle:test --rerun-tasks --no-build-cache --console=plain` from the workspace root: all 26 Gradle tests passed with no failures, errors, or skips, and all 56 scheduled tasks executed. From `bml/bml-runtime`, `npm test` passed all 238 tests across 15 files, including 10 library asset cases, and `npm run typecheck` passed. `git diff --check` passed. Compiler, sample, IDE, native, coverage, and full imported-component server/browser acceptance checks were not rerun for this bundler fix.

### Source inventory and binary consistency validation

Five additional runtime cases cover JavaScript/CSS data URLs, physical stylesheet imports with query suffixes, symlinked public files/directories with multiple authored aliases, a symlinked BML root with adjacent client/CSS dependencies, and cyclic/dangling links. Existing disabled-module coverage also checks a mapping whose target file does not exist. Archived modules execute after removing their original sources, and a real missing stylesheet still fails packaging.

The publication fixture uses an external symlinked BML root, linked public resources, inline JavaScript/CSS data URLs, and a linked npm dependency together. It checks archive hashes, rejects omission of a linked public font, and consumes the artifacts after deleting all producer/source/asset/dependency directories. A separate regression rejects a stale replacement binary after changing a Kotlin model, verifies the previous publication remains intact, then publishes a replacement built from current outputs and executes its updated implementation.

Ran `./gradlew :bml:bml-gradle:test --rerun-tasks --no-build-cache --console=plain` from the workspace root: all 27 Gradle tests passed with no failures, errors, or skips, and all 56 scheduled tasks executed. From `bml/bml-runtime`, `npm test` passed all 243 tests across 15 files, including 15 library asset cases; `npm run typecheck` passed. The original isolated asset reproductions and `git diff --check` also passed. Compiler, sample, IDE, native, coverage, and full imported-component server/browser acceptance checks were not rerun for these fixes.

### Symlinked dependency tracking validation

A new real Gradle publication regression verifies separate JavaScript and CSS edits in an npm package beside an external symlinked BML source tree. It checks configuration-cache reuse, published JavaScript and scoped CSS contents, archive inventories, retargeting the source symlink with identical authored files, and unchanged builds remaining up to date. The regression failed against the original implementation because the changed JavaScript left bundling up to date.

Ran `./gradlew :bml:bml-gradle:test --rerun-tasks --no-build-cache --console=plain` from the workspace root: all 28 Gradle tests passed with no failures, errors, or skips, and all 56 scheduled tasks executed. The focused new regression and `git diff --check` also passed. Runtime, compiler, sample, IDE, native, coverage, and full imported-component acceptance test suites were not rerun for this Gradle input-tracking fix.

### Nested source and package dependency validation

The dependency publication fixture now covers a symlinked source root, a linked component subdirectory, and a linked npm package with dependencies hoisted above its canonical package location. Each case independently edits JavaScript and CSS, checks the published archive, reuses the configuration cache, and retargets the link while both destination trees are already installed. Link changes rebuild the assets even when the lookup-directory set and linked file contents remain unchanged; subsequent unchanged builds remain up to date. Both newly added layouts reproduced stale publication before the fix.

Ran `./gradlew :bml:bml-gradle:test --rerun-tasks --no-build-cache --console=plain` from the workspace root: all 30 Gradle tests passed with no failures, errors, or skips, and all 56 scheduled tasks executed. All five focused asset tests passed. Both original review fixtures now publish dependency edits without forced rebuilding and reuse the configuration cache. `git diff --check` and whitespace checks of the edited untracked files passed. Runtime, compiler, sample, IDE, native, coverage, and full imported-component acceptance test suites were not rerun for this Gradle fix.

### Resolver configuration and inline import validation

Two publication regressions verify parent package import mappings, shared `jsconfig.json` inheritance, JSONC comments/trailing commas and `extends` arrays, configuration package exports, and creation/removal of an overriding `tsconfig.json`. They cover an ordinary subproject and an external symlinked BML tree, execute the published JavaScript through the consumer bundler, and check configuration-cache reuse and unchanged builds. Two browser regressions verify direct npm and package imports select the source-side dependency with and without a different producer-side package installed, while generated contract imports and the external runtime remain usable. Both browser cases failed before the fix.

Ran `./gradlew :bml:bml-gradle:test --rerun-tasks --no-build-cache --console=plain` from the workspace root: all 32 Gradle tests passed with no failures, errors, or skips, and all 56 scheduled tasks executed. From `bml/bml-runtime`, `npm test` passed all 245 tests across 15 files, including 17 library asset cases; `npm run typecheck` passed. The original package/TypeScript configuration reproductions now publish updated JavaScript after configuration-only edits without forced rebuilding, and the original inline-import reproduction selects the source-side package. `git diff --check` and whitespace checks passed. Compiler, sample, IDE, native, coverage, and full imported-component acceptance suites were not rerun for these fixes.

### Inline source-context validation

Four browser regressions verify direct inline CSS imports use the canonical BML location, with and without a competing producer-side package, and inline TypeScript uses source-local inherited compiler settings in ordinary and linked source trees. They cover simultaneous components with different settings, distinct generated modules for source aliases, a BML link targeting a file with another extension, generated contract imports, and execution after removing original sources. All four reproduced the reported source-context failures before the fix.

The real publication fixtures now import package CSS directly from linked BML roots and component directories while a competing producer-side package is installed. Two configuration fixtures publish a component with source-local class-field settings, execute both inline and adjacent helper code from its archive, change those settings, and verify the new behavior with configuration-cache reuse. Unchanged bundling remains up to date.

Ran `./gradlew :bml:bml-gradle:test --rerun-tasks --no-build-cache --console=plain` from the workspace root: all 32 Gradle tests passed with no failures, errors, or skips, and all 56 scheduled tasks executed. The seven focused publication tests also passed. After the final loader adjustment, `./gradlew :bml:bml-gradle:test --no-build-cache --console=plain` executed the test task again and all 32 tests passed. From `bml/bml-runtime`, `npm test` passed all 249 tests across 15 files, including 21 library asset cases; `npm run typecheck` passed. The original direct-CSS reproduction publishes the source-side stylesheet, and the original TypeScript reproduction now executes the same `raw/raw` result as the source-location control. Compiler, sample, IDE, native, coverage, and full imported-component acceptance suites were not rerun for these fixes.

### Nested npm dependency packaging validation

Two browser regressions reproduce unused Node utilities and invalid CSS being treated as standalone entries when packages are installed directly under the client directory or a nested client directory. Both now bundle only imported dependency code, CSS, and images and execute after removing the producer sources and npm installation. A standalone sources-JAR regression reproduces dependency source leakage through inherited and explicit inputs, then verifies nested dependency trees are excluded while original BML, Kotlin, TypeScript, and CSS remain available without compilation. Dependency-only edits leave that source archive up to date.

A real publication regression combines inline BML and nested client imports from source-local npm installations. It checks source inventories and both archives, edits dependency JavaScript and CSS, verifies updated output with configuration-cache reuse, and checks unchanged bundling stays up to date. It then deletes the producer and executes the archived browser modules in a separate consumer.

Ran `./gradlew :bml:bml-gradle:test --rerun-tasks --no-build-cache --console=plain` from the workspace root: all 33 Gradle tests passed with no failures, errors, or skips, and all 56 scheduled tasks executed. Three focused source/publication tests also passed. From `bml/bml-runtime`, `npm test` passed all 251 tests across 15 files, including 23 library asset cases; `npm run typecheck` passed. The original isolated failing project now publishes successfully, and direct archive inspection confirms imported dependency code/styles and original sources remain while installed dependency source trees are absent. `git diff --check` and untracked-file whitespace checks passed. Compiler, sample, IDE, native, coverage, and full imported-component acceptance suites were not rerun for this fix.

### Dependency Kotlin and BML source exclusion validation

Two compiler/extractor regressions and the expanded real publication fixture reproduced dependency BML examples entering compilation and stylesheet preparation before the fix. Main Kotlin source discovery, BML declaration discovery, and stylesheet extraction now exclude installed dependency directories consistently with archive packaging. Source validation inherits the main Kotlin source-set exclusion. The fixture retains an authored Kotlin model beside its BML component while excluding dependency Kotlin classes, Kotlin build scripts, and BML examples from the binary, manifests, and source attachment. Imported browser code, CSS, and images remain usable after removing the producer; dependency edits still rebuild, with configuration-cache reuse. Edits to unused native/BML dependency sources leave stylesheet extraction and the source archive up to date.

Ran `./gradlew :bml:bml-compiler:test :bml:bml-gradle:test :bml:bml-sample:test --rerun-tasks --no-build-cache --console=plain` from the workspace root: 361 compiler tests, 33 Gradle tests, and 33 sample tests passed (427 total; no failures, errors, or skips), and all 71 scheduled tasks executed. The three focused regressions also passed with forced execution and no build cache. The original isolated reproduction now publishes successfully with both dependency Kotlin and BML sources present; archive inspection confirms their classes/components/sources are absent while authored sources and imported JavaScript/CSS remain. `git diff --check` and untracked-file whitespace checks passed. The standalone runtime suite, IDE, native, coverage, and full imported-component acceptance checks were not rerun for this source-discovery fix.

### Remaining implementation audit

- File-local imports and module-root `imports.bml`, optional aliases, unqualified lookup/overrides, cross-library collision/version/cycle diagnostics, and equivalent CLI generation are not implemented. The compiler and project generator still resolve components from local declarations.
- Manifest props retain authored type strings; resolved Kotlin type contracts and canonical component/DOM/client identities across library boundaries are not implemented.
- Automatic composition of imported renderers, actions, deferred renderers, serializers, and DI providers is not implemented. Ordinary compiled Kotlin models and direct renderer calls work, but the state/action/DI acceptance cases remain outstanding.
- Consumer asset discovery, dependency-closure selection, development/production page bundling, namespaced resource serving and markup asset URL resolution, global-theme ordering/overrides, and library-aware metrics/cache integration are not implemented. Producer asset packaging above does not replace these steps.
- Localization merging/conflict diagnostics across libraries and editable dependency reloads are not implemented.
- Dependency-aware IDE completion and Command-click navigation into the correct original BML and Kotlin sources are not implemented. Source attachments and tag folding are implemented.
- The Bosca BML Design Library, source inventory, component catalog, and independent BML application's import-based adoption are not implemented. This checkout has Studio Vue Git components; the intended extraction source still needs to be identified before claiming BML source parity.
- Full imported-component server/browser integration and native resource/serializer smoke acceptance have not run. Named slots/fallbacks and document layouts remain existing language limitations, not completed prerequisites.

### Initial producer validation

Ran successfully from the workspace root:

```bash
./gradlew :bml:core-bml:test --tests 'bosca.bml.library.*' :bml:bml-compiler:test :bml:bml-gradle:test :bml:bml-sample:test --console=plain
```

Results: 4 library metadata tests, 356 compiler tests, 20 Gradle tests, and 33 sample application tests passed (413 total; no failures or skips). The Gradle fixtures cover original source archives, custom source roots, configuration-cache reuse, an isolated Maven publication, regeneration after metadata deletion, rejection of missing source targets, and a separate binary consumer after removing the producer checkout. The sample validates existing pages and Kotlin state models. The locked runtime development dependencies were installed with `npm ci --no-audit --no-fund` for its client bundling.

These results validate the producer foundation only. IDE dependency navigation, library browser/runtime composition, action/serializer/DI integration, design-library parity, and native smoke acceptance are outstanding. No coverage report or coverage gate was run; test counts are not coverage. No release artifacts were published outside the temporary fixture repository.

### IDE tag folding validation

Tag folding is implemented for multiline paired HTML/BML tags, bare and namespaced components, native escapes, and script/style bodies. Opening and closing tags remain visible, regions start expanded, and nested regions support individual and all-region Collapse/Expand actions. Folding uses syntax alone, including in original read-only BML sources from a library sources JAR. Single-line and self-closing tags create no body fold; tag-like text in embedded bodies, attributes, and comments is ignored. Closing-tag edits invalidate the fold ranges.

Ran successfully from the workspace root:

```bash
./gradlew :bml:bml-ide:test :bml:bml-ide:buildPlugin --console=plain
```

Results: all 117 IDE tests passed, including 9 new folding tests (no failures or skips), and the plugin ZIP was built. The tests exercise the registered builder through IntelliJ's editor, standard folding actions, malformed markup, editing, and a real read-only sources JAR. No manual installation or live IDE UI check was performed. Library-aware completion/navigation remains outstanding; these folding tests do not establish that behavior.

### Code review and regression validation

Reviewed the current library producer, manifest, Gradle publication/source archive, and IDE folding changes. Fixed library manifest emission after module-level localization errors: both conflicting markup translations and conflicting function defaults now prevent new component metadata from surviving the failed BML compilation. Restricted the whole-module compilation workaround to the main BML source set so ordinary Kotlin test compilation retains its incremental behavior.

Extended the real Gradle publication fixture to verify that test compilation reuses the main renderer without overwriting its metadata, and that removing the sources attachment fails publication through Gradle's existing module-metadata validation. The compiler regression reproduces the localization failure before the fix and checks both localization paths after it.

Ran successfully from the workspace root:

```bash
./gradlew :bml:core-bml:test --tests 'bosca.bml.library.*' :bml:bml-compiler:test :bml:bml-gradle:test :bml:bml-sample:test :bml:bml-ide:test :bml:bml-ide:buildPlugin --rerun-tasks --no-build-cache --console=plain
```

Results: 4 library metadata tests, 357 compiler tests, 21 Gradle tests, 33 sample tests, and 117 IDE tests passed (532 total; no failures or skips). All 86 scheduled tasks executed, and plugin packaging succeeded. `git diff --check` passed. No coverage report, native smoke test, plugin compatibility verifier, or manual IDE installation/UI check was run. The outstanding import, runtime/browser composition, library-aware navigation, and design-library extraction work remains unchanged.

### Cache and publication review fixes

Fixed the clean-build cache gap: compiler-owned TypeScript now lives in `build/generated/bml/compiler-ts`, GraphQL TypeScript defaults to `build/generated/bml/graphql`, and each generator owns its output tree. Cache restoration includes compiler resources, TypeScript, metrics/CSS, and optional Kotlin/source maps. A real Gradle fixture restores `compileKotlin` from cache after cleaning, changes a GraphQL query independently, and verifies the resulting production browser bundle contains the updated query. It also repeats the clean build with configuration-cache reuse.

Library validation now reads the manifest and renderer classes from the packaged binary JAR and checks the matching original sources archive. The ordinary `jar` and `check` paths run validation. Each Maven publication also validates its selected binary and sources JARs before publishing, including custom replacement artifacts. Regression cases exclude the renderer, exclude the manifest, replace the publication JAR, remove source attachments, and change publication coordinates. Invalid publication attempts leave the previously published fixture artifact intact. Normal publication and configuration-cache reuse continue to pass.

Ran successfully from the workspace root:

```bash
./gradlew :bml:core-bml:test --tests 'bosca.bml.library.*' :bml:bml-compiler:test :bml:bml-gradle:test :bml:bml-sample:test :bml:bml-ide:test :bml:bml-ide:buildPlugin --rerun-tasks --no-build-cache --console=plain
```

Results: 4 library metadata tests, 357 compiler tests, 22 Gradle tests, 33 sample tests, and 117 IDE tests passed (533 total; zero failures, errors, or skips). All 91 scheduled tasks executed, plugin packaging succeeded, and `git diff --check` passed. The cache regression explicitly enables build-cache reuse inside its isolated fixture; the root invocation disables reuse of test results. No coverage report, native smoke test, plugin compatibility verifier, or manual IDE UI check was run. The broader import, runtime/browser composition, library-aware navigation, and design-library extraction work remains outstanding.

### Kotlin archive validation review fixes

Binary validation compares the presence and exact bytes of the selected JAR's entries with the library's main compilation outputs, including model classes, nested classes, generated renderer helpers, and Kotlin module metadata. Both ordinary archive validation and publication-specific validation use this inventory, so replacement publication artifacts must include the current outputs. A stale class with the same entry name fails validation before publication. Dependency JAR contents are not part of the producer's inventory.

Sources validation checks the presence and exact contents of original Kotlin files from the configured main Kotlin source set. It preserves source-relative paths, supports custom roots, honors compilation include/exclude rules, and excludes BML's generated source mirrors. The expected inventory is independent of source JAR filtering and replacement, so removing or altering an original Kotlin file fails publication.

The expanded publication fixture rejects missing models, helpers, nested classes, renderer support classes, module metadata, all Kotlin sources, individual custom-root sources, and altered source contents. Replacement binary and sources artifacts are checked independently. Valid publication, source edits with configuration-cache reuse, intentional source exclusions, and a consumer rendering after the producer checkout is deleted all pass.

Ran successfully from the workspace root:

```bash
./gradlew :bml:core-bml:test --tests 'bosca.bml.library.*' :bml:bml-compiler:test :bml:bml-gradle:test :bml:bml-sample:test :bml:bml-ide:test :bml:bml-ide:buildPlugin --rerun-tasks --no-build-cache --console=plain
```

Results: 4 library metadata tests, 357 compiler tests, 22 Gradle tests, 33 sample tests, and 117 IDE tests passed (533 total; zero failures, errors, or skips). All 91 scheduled tasks executed, and plugin packaging succeeded. The focused publication regression also passed separately. `git diff --check` passed. No coverage report, native smoke test, plugin compatibility verifier, or manual IDE UI check was run. Consumer imports, runtime/browser composition, library-aware navigation, and design-library extraction remain outstanding.

### Empty client-input build fix

The bundling task tracks prepared TypeScript as a file collection, allowing the preparation task to leave the directory absent when there are no client modules. Global assets still run through optimization and packaging, and removing the final client script still runs bundle cleanup. Adding client code back invalidates the empty input collection and rebuilds its bundles.

Two real Gradle regressions reproduced the missing-directory failure before the fix and pass afterward. They cover fresh and clean CSS-only JAR builds, optimized CSS contents inside the JAR, CSS edits, removal of the last client script with no global assets or GraphQL output, cleanup of development/production bundles and source maps, and adding client code back. Repeated builds reuse the configuration cache and retain up-to-date bundling when inputs are unchanged.

Ran successfully from the workspace root:

```bash
./gradlew :bml:core-bml:test --tests 'bosca.bml.library.*' :bml:bml-compiler:test :bml:bml-gradle:test :bml:bml-sample:test :bml:bml-ide:test :bml:bml-ide:buildPlugin --rerun-tasks --no-build-cache --console=plain
```

Results: 4 library metadata tests, 357 compiler tests, 24 Gradle tests, 33 sample tests, and 117 IDE tests passed (535 total; zero failures, errors, or skips). All 91 scheduled tasks executed, plugin packaging succeeded, and `git diff --check` passed. The two new regressions also passed in a focused run. No coverage report, native smoke test, plugin compatibility verifier, or manual IDE UI check was run. The remaining library import, composition, navigation, and extraction requirements are still pending.

## Verified planning baseline

- BmlAdditionalSourcesExtension collects component declarations from configured source roots, generates local component/page/island registries, and emits TypeScript plus asset and localization metadata. It has no dependency-library import path. BmlProjectGenerator independently implements local component discovery and must share the new resolver.
- BmlExtension declares packageName, but BmlGradlePlugin does not forward it to the compiler. BmlAdditionalSourcesExtension hardcodes bml.generated. Components are named from their tags, so separately compiled libraries need distinct generated packages and runtime identities.
- BmlCodeGenerator and BmlClientCodeGenerator support component-scoped scripts, attribute/event fall-through, live state, deferred renderers, and dependency metadata. BmlComponentInfo carries full and eager dependencies, session and feature-flag information, render revisions, styles, and client module names. Imported components must retain these semantics.
- Current asset production reads application-local generated TypeScript and metrics. Production combines page and component client modules into a page bundle. Runtime CSS, renderer, action, and deferred registries are supplied to BmlServer by the application.
- Only application projects receive the BML build-id resource. public/ is packaged under bml/public for existing message artifacts; this is not yet a general namespaced web-library asset contract.
- BmlDevTask currently places the application’s main output in the reload generation. Library output needs to join that generation when developing a project dependency. BmlComponentDecls searches project files and needs module/dependency visibility rules.
- Default child slots exist. Named slots currently warn and render default children; fallback slot bodies are not implemented. Template/layout declarations are reserved and do not supply document layouts.
- This checkout contains Studio Git components as Vue files under web/projects/studio/app/components/git; it contains no BML Git UI application. Confirm whether the intended source is that UI or a separate/newer BML Git application before extraction. The library mechanism does not depend on that answer.

## Proposed authoring and consumption

Use the existing io.bosca.bml Gradle plugin with an explicit library block. The following DSL and import declaration are proposed additions. Standard Kotlin/JVM library and Maven publication configuration still owns artifact publication. Workspace versions belong in gradle/libs.versions.toml; dependencies resolve through the root build without Maven Local.

```kotlin
// Producer: bml/bml-design/build.gradle.kts (proposed)
bml {
    packageName.set("bosca.design.generated")
    library {
        exports.set(listOf("button", "badge", "card", "card-header", "card-body"))
        styles.from("src/main/client/tokens.css")
    }
}

// Consumer: dependency version comes from the shared version catalog.
dependencies {
    implementation(libs.bml.design)
}
```

```bml
<import library="io.bosca:bml-design" as="ui"/>

<page route="/example">
  <html lang="en">
    <head><title>Library example</title></head>
    <body>
      <ui:card>
        <ui:card-header>Shared Bosca components</ui:card-header>
        <ui:card-body>
          <ui:badge tone="success">Ready</ui:badge>
          <ui:button type="button" disabled>Save</ui:button>
        </ui:card-body>
      </ui:card>
    </body>
  </html>
</page>
```

The dependency determines the version; markup contains only the group:artifact identity. A file-local import is a static declaration before page, route, message, or component declarations; project-wide imports use the dedicated file described below. The `as` attribute is optional in both locations. With `as="ui"`, the library's exports are available through qualified tags such as `<ui:button>`. Omitting `as` automatically makes all exported components available under their original tag names within the import's scope, including overrides of ordinary built-in HTML tags:

```bml
<import library="io.bosca:bml-design"/>

<page route="/example">
  <html lang="en">
    <head><title>Library example</title></head>
    <body>
      <button type="button">Design library button</button>
      <html:button type="button">Native HTML button</html:button>
    </body>
  </html>
</page>
```

Unqualified tag resolution follows this order:

1. Protected BML syntax, which cannot be overridden.
2. Components declared in the consuming project, following existing local resolution rules.
3. Exported components from the combined project-wide and file-local imports without `as`.
4. Built-in HTML/SVG handling under the existing namespace and SVG-context rules.

Thus `<button>` uses `bml-design` when it exports `button` and the consuming project has no local `button` override. `<html:button>` always selects the native element, including inside the library's own button implementation. An aliased import exposes only its qualified names and does not change unqualified lookup. Both forms expose only explicit exports; adding a Gradle dependency alone does not import tags.

Two different libraries imported without `as` cannot contribute the same tag name: this is a compile error identifying both libraries and import locations, regardless of declaration order or a local override. This rule applies across project-wide and file-local imports as well as within either scope. Use `as` on at least one import to distinguish them. Repeated unaliased imports of the same resolved library deduplicate; importing it both directly and under an alias is allowed and refers to the same components. An identical alias-to-library binding repeated across the project-wide and file-local scopes also deduplicates. `html`, `svg`, and `t` remain reserved namespaces. Duplicate alias declarations within one scope, aliases bound to different libraries across scopes, unresolved libraries, unknown exported tags, and protected-name conflicts are compile errors with source locations.

A library’s unqualified internal component calls resolve within that library. Cross-library calls use that library’s own imports. A consumer cannot change a library’s internal implementation by declaring a local component with the same tag. Only explicitly declared direct dependencies are importable by a consumer; transitive dependencies are still included for the library’s own rendering and assets. Re-export syntax is outside the initial release.

### Project-wide imports

Define shared imports in `src/main/bml/imports.bml`, at the root of the module's configured BML source directory. This is a dedicated imports file, containing only import declarations and comments. It is optional; projects without it use file-local imports as above. For example:

```bml
{# src/main/bml/imports.bml #}
<import library="io.bosca:bml-design"/>
```

Every page, route, message, and component source in that BML module can then use `<button>`, `<card>`, and the library's other exported tags without repeating the declaration. The existing render-target checks still apply when a message uses a web-only component. To make the same library globally available under a namespace, use this declaration instead:

```bml
{# src/main/bml/imports.bml #}
<import library="io.bosca:bml-design" as="ui"/>
```

Every BML source in that module can then use `<ui:button>`. The `as` and native-tag override behavior is identical to file-local imports. The dependency remains a normal declared Gradle dependency; the imports file does not resolve versions or add dependencies implicitly.

The compiler merges project-wide imports with each file's local imports before resolving tags. File-local imports add bindings; they do not silently replace a project-wide binding. Exact bindings repeated across those two scopes deduplicate, and conflicting bindings fail with both source locations. A file can add an alias for a globally imported library without removing its unqualified tags. Use `<html:button>` for native HTML and local component declarations for the established component override behavior. There is no import-order precedence or nested-directory inheritance.

Global means the current BML module, including its nested source directories, rather than every project in the Gradle workspace or every dependency. There is one designated `imports.bml` per BML module, resolved relative to the configured `sourceDir`; the build passes its path explicitly to compilation and applies it to all of that module's BML source roots. CLI/direct generation uses the corresponding module source-root convention and the same resolver. A library may use its own project-wide imports when compiled, but those bindings do not become global imports in a consuming project. Its internal dependency edges remain recorded in the library metadata. The dedicated imports file is excluded from page/component generation and retained in library sources for IDE context.

Track creation, edits, renames, and deletion of `imports.bml` as compilation, bundling, development-reload, and IDE-resolution inputs. Removing it removes the global bindings on the next successful rebuild/sync; stale bindings must not remain cached. The IDE reads the file even before a successful consumer compilation, using the existing resolved Gradle dependency model for library metadata and sources. Making a library globally visible does not eagerly evaluate its components or include all of its CSS/JS; asset planning still follows actual component references.

Library mode accepts components and their Kotlin code, including view models, model types, helpers, and providers, as well as client code, styles, assets, and localization. Routable pages, message entry points, and an application build ID do not belong in a component-library artifact. Examples and the component catalog live in a separate application. The publishing mechanism is general, but this first design library targets web UI; email/push reuse requires exports whose declared capabilities match those render targets.

## Artifact and build contract

Publish a normal JVM JAR plus standard Maven/Gradle dependency metadata. The JAR contains compiled Kotlin renderers, view models, helpers, serializers, and any generated provider/action/contract wiring; a versioned manifest at META-INF/bml/library-manifest.json; browser ESM modules; declared global/theme CSS; namespaced public resources; and localization metadata. Server code is compiled by the library producer; the consumer links compiled code rather than recompiling dependency BML or Kotlin sources.

Every library publication includes a matching sources JAR containing the original `.bml` and `.kt` files. Configure BML source inclusion explicitly; publishing Kotlin sources alone does not satisfy this contract. For each component, the manifest records its sources-artifact entry, declaration location, and source digest, associated with the resolved library identity and version. Preserve source-relative paths without producer-machine absolute paths. Publication validation checks that exported declarations and their navigation targets are present. Sources support editing tools and diagnostics and are not required to execute the compiled library.

- The manifest records schema version, group:artifact identity, artifact version, generated package/entry points, compiler and server/browser runtime protocol requirements, exported tags, all internal component IDs, prop contracts, default-slot support, render-target capabilities, source locations, dependency edges, eager edges, client modules, scoped styles, declared global styles, resource digests, localization entries, and render revisions. Existing BmlComponentInfo semantics are reused rather than replaced by a second runtime registry framework.
- Public prop contracts include resolved Kotlin type identity, nullability, required/default information, and declarations useful to the IDE. Generated callers perform the same static coercion/type checks as local components. Defaults execute in the producer renderer. Unknown attributes continue to follow BML’s established fall-through behavior; props are not conflated with HTML attributes.
- Build-time manifest extraction uses Gradle’s resolved dependency graph, including project substitution and normal artifact coordinates. The compiler receives explicit, tracked metadata inputs. Dependency classes are not loaded or discovered reflectively during compilation or server startup.
- Use one selected version per group:artifact in an application. BML library version conflicts fail with both dependency paths unless the consumer explicitly selects a version through ordinary Gradle dependency configuration. Identical diamond dependencies deduplicate. Dependency cycles, missing manifests/resources, unsupported manifest/protocol versions, and duplicate generated FQNs fail before deployment.
- The publisher emits browser ESM with its private npm dependencies resolved from its own package manifest/lockfile and bundled where appropriate. @bosca/bml remains an external runtime dependency so the consuming application bundles the supported runtime once per existing production page-bundle model. Consumers do not need a separate manually synchronized npm package for each BML library. Relative modules and assets must work after installation outside the producer checkout.
- Library schema/protocol support is checked explicitly and unsupported combinations fail with actionable diagnostics. Do not add internal legacy adapters or compatibility shims under this specification. Any need to support older published signatures or protocols must be discussed before choosing a migration policy; additive extension is required where a published SDK entry point is extended.

## Kotlin code and view models

A BML library is a Kotlin/JVM library with BML components. Its `src/main/kotlin` can contain ordinary classes, interfaces, enums, view models, and supporting functions used by its components. Components can construct library-owned models in `<script server provides="…">` or resolve them through existing `<inject>` and `<inject server>` behavior. Public Kotlin types are also usable by consuming Kotlin code and embedded server scripts through ordinary Kotlin imports; a BML `as` alias affects component tags, not Kotlin package names.

The producer applies the required serialization/compiler/KSP plugins and packages their generated output with the library. A serializable view model carries its serializer and generated server-action dispatchers through publication. Its methods, action arguments, state round-trips, and island re-rendering must work when the consumer has only the compiled dependency. The consumer must not need the model's source code to discover its methods or regenerate its serializers. Preserve existing page, client-session, client-local, and server-session behavior and distinguish model state from runtime service dependencies.

Publish normal compile/runtime dependency metadata for Kotlin dependencies. Types exposed by a public model, prop, or callable API must be available on the consumer compile classpath using the established public-API dependency configuration. Implementation dependencies remain owned by the library while being present where runtime execution requires them. Apply the same rules to generated contracts and any generated GraphQL client used by library Kotlin code; network operations retain the host's existing authenticated client and proxy behavior.

For DI-backed models, package the producer's generated `ProviderRegistrar` implementations and identify their entry points in the library metadata. Compose them by direct reference through the existing application/provider registration path, once per resolved library, before a component can request its model. The host supplies explicitly documented external bindings and configuration; importing a library must not silently replace host bindings or create another DI container. Required bindings and intentional overrides remain visible in the application composition. Constructor-created models need no DI setup. Native packaging includes the library's required resources and reachability metadata without adding reflective model discovery.

The design library may own reusable presentation models, such as dialog or selection state. The general library mechanism also supports richer reusable Kotlin behavior; the design library's separation from Git-specific data and permissions is a responsibility boundary, not a restriction on shipping Kotlin code.

## Identity, compilation, and runtime composition

Distinguish a file’s import spelling from a component’s canonical identity. Derive library component identity from stable group:artifact plus its declaration name, independent of whether the consumer supplies an alias and independent of checkout location. Qualified and unqualified references to the same component share that identity. Generate URL/DOM-safe encodings and detect collisions. Use that identity consistently in server registries, CSS scope markers, client registrations, island/action state keys, deferred renderer IDs, asset names, metrics, and render revisions. Library version/content participates in cache invalidation rather than changing identity through a consumer alias.

Honor packageName throughout compiler options and every generated registry/contract. Library mode requires a unique package or derives one deterministically from library identity. Keep application generated entry points usable; composition is generated at build time through direct references to the selected library renderers and metadata. The normal generated application registries include the reachable imported components and their private/transitive dependencies. No per-library manual list or runtime classpath scan is needed.

A page’s component closure includes statically referenced components in branches and nested calls, including deferred dependencies and caller-owned slot content. It need not perform runtime branch elimination. Preserve eager versus deferred dependency information so importing a session-backed or identity-dependent component cannot bypass the existing shared-cache shell rules. Server actions and fragment/deferred requests use the same identity, session, authorization, and serialization paths as local components. A library dependency does not grant new application permissions or register arbitrary routes.

## Browser assets, themes, and localization

Extend the existing asset planner, bundler, metrics, and server resource wiring to consume the same resolved library graph. Load the CSS and browser modules of the page’s reachable component set; importing a library globally or locally, with or without `as`, does not load the whole component catalog. Private dependencies count toward the closure. Declared library-global CSS loads once when at least one library component is reachable in the application, in deterministic dependency order before application overrides. A globally imported but unused library contributes no browser assets. Document and measure the global CSS cost for participating libraries.

The design library publishes Bosca CSS custom properties for color, typography, spacing, radius, and focus styling. Its shared rules live in real CSS files, with component styles scoped. Global CSS uses a documented library-owned scope/layer and does not replace host resets or page shells implicitly. Application token overrides are explicit. Scoped styles do not constitute a security boundary or full isolation from global CSS.

Package fonts, images, and other public files under a library namespace. Add a library-scoped asset reference resolved at build time or by a generated helper; rewrite relative CSS url() references and emit version/content-addressed same-origin asset URLs. Include these resources in JAR/native packaging. Equal filenames from two libraries must coexist. A consumer requires no hand-copied public files or new CDN configuration.

Merge imported localization manifests into the consumer’s extraction and validation path. Preserve library-owned string keys and source context, deduplicate identical definitions, and report conflicting definitions. Render using the host’s existing locale, catalog, fallback, and request identity behavior; do not create a second localization service.

## Component API and first design-library release

Use existing typed props, default child slots, native attribute fall-through, declarative events, and component-scoped client lifecycles. Components accept presentation data and caller-owned actions. Keep visual variants and accessibility behavior consistent across consumers. Native HTML semantics, labels, keyboard behavior, focus handling, reduced motion, and disabled/error states are part of the component contract.

The initial composition model uses default slots and composed subcomponents such as card-header/card-body. Do not publish APIs that rely on named slots behaving as independent regions while the compiler still renders the default slot. If the confirmed Git UI inventory needs named slots or fallback content, record the concrete cases and approve that language work before committing those APIs; it must then be implemented and tested before shipping those components.

- Foundation: Bosca tokens, typography, layout spacing, focus styles, and existing brand/icon assets with their provenance recorded.
- First reusable set, finalized against the actual Git UI source: button/link variants, badge/status indicator, card/panel composition, field/label/error/help text, input/textarea/select/checkbox controls, tabs, dialog/confirmation composition, empty/loading/error states, and basic list/table/pagination presentation.
- Extract shared behavior as well as markup: keyboard interactions, focus return, form semantics, client setup/disposal, and consistent state styling.
- Git-specific repository/branch/commit/diff data, permissions, API calls, route navigation, and workflow actions stay in the Git application. A generic tree or code viewer may be considered after the core component inventory proves a reusable contract; it is not required to port Git domain screens.
- If the source is Studio’s Vue UI, port the selected presentation and behavior contracts into BML and verify parity; Vue sources cannot be moved unchanged into the BML artifact. If a separate BML Git app is the source, extract its components directly and remove the migrated copies only after the consumer uses the library successfully.

## Local development, tooling, and documentation

Project dependencies use the same manifest and asset contract as published dependencies. Changes to library BML, Kotlin helpers, client code, styles, resources, exports, dependency metadata, or project-wide imports invalidate the appropriate consumer tasks. bmlDev includes editable library outputs in the isolated reload generation rather than leaving stale classes in its parent loader. A failed rebuild retains the last working generation; removals clean stale output. Libraries remain build outputs, not long-running tasks.

IntelliJ uses the current module’s resolved project-wide and file-local imports and dependency metadata for component/prop completion, diagnostics, and source navigation. It follows the same qualified and unqualified lookup rules as the compiler: with an unaliased design-library import in `imports.bml`, navigation and prop completion on `<button>` work in every BML source without a local import and resolve to the library component unless a local component overrides it. It must not suggest unrelated components from another application merely because both projects are open. CLI compile/dev paths and direct project generation use the same component resolution contract as the Gradle compiler path.

Command-click / Go to Declaration on either `<button>` or `<ui:button>` must open the original `.bml` file at its `<component tag="button">` declaration. Support references from opening, closing, and self-closing tags. For a project dependency, open the editable producer source. For a published dependency, resolve and attach the matching sources JAR through the IDE's Gradle dependency model and open the original `.bml` as a read-only library source. Generated Kotlin, decompiled bytecode, and the manifest are not substitutes for that navigation target. Embedded Kotlin references in that source must resolve to the library's own Kotlin classes and dependencies, allowing navigation onward to a view model's original `.kt` source or its inline BML declaration.

Navigation uses resolved module identity, version, and source entry rather than a global first match for the short tag name. A local override navigates locally; `<html:button>` remains native. Changing the dependency version or import alias invalidates the relevant navigation/completion cache, and a matching source digest prevents showing an unrelated source revision. If sources are unavailable offline or cannot be downloaded, retain metadata-based completion and show a source-unavailable result with the normal source-download/retry action; do not navigate to another library, another version, or a generated class as though it were the original BML. Building and running from the binary artifact remain possible.

The IDE supports collapsing and expanding multiline paired tag bodies through gutter controls and standard folding actions. Nested HTML, BML declaration/control tags, bare imported components such as `<button>`, namespaced components such as `<ui:button>`, native escapes, and script/style bodies fold independently. Keep opening and closing tags visible and leave regions expanded by default. Folding works directly from BML syntax before dependency resolution and in original library sources. Single-line bodies and self-closing tags create no body fold. Comments, attribute values, and embedded code must not introduce false tag pairs; incomplete edits must not produce crossing fold ranges, and folds update when matching tags change.

Add a component catalog BML application with examples, prop/default descriptions, states, themes, keyboard instructions, and composition recipes. Document producer publication and consumer setup for both workspace project dependencies and a published artifact. New project scaffolds should demonstrate the minimal setup without hand-wiring individual library registries.

## Implementation ownership and sequence

1. Resolve the Git UI source and record the extraction inventory; settle the first component APIs and any named-slot prerequisite. Implement project-wide and file-local imports with optional `as` according to the agreed behavior above.
2. Implement artifact schema, producer library mode, package identity, Gradle dependency inputs, and export validation in bml/core-bml, bml/bml-compiler, and bml/bml-gradle.
3. Implement library imports, typed component resolution, canonical IDs, and generated registry composition across compiler/project-generation/CLI paths.
4. Integrate ESM/CSS/resources/localization with bml/bml-runtime, bml/bml-server, metrics, and packaging; add native reachability/resource metadata through established mechanisms.
5. Complete workspace development reloads, IDE dependency resolution, and published-artifact integration fixtures.
6. Add the proposed bml/bml-design module and separate catalog application in the root workspace map. Extract the approved Git components and adopt them in one independent BML application (Profiles is a candidate, not an assumed source migration). Keep Profiles version pins on an available published release until a compatible BML release exists.
7. Publish through the existing Bosca artifact/release path after validation; document available coordinates and migration steps.

## Acceptance and validation

- A two-library test fixture and an application compile through real Gradle tasks using both project dependencies and JARs published to an isolated temporary Maven repository. The consumer can build/run without the producer checkout. No publishToMavenLocal is needed.
- Two libraries export the same short tag with different aliases on one page. SSR, client setup, styles, state, actions, fragments, deferred renderers, and resources remain distinct. Alias changes do not change canonical identity. A shared transitive dependency loads once.
- Importing `bml-design` without `as` makes `<button>` render its exported button, while `<html:button>` renders native HTML. A local `button` component takes precedence over the imported one. An aliased import alone leaves unqualified lookup unchanged. Two different unaliased libraries exporting the same tag fail regardless of import order; aliasing one resolves the conflict. Protected BML syntax cannot be overridden. Importing one library both ways shares component registration and assets, with ordinary per-instance state semantics retained.
- Declaring a library once in module-root `imports.bml` makes its exports available in nested pages and components with no file-local declarations. Verify both unqualified and aliased global imports, merging with file-local imports, duplicate binding deduplication, cross-scope collision diagnostics, custom source directories, and absence of leakage into sibling projects or dependency implementations. A global import alone includes no unused component assets. Adding, changing, and deleting the imports file updates compilation, CLI generation, development reloads, completion, and Command-click navigation; a project without the file retains ordinary local imports.
- Imported props, defaults, nullability, static literals, bound expressions, slots, Boolean attributes, data/ARIA attributes, and declarative event fall-through match local-component behavior. Missing exports, private imports, invalid namespaces/types, and conflicting versions produce source-located failures.
- Using one interactive component includes its complete transitive CSS/JS/asset closure and excludes unrelated component modules; the BML runtime is not duplicated within a page bundle. Styles, client modules, resources, and transitive implementation changes invalidate render/cache revisions. bmlMetrics accounts for library-global and per-page bytes.
- Imported session and feature-flag capabilities enforce existing shared-cache restrictions. Deferred rendering, actions, sign-out behavior, page/site state lifetimes, and slot ownership retain their existing semantics. Supported message targets reject incompatible web components at build time.
- A published library fixture contains a Kotlin view model, a dependency used by that model, generated serialization, and a component that invokes a model action and re-renders its island. A consumer with no producer source checkout renders it, round-trips state, and executes the action on the JVM and in a native smoke test. Cover direct model construction and DI-backed construction through generated provider registration, with a documented host-supplied dependency and no manual registration of individual model actions. A public library model type is usable from consumer Kotlin code and as a typed component prop.
- IDE tests resolve opening, closing, and self-closing imported tags to the actual `.bml` declaration for both import forms. Cover editable project sources and a real attached sources JAR with no producer checkout, then navigate from the component to its Kotlin view model source. Verify local overrides, native escapes, two libraries with the same short tag, dependency-version changes, and missing sources; assert the target file content and declaration, not merely that a reference is non-null.
- IDE folding tests exercise the registered editor integration and Collapse/Expand actions for nested multiline tags, both library tag spellings, native escapes, declarations/control flow, and script/style bodies. Verify single-line/self-closing exclusions, tag-like text in opaque regions, malformed markup, and removal/restoration of closing tags during editing.
- Fresh builds, cached builds, dependency updates, deleted exports, and local development edits produce correct output. A real bmlDev test modifies a library and observes the updated server/client generation. Published-artifact and JVM/native sample runs exercise actual registry/asset wiring; mock-only tests are insufficient.
- Catalog browser tests cover representative controls, keyboard navigation, focus management, repeated component instances, mounting/unmounting, responsive layouts, and theme overrides. Review visual/behavioral parity against the confirmed Git source and verify the same components in the independent consumer.
- Run focused compiler, Gradle TestKit, runtime bundler, server integration, IDE, and consumer checks. Use the real Netty test-server pattern without calling NettyServerEngine.stop(). Where database wiring is exercised, run the relevant Testcontainers fixtures with required infrastructure. Report measured results and any applicable line/branch coverage gates; this draft does not claim tests have run.

## Review decisions and scope boundaries

- Confirm the Git UI source location/revision. This determines direct BML extraction versus a Vue-to-BML port and prevents speculative component-parity claims.
- Import behavior is agreed: `as` creates a namespace; omitting it imports exported tags directly and allows them to override ordinary built-in HTML tags. Shared imports can be defined once for the BML project in `imports.bml`. Review the proposed artifact name, producer DSL, and first component inventory before implementation.
- Determine whether the selected components require named slots/fallbacks. The first release uses default-slot composition unless that additional language work is explicitly accepted.
- Initial packaging targets the existing Kotlin/JVM BML application toolchain and GraalVM application deployment. A general KMP binary-library format, runtime plugin installation, automatic route imports, a new package registry, live production library hot-swapping, a broad Studio rewrite, and side-by-side versions of one library are outside this specification.

## Implementation requirements

The following eight requirements track implementation. The artifact/publication and IDE/development requirements are In Progress; the first producer implementation and IDE tag folding are complete. Remaining artifact work includes cross-library identities/contracts, provider entry points, and browser/resource packaging. IDE dependency resolution and development reloads remain outstanding. The other requirements are To Do.

### 1. Define BML library artifacts and Gradle publication

Add explicit library mode to the existing BML Gradle plugin and a versioned library manifest with compiled renderer references, prop/export contracts, dependency/capability metadata, browser modules, CSS, assets, localization, provider entry points, and source locations. Package ordinary Kotlin classes and view models, their serializers/generated wiring, and normal compile/runtime dependency metadata. Publish a matching sources JAR with original `.bml` and `.kt` files and map component declarations to exact source entries and digests. Honor packageName across generated outputs and prevent package/entry-point collisions. Use standard Maven coordinates and project substitution; do not introduce a separate registry or Maven Local workflow.

Acceptance: producer JAR and source attachment contain the declared outputs and original sources; components and Kotlin view models compile without an application entry point; public model/prop types are available to consumer Kotlin; application routes and build IDs are absent from the library artifact; a fresh consumer resolves the artifact and its dependencies from an isolated Maven fixture without the producer checkout; source declarations map to the matching artifact version; malformed/missing resources and unsupported manifest/protocol versions fail clearly; dependency versions remain in the shared catalog.

Producer archive validation checks exact compiled class/module-metadata bytes and original Kotlin source contents. A replacement binary from an older compilation fails before publication even when all expected entry names are present; a replacement built from the current outputs remains valid.

Standalone client files and public resources independently require a client manifest in the published binary, including when no component has an inline script or the bundling task is skipped. A bundler that exits successfully without producing fresh library output fails before publication; output from an earlier invocation cannot satisfy that check.

Installed-package ownership follows canonical package directories even when the installation or its scope directory is a symlink. Imported private code and resources stay bundled; their source files remain excluded from the attachment whether the package store is outside or inside the producer project.

Installed npm trees are excluded from the main Kotlin source set and BML declaration discovery, including when installed beneath a configured BML source directory. Original-source validation follows the same filtered Kotlin source set. Authored Kotlin models beside BML files remain compiled and attached; dependency native code, build scripts, and BML examples are excluded.

### 2. Resolve library imports with optional namespaces and component contracts

Implement `<import library="group:artifact" as="alias"/>` and `<import library="group:artifact"/>` both in individual source files and in a dedicated module-root `imports.bml`, through a shared resolver used by compiler, direct generator, and CLI paths. The dedicated file supplies imports to every BML source in that module. `as` exposes qualified component names; omitting it automatically imports all exported tag names into unqualified lookup ahead of ordinary built-in HTML tags and after local components. Merge global and local bindings without import-order overrides, deduplicate identical bindings across scopes, and diagnose conflicts with both source locations. Preserve protected syntax and native namespace escape hatches. Exports are explicit; private/internal calls and library-owned global imports remain bound to their producer module.

Acceptance: an unaliased design-library import makes `<button>` select the exported component; `<html:button>` selects native HTML; a local component takes precedence; an aliased import alone does not affect unqualified lookup. Two different libraries exporting the same unqualified name fail regardless of import order, and adding an alias resolves the conflict. Qualified and unqualified imports of the same library share canonical identities and deduplicate registration/assets. Private/unresolved exports, duplicate aliases/FQNs, protected-name conflicts, cycles, and conflicting versions fail with dependency/source context. Typed props/defaults/nullability and ordinary attributes match local behavior; private transitive dependencies resolve within their owner; imports do not expose routes.

Global-import acceptance: one declaration in `imports.bml` supports pages and components in nested directories with no repeated imports. Test global aliases, additive file-local imports, exact binding deduplication, cross-scope conflicts, custom source roots, and isolation between modules and library internals. The dedicated file accepts only imports/comments and generates no renderer. Removing it clears global bindings; projects without it still support local imports. Global visibility alone does not evaluate components or pull unused browser assets into a page.

### 3. Compose imported renderers, live state, and deferred behavior

Generate application component, action, and deferred registries from the resolved library graph using direct references and canonical IDs independent of aliases. Carry eager/full dependencies, session/feature-flag capabilities, targets, and render revisions through the compilation and server paths. Support library-owned Kotlin view models, their compiled serializers and actions, and producer-generated DI registrars composed through the existing application registration path. Reuse existing renderer and request/session infrastructure.

Acceptance: SSR, fragments, declarative events, repeated instances, page/site state, deferred rendering, and sign-out behavior work across library boundaries; a published Kotlin view model renders, round-trips state, and handles actions without consumer-side source scanning or serializer regeneration; constructor-created and DI-backed models work with documented host bindings and preserve duplicate-provider checks; imported identity-dependent behavior cannot bypass shared-shell validation; unsupported message targets fail during compilation; no reflection scan or manual registration of individual library renderers/actions is required; actual server integration tests exercise the wiring.

### 4. Bundle library client code, styles, resources, and localization

Package browser ESM and private npm dependencies with @bosca/bml external, then integrate dependency modules into existing development and production bundling. Resolve scoped CSS, library-global tokens, public resource references, localization entries, and cache/metrics data using the same component graph. Namespace resources and canonical DOM/client identities.

Acceptance: reachable private/transitive assets load; unrelated component modules do not; equal asset filenames and short tags do not collide; one page bundle uses one BML runtime; tokens load once in documented order before host overrides; all relevant changes invalidate caches; a consumer needs no copied assets or separate manually synchronized npm library; localization conflicts are diagnosed and host locale semantics are retained; JVM/native artifact tests verify packaged resources.

Producer status: generated inline modules and recursive standalone client sources are packaged as ESM with private dependencies and shared chunks; the BML runtime stays external. Inline CSS is bundled before component scoping, referenced resources receive library namespaces, original asset sources are attached, and final binary/source archives are validated. Consumer selection/linking/serving, global-theme ordering, cross-library localization, metrics/cache composition, and native acceptance remain pending.

Publication regressions cover TypeScript-only, CSS-only, and public-resource-only libraries with static components. They reject tools that ignore library arguments, verify successful packaging with the current bundler and configuration-cache reuse, reject an unsupported bundler after a successful build, and reject publication when bundling is disabled. Rejected builds leave previously published artifacts intact.

Linked-installation regressions cover entire `node_modules` directories and scope directories. Runtime cases check external and project-local stores, package JavaScript/CSS/images, source exclusion, and execution after removing producer sources and installed packages. Real publication cases verify inline and standalone modules, original source attachments, JavaScript/CSS edits, link retargeting, and configuration-cache reuse with unchanged bundling remaining up to date.

Producer regression coverage includes comment-prefixed CSS at-rules, separate standalone and script-generated stylesheet outputs, local/hoisted linked npm packages, browser-disabled JavaScript/CSS dependencies, data-URL imports, symlinked source roots, and symlinked public files/directories. Virtual modules are excluded from source inventories while missing real dependencies still fail packaging. The publication fixture verifies linked JavaScript and CSS edits rebuild with configuration-cache reuse and that the binary remains usable after removing the producer, linked source/assets, and npm dependency.

Three publication regressions verify npm dependency edits beside an external symlinked BML root, beside a linked component subdirectory, and above a linked npm package. JavaScript and CSS changes independently update the published archive with configuration-cache reuse. Retargeting a link changes dependency lookup even when its source files are unchanged and both destination trees are already installed; a subsequent unchanged build keeps both bundling tasks up to date.

Resolver configuration regressions cover ordinary subprojects and external BML source trees. Parent package import mappings, inherited JSONC configuration edits, and creation/removal of shared TypeScript configuration update the published module's executed behavior. Browser tests verify inline npm and package imports select the same source-side dependency as adjacent helpers, including when the producer installs a different copy; generated imports and the external BML runtime remain usable.

Source-context regressions verify direct inline CSS package imports from linked BML files, roots, and component directories, including a competing producer-side package. Inline TypeScript and adjacent helpers use their own source-local compiler settings. Browser tests cover inherited settings, simultaneous components with different settings, distinct generated modules for source aliases, generated contract imports, and execution after deleting original sources. Publication tests change source-local class-field settings and verify the new behavior in archived modules with configuration-cache reuse.

Nested npm regressions verify that dependency directories are excluded from authored entry discovery and source archives, including inherited source-set inputs. Imported JavaScript, CSS, and images remain packaged; unused Node utilities, unused invalid stylesheets, and internal dependency sources do not. Publication tests cover dependencies beneath BML and nested client source directories, dependency edits with configuration-cache reuse, unchanged bundling remaining up to date, and execution after deleting the producer. Standalone sources-JAR tests retain original BML, Kotlin, TypeScript, and CSS without compiling the library and remain up to date after dependency-only edits.

Compiler and stylesheet-extractor regressions exclude dependency BML examples from direct and nested npm installations while retaining similarly named authored directories. The publication fixture also includes unused Kotlin classes, Kotlin build scripts, and BML components with invalid CSS imports. They produce no classes, manifest declarations, source entries, or extracted stylesheet inputs; edits to them leave stylesheet extraction and the source archive up to date. Authored Kotlin models under the BML source root remain available, and imported dependency JavaScript/CSS edits still rebuild the published browser assets.

Project-wide import acceptance: a library declared in `imports.bml` but never referenced contributes no CSS/JS. Referencing one exported component includes its reachable dependencies and the library's declared global CSS, with the same accounting and deduplication as a file-local import.

### 5. Support library development and IDE dependency awareness

Connect all library source/metadata/resource changes and creation/edits/removal of `imports.bml` to Gradle inputs and include editable project dependencies in bmlDev reload generations. Extend IntelliJ completion, validation, and Command-click / Go to Declaration to resolve the combination of global and file-local imports against the current module and its dependency manifests/sources. Open the component's original `.bml` declaration and support navigation from embedded Kotlin to library view-model source. Resolve published sources from the exact dependency version through Gradle source attachments. Keep CLI and Gradle resolution equivalent.

Add tag folding through the IDE's gutter and standard Collapse/Expand actions for multiline paired HTML/BML tags, bare and namespaced component tags, native escapes, and script/style bodies. Keep opening/closing tags visible, start expanded, and support nested regions independently without requiring imports to resolve.

Acceptance: editing library Kotlin, BML, client scripts, CSS, assets, or exports updates the running consumer after a successful rebuild; failed builds retain the working generation; removing components clears stale output. Command-click on opening, closing, and self-closing `<button>` and `<ui:button>` tags opens the correct original `.bml` declaration for project and published dependencies; navigation continues to the library view model's original Kotlin source. Tests use an attached sources JAR without a producer checkout and cover overrides, native escapes, collisions, dependency-version changes, and unavailable sources. Unrelated applications and stale dependency versions do not pollute completion/navigation; missing sources produce a clear retry path while metadata completion and binary builds remain usable. Real development-reload and IDE tests cover the behavior.

Global-import acceptance: completion and source navigation work on a page with no local import when its library is declared in `imports.bml`, including before successful consumer compilation. Editing/removing the global import immediately invalidates IDE bindings and invalidates the appropriate build/reload inputs. No stale global symbols or bindings from other modules remain.

Folding acceptance: editor tests verify individual and all-region collapse/expand actions, nested and repeated tag names, both library import spellings before resolution, declarations/control-flow clauses, and embedded script/style bodies. Single-line and self-closing tags create no body fold; tag-like text inside comments, attributes, and embedded code creates no false folds. Incomplete/mismatched markup produces no crossing ranges, and editing a closing tag removes/restores its fold. Original `.bml` library sources use the same folding behavior.

### 6. Extract the Bosca BML Design Library and catalog

First confirm the Git UI source/revision and record an inventory that maps each selected component to its source, props, behavior, styling, dependencies, and reuse decision. Implement the approved generic UI set and Bosca tokens in the proposed bml/bml-design module, with a separate BML catalog. Keep Git models, routes, permissions, and API calls in their owner application.

Acceptance: source-level visual/behavioral parity is documented for migrated components; component APIs use typed props, declarative actions, scoped lifecycle behavior, and supported child composition; keyboard/focus, responsive layout, disabled/error/loading states, and theme overrides are demonstrated and checked. Named slots/fallbacks require an explicit language prerequisite if the inventory needs them. Studio Vue components require a deliberate BML port; do not claim a BML Git source exists in this checkout.

### 7. Adopt the library in Git and an independent BML consumer

If the Git source is BML, migrate its selected components to library imports. If the source is Vue, demonstrate the selected Git UI compositions in the BML catalog and compare them with the original; migrating the Vue host is separate work. In either case, adopt a representative set in an independent BML application after selecting it. Configure the shared design library once in that application's `imports.bml` and demonstrate pages without repeated declarations. Cover default-slot composition, interactive controls, theme overrides, and shared assets. Document both workspace and published dependency setup, with project-wide and file-local import examples.

Acceptance: each participating BML application declares the library dependency and imports components without copying implementations or hand-registering library internals; source-specific behavior/authorization remains in its application; migrated duplicates are removed only after parity checks; the independent consumer builds from a published fixture without a sibling checkout; available release pins and deployment paths are respected. A full Studio-to-BML rewrite is not part of this requirement.

### 8. Verify publication, integration, and release documentation

Build the two-library/application TestKit fixture, published-artifact fixture, actual server/browser integration checks, and native packaging smoke case described in the specification. Review the extraction inventory against implemented behavior and exercise the design catalog plus the participating BML applications. Publish usage and release documentation after the implementation meets the measured bar.

Acceptance: compiler, Gradle, runtime bundler, server, IDE, and consumer checks cover collisions, private/transitive dependencies, typed contracts, global/local import composition, cache invalidation, dependency and global-import changes/removals, and accessibility interactions. Record exact commands/results and applicable line/branch coverage gates. Do not replace integration evidence with mock-only tests, passing test counts, or screenshots of happy paths. The planning task itself does not publish artifacts or run these future checks.

## Source evidence

- [Compiler source collection and generated registries](../../bml/bml-compiler/src/main/kotlin/bosca/bml/compiler/plugin/BmlAdditionalSourcesExtension.kt)
- [Compiler options](../../bml/bml-compiler/src/main/kotlin/bosca/bml/compiler/plugin/BmlCommandLineProcessor.kt)
- [Server component generation](../../bml/bml-compiler/src/main/kotlin/bosca/bml/codegen/BmlCodeGenerator.kt)
- [Client component generation](../../bml/bml-compiler/src/main/kotlin/bosca/bml/codegen/BmlClientCodeGenerator.kt)
- [Direct project generation](../../bml/bml-compiler/src/main/kotlin/bosca/bml/codegen/BmlProjectGenerator.kt)
- [Component metadata](../../bml/core-bml/src/main/kotlin/bosca/bml/render/BmlComponentInfo.kt)
- [Renderer contract](../../bml/core-bml/src/main/kotlin/bosca/bml/render/BmlComponentRenderer.kt)
- [Gradle extension](../../bml/bml-gradle/src/main/kotlin/bosca/bml/gradle/BmlExtension.kt)
- [Gradle integration](../../bml/bml-gradle/src/main/kotlin/bosca/bml/gradle/BmlGradlePlugin.kt)
- [Development reload generations](../../bml/bml-gradle/src/main/kotlin/bosca/bml/gradle/BmlDevTask.kt)
- [Browser bundling](../../bml/bml-runtime/tools/bundle.mjs)
- [Server composition](../../bml/bml-server/src/main/kotlin/bosca/bml/server/BmlServer.kt)
- [IDE component lookup](../../bml/bml-ide/src/main/kotlin/bosca/bml/ide/BmlComponentDecls.kt)
- [IDE component reference](../../bml/bml-ide/src/main/kotlin/bosca/bml/ide/BmlTagNameElement.kt)
- [Existing component navigation tests](../../bml/bml-ide/src/test/kotlin/bosca/bml/ide/BmlReferenceTest.kt)
- [IDE tag folding](../../bml/bml-ide/src/main/kotlin/bosca/bml/ide/BmlFoldingBuilder.kt)
- [IDE folding tests](../../bml/bml-ide/src/test/kotlin/bosca/bml/ide/BmlFoldingTest.kt)
- [Existing Kotlin view model](../../apps/profiles-web/src/main/kotlin/bosca/profiles/web/DevicesModel.kt)
- [Provider registration](../../services-di/di/src/commonMain/kotlin/bosca/di/ProviderRegistry.kt)
- [BML grammar](../../bml/docs/grammar.md)
- [Studio Git tree component](../../web/projects/studio/app/components/git/ChangedFileTree.vue)
- [Profiles BML component example](../../apps/profiles-web/src/main/bml/components/shared/toast-message.bml)
