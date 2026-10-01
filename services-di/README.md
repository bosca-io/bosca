# Bosca DI and services

Kotlin Multiplatform dependency injection and service framework for the Bosca platform. This is the root of the dependency tree -- consumed by every other Bosca module, including backend services, mobile apps, and web clients.

## Modules

| Module | Description |
|--------|-------------|
| `di` | Suspend-friendly dependency injection container (KMP) |
| `di-ksp` | KSP processor for compile-time DI wiring |
| `service` | Service marker interface and lifecycle (KMP) |
| `service-ksp` | KSP processor for `@ServiceImplementation` registration |
| `base-ksp` | Shared KSP utilities used by DI and service processors |

## Platforms

All multiplatform modules target: JVM (desktop), Android, iOS (arm64/x64/simulator), JS, WASM.

## Prerequisites

- JDK 25
- Android SDK (`ANDROID_HOME` or `sdk.dir` in `local.properties`)

## Build

Run these commands from the **workspace root**. `services-di` is part of the root Gradle build, and its dependency versions live in [gradle/libs.versions.toml](../gradle/libs.versions.toml). Local `io.bosca:*` dependencies resolve to sibling projects without publishing.

```bash
./gradlew :services-di:test  # Test DI and service modules
./gradlew :services-di:di:desktopTest :services-di:service:desktopTest  # JVM tests
```

## Architecture

The project follows a core/processor split. The `di` and `service` modules are pure multiplatform runtime libraries with zero external dependencies beyond `kotlinx-coroutines-core`. The `di-ksp` and `service-ksp` modules are JVM-only KSP annotation processors that generate DI registration code at compile time, both sharing utilities from `base-ksp`. The `service` module depends on `di` for provider resolution.

Key design decisions: suspend-first resolution (`provide<T>()` is a suspend function), annotation-driven registration via KSP, and zero external runtime dependencies.

## Use in this workspace

Depend on the root project path for the modules you need. For example:

```kotlin
dependencies {
    implementation(project(":services-di:di"))
    implementation(project(":services-di:service"))
    ksp(project(":services-di:di-ksp"))
}
```

Published `io.bosca:*` coordinates used by existing catalogs also resolve to these local projects in the root build.

## DI API

```kotlin
// Register a provider
provides<MyService>(singleton = true) { MyServiceImpl() }

// Resolve (suspend-friendly)
suspend fun <T : Any> provide(): T
fun <T : Any> provideBlocking(): T
fun <T : Any> provideLazy(): Lazy<T>
```
