# bosca-yks

Kotlin Multiplatform (KMP) implementation of the Yjs CRDT library, providing real-time collaborative editing primitives for the Bosca platform. Targets Android, Desktop (JVM), iOS, JS, WasmJS, and native platforms, enabling shared document state across all Bosca clients.

## Prerequisites

- **JDK 25** (resolved via Gradle toolchains)
- **Android SDK** (`ANDROID_HOME` or `sdk.dir` in `local.properties`)

## Build

Run these commands from the **workspace root**. `bosca-yks` is part of the root Gradle build, and its dependency versions live in [gradle/libs.versions.toml](../gradle/libs.versions.toml). Local `io.bosca:*` dependencies resolve to sibling projects without publishing.

```bash
./gradlew :bosca-yks:yks:desktopTest  # JVM tests
./gradlew :bosca-yks:yks:compileKotlinDesktop  # Compile the desktop target
./gradlew :bosca-yks:yks:compileKotlinAndroid  # Compile Android with an SDK
```

## Architecture

The library is a pure Kotlin port of Yjs, mirroring its architecture: lib0 encoding/decoding and RLE codecs at the base, CRDT structs (Item, GC, Skip) and content types in the middle, and Y-types (YArray, YMap, YText, YXml) plus the sync protocol at the top. All core logic lives in `commonMain` with platform source sets providing only expect/actual implementations.

The library is intentionally minimal in dependencies — only `kotlinx-coroutines-core` beyond the Kotlin stdlib. It ships as a single Gradle module (`:yks`) with multiplatform targets.

## Use in this workspace

Depend on the root project path for the modules you need. For example:

```kotlin
dependencies {
    implementation(project(":bosca-yks:yks"))
}
```

Published `io.bosca:*` coordinates used by existing catalogs also resolve to these local projects in the root build.

## License

`bosca-yks` is licensed under the [MIT License](LICENSE). As a port of Yjs and lib0, it also carries their MIT copyright notices, reproduced in the same file.
