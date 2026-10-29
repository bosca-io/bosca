# Bosca Markup Language

BML compiles HTML-like `.bml` sources with embedded server Kotlin and client TypeScript into
server-rendered pages, interactive islands, reusable components, and email or push message templates.

- [Getting started](docs/getting-started.md)
- [Grammar](docs/grammar.md)
- [Tag reference](docs/tag-reference.md)
- [Islands, deferred rendering, and shared caching](docs/islands.md)
- [Compiled component libraries](docs/libraries.md)
- [Compiled library providers and host bindings](docs/library-providers.md)
- [Compiled library localization](docs/library-localization.md)
- [Compiled library client types](docs/library-client-types.md)
- [Design library components](bml-design/README.md)
- [Shared authentication UI](bml-auth-ui/README.md)
- [Design library catalog](bml-design-catalog/README.md)
- [Design library consumer example](examples/design-library/README.md)
- [Examples](docs/examples/README.md)
- [Performance benchmarks](docs/benchmarks.md)

The workspace's root Gradle build includes the BML compiler, runtime, server, message tooling,
Gradle plugin, sample application, and IntelliJ plugin. Dependency versions are in
[gradle/libs.versions.toml](../gradle/libs.versions.toml).

Run from the workspace root:

```bash
./gradlew :bml:test
./gradlew :bml:bml-compiler:test :bml:bml-gradle:test
./gradlew :bml:bml-ide:buildPlugin
```

The IntelliJ plugin is documented in [bml-ide/README.md](bml-ide/README.md).
The TypeScript browser runtime lives in `bml-runtime/` and uses npm; install its dependencies
there before running tasks that bundle BML client code.

The BML Gradle-plugin suite runs independent TestKit classes in two isolated test JVMs and
logs completed tests. Fixture builds that edit files between invocations disable filesystem
watching while retaining configuration-cache and up-to-date checks.

The Design Library catalog, consumer and Notifications Web tests launch isolated headless profiles.
Gradle prepares Chromium through the catalog's pinned private Playwright dependency before these
tests, so a system Chrome installation is unnecessary. The first run requires network access;
later runs reuse Playwright's browser cache. Set `BML_DESIGN_CHROME_BINARY` to use an existing
Chrome executable and bypass browser preparation. BML test failures print their full exception
messages, including child-process diagnostics, directly in the Gradle log.
The JVM CI pipeline installs Chromium through the catalog's pinned test dependency and exports
its executable path for both suites. It does not depend on a system Chrome installation.
Browser suites, including Notifications Web adoption, run in a dedicated phase before aggregate JVM
validation. Within the catalog and Design Library consumer suites, independent test methods run in
a pool sized to Gradle's worker budget while test classes remain serial. Independent suites can run
in parallel. Each method owns its server, browser profile and cookies; focused checks use distinct
report names. Set `-Pbml.browser.workers=1` to run methods
serially, or choose another pool size for the available machine. CI's later JVM phase excludes these
suites so they run once even when that phase uses a different Gradle worker count.

Run the CI browser phase from the workspace root with
`./gradlew -Pbosca.ios.enabled=false --parallel --max-workers=4 testBmlBrowser`.
This builds independent suites in parallel, then runs chunk-cache, Notifications Web,
Design Library consumer and catalog tests in sequence. Catalog test completions appear
in the Gradle log. Local choices, feedback and surfaces run in their dedicated JVM
cases; native catalog qualification retains them in its single browser entry point.

`./gradlew :bml:bml-server:chunkCacheBrowserTest` checks module requests across two deployments
with unchanged JavaScript and a retained browser cache, for both filesystem and packaged assets.
It covers shared, nested, lazy and compiled-library imports and runs in the CI and release browser
phase. It uses the same managed browser and `BML_DESIGN_CHROME_BINARY` override described above.
The browser stays open across deployments and waits for module completion through DevTools.
Browser logs remain under `bml/bml-server/build/reports/chunk-cache-browser/` for diagnostics.

BML Maven libraries and the `io.bosca.bml` and `io.bosca.bml.host` Gradle plugins publish with the workspace tag through
the root `publishExceptFirebaseScrypt` task. This includes `bml-server`, which applications embed
as their host library. The web release pipeline builds, tests, and publishes the `@bosca/bml`
browser package at the same tag version. Compiled `bosca-messages` projects publish separately
to the `bml-message` raw-artifact namespace on workspace tags or manual runs. All three paths
use the configured Bosca Artifacts registry.

Client bundling resolves Node from `PATH`, then the standard Homebrew locations. For another
installation, set `bml.nodeExecutable=/absolute/path/to/node` in your Gradle user properties
or configure `bml { nodeExecutable.set("/absolute/path/to/node") }` in the application build.
