# Sample BML site

A reference project layout for a BML website. It applies the `io.bosca.bml` Gradle
plugin, whose K2 compiler plugin turns each `.bml` under `src/main/bml/` into an in-memory Kotlin
render object during `compileKotlin`.

```
site/
├── build.gradle.kts          # applies id("io.bosca.bml")
└── src/main/bml/
    └── home.bml              # <page route="/"> with a full document skeleton
```

This layout is a reference example in the workspace root build. Compile it from the root with
`./gradlew :bml:examples:site:compileKotlin`. The root build supplies `io.bosca.bml` and
`core-bml` from local source.

Set `bml { generateKotlinSources = true }` when you want generated `.kt` and source-map files mirrored
under `build/generated/bml/kotlin` for inspection. Those mirror files are not compilation inputs.

Serve with `bml-server` (see `../../docs/getting-started.md`) by passing the
generated pages (e.g. `sample.site.generated.HomePage`, which implements `BmlPageRenderer`)
to `BmlServer`, which registers them on Bosca Server's router.

> This is a project skeleton illustrating page and plugin usage; the
> single-file `.bml` examples in `../../docs/examples/` are the language samples.
