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

The Design Library catalog, consumer and Notifications Web tests launch isolated headless profiles.
Gradle prepares Chromium through the catalog's pinned private Playwright dependency before these
tests, so a system Chrome installation is unnecessary. The first run requires network access;
later runs reuse Playwright's browser cache. Set `BML_DESIGN_CHROME_BINARY` to use an existing
Chrome executable and bypass browser preparation. BML test failures print their full exception
messages, including child-process diagnostics, directly in the Gradle log.
The JVM CI pipeline installs Chromium through the catalog's pinned test dependency and exports
its executable path for both suites. It does not depend on a system Chrome installation.
Browser suites, including Notifications Web adoption, run in a serial phase before aggregate JVM
validation. The aggregate task reuses those results, and its worker count is bounded independently.

BML Maven libraries and the `io.bosca.bml` and `io.bosca.bml.host` Gradle plugins publish with the workspace tag through
the root `publishExceptFirebaseScrypt` task. This includes `bml-server`, which applications embed
as their host library. The web release pipeline builds, tests, and publishes the `@bosca/bml`
browser package at the same tag version. Compiled `bosca-messages` projects publish separately
to the `bml-message` raw-artifact namespace on workspace tags or manual runs. All three paths
use the configured Bosca Artifacts registry.

Client bundling resolves Node from `PATH`, then the standard Homebrew locations. For another
installation, set `bml.nodeExecutable=/absolute/path/to/node` in your Gradle user properties
or configure `bml { nodeExecutable.set("/absolute/path/to/node") }` in the application build.
