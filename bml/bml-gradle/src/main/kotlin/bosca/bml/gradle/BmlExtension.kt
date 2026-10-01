package bosca.bml.gradle

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property

/** DSL for the `bml { }` block: where `.bml` sources live and the generated package. */
abstract class BmlExtension {
    /** Root directory of `.bml` sources (default `src/main/bml`). */
    abstract val sourceDir: DirectoryProperty

    /** Package the compiler plugin synthesizes page objects into (default `bml.generated`). */
    abstract val packageName: Property<String>

    /**
     * Opt in to writing the generated Kotlin (`.kt` + `.kt.map`) to `build/generated/bml/kotlin` so it's
     * inspectable and debuggable (compiled classes are then tagged with the real `.kt` path → correct error
     * line numbers + breakpoints). Off by default; the in-memory sources are always what compile, so this only
     * adds a readable mirror — it is NOT a compile source root.
     */
    abstract val generateKotlinSources: Property<Boolean>

    /**
     * Path to the `@bosca/bml` esbuild bundler (`tools/bundle.mjs`). When set, the `bmlBundleClient`
     * task bundles the generated client TypeScript into browser JS. Typically points at the installed
     * `node_modules/@bosca/bml/tools/bundle.mjs`.
     */
    abstract val clientBundler: RegularFileProperty

    /** Node executable for client bundling; defaults to PATH, then standard Homebrew locations. */
    abstract val nodeExecutable: Property<String>

    /**
     * Optional `@bosca/bml` runtime entry to alias the bare specifier to when it isn't installed in
     * node_modules (e.g. this monorepo, where the runtime is local source). Omit for normal consumers.
     */
    abstract val clientRuntime: RegularFileProperty

    /**
     * The site's every-page asset tier, counted into each page's first-load total by `bmlMetrics`.
     * Seeded with the CSS files under `src/main/client` (the `BmlServer(globalCss = …)`
     * convention); add the built global JS or any other always-loaded files here.
     */
    abstract val metricsGlobalAssets: ConfigurableFileCollection
}
