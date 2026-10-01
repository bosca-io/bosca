# Gateway

`core-gateway` contains gateway contracts, and `gateway` implements schema discovery and
GraphQL schema stitching. The `proxy/` directory contains the Rust proxy service.

The Kotlin modules belong to the workspace's root Gradle build. Run from the workspace root:

```bash
./gradlew :gateway:test
./gradlew :gateway:core-gateway:test :gateway:gateway:test
cd gateway/proxy && cargo test --lib --all-features
```

Dependency versions for the Kotlin modules are in
[the shared catalog](../gradle/libs.versions.toml). The active validation pipeline is
[gateway-build.yaml](../.bosca/pipelines/gateway-build.yaml); the image is published as
`ghcr.io/bosca-io/bosca/bosca-gateway` by [release-image.yaml](../.bosca/pipelines/release-image.yaml).
