# Bosca Markup Language

BML compiles HTML-like `.bml` sources with embedded server Kotlin and client TypeScript into
server-rendered pages, interactive islands, reusable components, and email or push message templates.

- [Getting started](docs/getting-started.md)
- [Grammar](docs/grammar.md)
- [Tag reference](docs/tag-reference.md)
- [Islands, deferred rendering, and shared caching](docs/islands.md)
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

Client bundling resolves Node from `PATH`, then the standard Homebrew locations. For another
installation, set `bml.nodeExecutable=/absolute/path/to/node` in your Gradle user properties
or configure `bml { nodeExecutable.set("/absolute/path/to/node") }` in the application build.
