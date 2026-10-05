# bosca-firebase-scrypt

Firebase-compatible scrypt password hashing utility. A Rust implementation of Firebase's scrypt variant with UniFFI bindings to the JVM, allowing Kotlin/JVM code to verify passwords that were originally hashed by Firebase Authentication.

## Prerequisites

- JDK 25
- Rust toolchain (`rustup`) with Cargo

## Build

Run these commands from the **workspace root**. `firebase-scrypt` is part of the root Gradle build, and its dependency versions live in [gradle/libs.versions.toml](../gradle/libs.versions.toml). Local `io.bosca:*` dependencies resolve to sibling projects without publishing.

```bash
./gradlew :firebase-scrypt:firebase-scrypt:build  # Build Rust, UniFFI bindings, and Kotlin
```

## Architecture

Rust handles scrypt hashing for password verification. Mozilla's UniFFI generates JVM bindings from Rust `#[uniffi::export]` annotations. A thin Kotlin `Password` class wraps the generated `PasswordUtil` and implements `AutoCloseable` for resource cleanup. This leaf module has no dependency on `bosca-core` or `services-di`.

## Use in this workspace

Depend on the root project path for the modules you need. For example:

```kotlin
dependencies {
    implementation(project(":firebase-scrypt:firebase-scrypt"))
}
```

Published `io.bosca:*` coordinates used by existing catalogs also resolve to these local projects in the root build.
