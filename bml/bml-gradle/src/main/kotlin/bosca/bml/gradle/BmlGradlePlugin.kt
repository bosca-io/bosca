package bosca.bml.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.JavaExec
import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import java.util.Properties
import java.io.File

/**
 * The `io.bosca.bml` Gradle plugin. A `KotlinCompilerPluginSupportPlugin` that wires
 * the BML K2 compiler plugin (`io.bosca:bml-compiler`) into Kotlin compilation and passes the
 * project's `.bml` source root. The compiler plugin then reads those `.bml` files and contributes
 * the generated Kotlin to the compilation as in-memory sources (no `.kt` files on disk).
 *
 * Mirrors `~/git/engine` Volt's `VoltGradlePlugin` (standalone plugin build, `includeBuild`'d into
 * consumers; the compiler plugin resolved from a local Maven repo).
 */
class BmlGradlePlugin : KotlinCompilerPluginSupportPlugin {

    override fun apply(target: Project) {
        val ext = target.extensions.create("bml", BmlExtension::class.java)
        ext.sourceDir.convention(target.layout.projectDirectory.dir("src/main/bml"))
        ext.packageName.convention("bml.generated")
        ext.generateKotlinSources.convention(false) // opt-in: dump generated `.kt` for inspection/debugging
        val nodeName = if (target.providers.systemProperty("os.name").get().startsWith("Windows")) "node.exe" else "node"
        ext.nodeExecutable.convention(
            target.providers.gradleProperty("bml.nodeExecutable").orElse(
                target.providers.environmentVariable("PATH").orElse("").map { path ->
                    val candidates = path.split(File.pathSeparator)
                        .filter { it.isNotBlank() }
                        .map { File(it, nodeName) } +
                        listOf(File("/opt/homebrew/bin/node"), File("/usr/local/bin/node"))
                    candidates.firstOrNull { it.isFile && it.canExecute() }?.absolutePath ?: nodeName
                }
            )
        )
        // The every-page tier for bmlMetrics: the global stylesheet(s) under the src/main/client
        // convention. Sites add their built global JS (or anything else always loaded) explicitly.
        ext.metricsGlobalAssets.from(
            target.fileTree(target.layout.projectDirectory.dir("src/main/client")) { it.include("*.css") },
        )

        // The compiler plugin (CollectAdditionalSourceFilesExtension) only runs over `.bml` files if
        // KGP actually collects them as compilation sources. Two halves are required and must be paired:
        //   1. addCustomSourceFilesExtensions("bml") — teach KGP/the compiler that `.bml` is a source
        //      file extension (otherwise only `.kt`/`.kts` are collected and the extension never fires).
        //   2. add the `.bml` source dir to the main source set so those files are on the source path.
        // The compiler plugin then drops the raw `.bml` and contributes generated Kotlin in their place.
        target.plugins.withId("org.jetbrains.kotlin.jvm") {
            val kotlin = target.extensions.getByName("kotlin")
                as org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
            val main = kotlin.sourceSets.getByName("main")
            main.kotlin.srcDir(ext.sourceDir)
            // addCustomSourceFilesExtensions is declared on the `internal` InternalKotlinSourceSet
            // interface, so Kotlin won't let us call it cross-module — reach it reflectively. The
            // concrete DefaultKotlinSourceSet exposes it as a public method.
            main.javaClass.getMethod("addCustomSourceFilesExtensions", List::class.java)
                .invoke(main, listOf("bml"))

            // The K2 plugin reads `.bml` from the source-root PATH (a SubpluginOption string), so Gradle
            // does NOT otherwise snapshot their CONTENT — meaning a `.bml` edit leaves `compileKotlin`
            // up-to-date / cache-restorable and the change silently doesn't take effect. Declare the `.bml`
            // dir as a content-tracked input so editing a `.bml` correctly invalidates the compilation.
            target.tasks.named("compileKotlin").configure { task ->
                task.inputs.dir(ext.sourceDir)
                    .withPropertyName("bmlSources")
                    .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
                    .optional()
            }

            // The compiler plugin writes generated resources (the email manifest)
            // during compilation; add that dir to the main resource set so they land in the jar, and
            // order resource processing after the compilation that produces them.
            val resourcesDir = target.layout.buildDirectory.dir("generated/bml/resources")
            target.extensions.getByType(org.gradle.api.tasks.SourceSetContainer::class.java)
                .getByName("main").resources.srcDir(resourcesDir)
            target.tasks.named("processResources").configure { task ->
                task.dependsOn("compileKotlin")
            }

            // The project's `public/**` assets are packaged into the jar under `bml/public/` so an
            // email bundle is self-contained: the message server serves a VERSION's assets straight
            // from that version's jar at version-pinned public URLs.
            val publicDir = target.layout.projectDirectory.dir("public")
            target.tasks.named("processResources", org.gradle.language.jvm.tasks.ProcessResources::class.java).configure { task ->
                task.from(publicDir) { spec -> spec.into("bml/public") }
            }

            // Only a runnable site identifies a build; a BML library applying this plugin must not
            // ship a build-id resource that a site's server could read instead of its own.
            target.plugins.withId("application") { registerBuildIdTask(target) }
            registerBundleTask(target, ext)
            registerMetricsTask(target, ext)
            registerDevelopmentTask(target)
        }
        configureGraphqlDefaults(target)
    }

    /**
     * GraphQL codegen is OWNED by the `io.bosca.graphql` plugin (download-schema + generate + source-set
     * wiring, gated on `.graphql` files existing). The BML plugin does not apply it — a project opts in with
     * `id("io.bosca.graphql")` — but when it IS applied the BML plugin contributes BML-sane defaults: a BML
     * site fetches data over GraphQL both server-side and from scoped client modules. In addition to Kotlin
     * operations, BML enables browser TypeScript operations in its generated TS tree so page/component scripts
     * can import them without handwritten query strings and response interfaces. A project still sets its own
     * `boscaGraphql { endpoint; packageName }` and can override any scalar or disable the browser target.
     */
    private fun configureGraphqlDefaults(target: Project) {
        target.plugins.withId("io.bosca.graphql") {
            val ext = target.extensions.getByName("boscaGraphql")
            // BoscaGraphqlExtension isn't on this plugin's compile classpath (separate plugin build), so reach
            // its MapProperty reflectively — same pattern as addCustomSourceFilesExtensions above. The returned
            // type (Gradle's MapProperty) IS on the classpath, so putAll is a normal, type-safe call. These are
            // additive defaults applied at apply-time; a project's later boscaGraphql {} puts override per key.
            @Suppress("UNCHECKED_CAST")
            val scalars = ext.javaClass.getMethod("getScalarMappings").invoke(ext)
                as org.gradle.api.provider.MapProperty<String, String>
            scalars.putAll(
                mapOf(
                    "JSON" to "kotlinx.serialization.json.JsonElement",
                    "Long" to "kotlin.Long",
                    "UUID" to "kotlin.String",
                    "DateTime" to "kotlin.String",
                ),
            )

            @Suppress("UNCHECKED_CAST")
            val generateTypeScript = ext.javaClass.getMethod("getGenerateTypeScript").invoke(ext)
                as org.gradle.api.provider.Property<Boolean>
            generateTypeScript.convention(true)

            val typeScriptOutputDir = ext.javaClass.getMethod("getTypeScriptOutputDir").invoke(ext)
                as org.gradle.api.file.DirectoryProperty
            typeScriptOutputDir.convention(target.layout.buildDirectory.dir("generated/bml/ts/graphql"))

            @Suppress("UNCHECKED_CAST")
            val typeScriptScalars = ext.javaClass.getMethod("getTypeScriptScalarMappings").invoke(ext)
                as org.gradle.api.provider.MapProperty<String, String>
            typeScriptScalars.putAll(
                mapOf(
                    "JSON" to "unknown",
                    "Long" to "number",
                    "UUID" to "string",
                    "DateTime" to "string",
                ),
            )

            // Browser operations are imported by compiler-emitted page/component modules, so they must exist
            // before esbuild resolves those imports. Configure lazily to remain independent of plugin order.
            target.tasks.matching { it.name == "bmlBundleClient" }.configureEach { task ->
                task.dependsOn("generateBoscaGraphqlTypeScriptClient")
            }
        }
    }

    /**
     * `bmlBundleClient`: bundles the generated client TypeScript (written by the compiler plugin to
     * `build/generated/bml/ts`) into browser JS via the `@bosca/bml` esbuild bundler. The bundler
     * comes from `bml.clientBundler` when set (the bosca-workspace dev loop points it at the live
     * bml-runtime checkout), else from the published `@bosca/bml` package in the project's
     * node_modules (declare it in package.json — `bmlInstallClientDependencies` installs it).
     * A no-op when the project has no `<script client>`.
     */
    private fun registerBundleTask(target: Project, ext: BmlExtension) {
        val tsDir = target.layout.buildDirectory.dir("generated/bml/ts")
        val jsDir = target.layout.buildDirectory.dir("generated/bml/js")
        // Production bundles: one merged `<slug>.page.js` per page, built from the metrics
        // manifest's page→module mapping. Production deployments serve THIS dir as clientDir.
        val jsProdDir = target.layout.buildDirectory.dir("generated/bml/js-prod")
        // Production-minified every-page assets from src/main/client. They become classpath
        // resources under their established basenames, which BmlServer launchers load as app.css/js.
        val globalAssetSourceDir = target.layout.projectDirectory.dir("src/main/client")
        val globalAssetOutputDir = target.layout.buildDirectory.dir("generated/bml/client-assets")
        val manifestFile = target.layout.buildDirectory.file("generated/bml/metrics/$MANIFEST_FILE_NAME")
        // Standalone fallback: the bundler shipped inside the installed @bosca/bml package. The
        // package declares esbuild as a real dependency, so npm install provides everything the
        // script imports. When it is used, no --runtime alias is passed — the compiler-emitted
        // `@bosca/bml` imports resolve to the same installed package.
        val packagedBundler = target.file("node_modules/@bosca/bml/tools/bundle.mjs")
        val clientManifest = target.file("package.json")
        // `bmlInstallClientDependencies`: sites whose `<script client>` imports packages (e.g. the
        // Bosca auth library) declare them in a project-root package.json; the plugin installs
        // node_modules before bundling so bare imports resolve. A no-op without a package.json.
        val installDeps = target.tasks.register("bmlInstallClientDependencies", Exec::class.java) { task ->
            task.group = "bml"
            task.description = "Install client-script npm dependencies (package.json), when present."
            task.workingDir = target.projectDir
            task.inputs.files(target.file("package.json"), target.file("package-lock.json"))
                .withPropertyName("clientManifest").optional()
            task.outputs.dir(target.file("node_modules")).withPropertyName("nodeModules")
            // Through a login shell so node-version managers (nvm & co) are on PATH — Gradle
            // daemons don't reliably inherit the interactive shell's npm.
            task.executable = "sh"
            task.args("-lc", "npm install --no-audit --no-fund")
            task.onlyIf { clientManifest.isFile }
        }
        target.tasks.register("bmlBundleClient", Exec::class.java) { task ->
            task.group = "bml"
            task.description = "Bundle generated client TypeScript and optimize global browser assets (esbuild)."
            task.dependsOn("compileKotlin") // the compiler plugin emits the .ts during compilation
            task.dependsOn(installDeps)
            task.inputs.dir(tsDir).withPropertyName("clientTs").optional()
            task.inputs.files(target.fileTree(globalAssetSourceDir) { it.include("*.css", "*.js", "*.ts") })
                .withPropertyName("globalClientAssets")
                .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
            // The bundler script AND the `@bosca/bml` runtime source tree are inputs too — without these,
            // editing the runtime (or the bundler) leaves the bundle UP-TO-DATE and the served JS goes stale
            // even though the generated TS is unchanged. Track the runtime entry's whole directory so its
            // transitive imports (action.ts, island.ts, …) invalidate the bundle.
            task.inputs.files(ext.clientBundler).withPropertyName("clientBundler").optional()
            task.inputs.dir(target.provider { ext.clientRuntime.orNull?.asFile?.parentFile })
                .withPropertyName("clientRuntime")
                .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
                .optional()
            task.outputs.dir(jsDir).withPropertyName("clientJs")
            task.outputs.dir(jsProdDir).withPropertyName("clientJsProd")
            task.outputs.dir(globalAssetOutputDir).withPropertyName("globalClientAssetsOptimized")
            task.executable = ext.nodeExecutable.get()
            task.onlyIf {
                val dir = tsDir.get().asFile
                val hasPageInputs = dir.isDirectory && dir.listFiles()?.any { it.isFile && it.extension == "ts" } == true
                val assetDir = globalAssetSourceDir.asFile
                val hasGlobalInputs = assetDir.isDirectory && assetDir.listFiles()?.any {
                    it.isFile && it.extension in setOf("css", "js", "ts")
                } == true
                val hasStaleOutputs = jsDir.get().asFile.exists() ||
                    jsProdDir.get().asFile.exists() ||
                    globalAssetOutputDir.get().asFile.exists()
                (ext.clientBundler.isPresent || packagedBundler.isFile) &&
                    (hasPageInputs || hasGlobalInputs || hasStaleOutputs)
            }
            task.argumentProviders.add(CommandLineArgumentProvider {
                buildList {
                    add((ext.clientBundler.orNull?.asFile ?: packagedBundler).absolutePath)
                    add("--in"); add(tsDir.get().asFile.absolutePath)
                    add("--out"); add(jsDir.get().asFile.absolutePath)
                    add("--manifest"); add(manifestFile.get().asFile.absolutePath)
                    add("--prod-out"); add(jsProdDir.get().asFile.absolutePath)
                    add("--assets-in"); add(globalAssetSourceDir.asFile.absolutePath)
                    add("--assets-out"); add(globalAssetOutputDir.get().asFile.absolutePath)
                    if (ext.clientRuntime.isPresent) {
                        add("--runtime"); add(ext.clientRuntime.get().asFile.absolutePath)
                    }
                }
            })
        }
        target.extensions.getByType(org.gradle.api.tasks.SourceSetContainer::class.java)
            .getByName("main").resources.srcDir(globalAssetOutputDir)
        target.tasks.named("processResources").configure { task ->
            task.dependsOn("bmlBundleClient")
        }
    }

    /**
     * `bmlBuildId` (application projects only): writes the build's identity, the current UTC time, to
     * `META-INF/bosca/bml/build-id` in the main resources, but only when the file does not exist yet. It lives under `build/`, so a
     * local build keeps one stable value until `clean`, and every fresh CI checkout gets a new one.
     * bml-server includes it in the deployment token, so a deploy that changes only server code still
     * changes shared-shell validators, without hashing anything at startup.
     */
    private fun registerBuildIdTask(target: Project) {
        val outputDir = target.layout.buildDirectory.dir("generated/bml/build-id")
        val buildId = target.tasks.register("bmlBuildId") { task ->
            task.group = "bml"
            task.description = "Write the BML build identity (the build time) unless it already exists."
            val file = outputDir.map { it.file(BUILD_ID_RESOURCE) }
            task.outputs.file(file)
            task.doLast {
                val idFile = file.get().asFile
                if (!idFile.exists()) {
                    idFile.parentFile.mkdirs()
                    idFile.writeText(java.time.Instant.now().toString())
                }
            }
        }
        target.extensions.getByType(org.gradle.api.tasks.SourceSetContainer::class.java)
            .getByName("main").resources.srcDir(buildId.map { outputDir.get() })
    }

    /**
     * `bmlMetrics`: joins the compiler-written metrics manifest with the bundled island
     * JS and the site's global tier to report per-page first-load payloads (raw + gzip) — console
     * table plus `build/reports/bml/site-metrics.json`. Always re-runs: it exists to be read.
     */
    private fun registerMetricsTask(target: Project, ext: BmlExtension) {
        target.tasks.register("bmlMetrics", BmlMetricsTask::class.java) { task ->
            task.group = "bml"
            task.description = "Report per-page first-load payload sizes (raw + gzip) for the compiled site."
            task.dependsOn("compileKotlin", "bmlBundleClient")
            task.metricsDir.set(target.layout.buildDirectory.dir("generated/bml/metrics"))
            task.jsDir.set(target.layout.buildDirectory.dir("generated/bml/js"))
            task.prodJsDir.set(target.layout.buildDirectory.dir("generated/bml/js-prod"))
            task.globalAssets.from(ext.metricsGlobalAssets)
            task.reportFile.set(target.layout.buildDirectory.file("reports/bml/site-metrics.json"))
            task.outputs.upToDateWhen { false }
        }
    }

    /**
     * Adds the application development lifecycle supplied by the BML plugin:
     *
     * - `run` remains a one-shot foreground run, but always uses BML development mode and current
     *   client bundles. This also makes it a suitable child for `bosca bml dev --run`.
     * - `bmlDev` is the Gradle-owned hot-swap loop. Gradle watches the real inputs of `classes`
     *   and `bmlBundleClient`, incrementally rebuilds them, then publishes application classes to
     *   a fresh child classloader in the persistent server JVM. SSE refreshes connected browsers.
     */
    private fun registerDevelopmentTask(target: Project) {
        target.plugins.withId("application") {
            val clientDir = target.layout.buildDirectory.dir("generated/bml/js")
            val runTask = target.tasks.named("run", JavaExec::class.java)
            runTask.configure { task ->
                task.dependsOn("bmlBundleClient")
                task.systemProperty("bml.dev", "true")
                task.systemProperty("bml.clientDir", clientDir.get().asFile.absolutePath)
                // A server launch is an action, never a reusable build output (notably, the
                // GraalVM plugin gives every JavaExec an agent-output directory).
                task.outputs.upToDateWhen { false }
            }

            target.tasks.register("bmlDev", BmlDevTask::class.java) { task ->
                val run = runTask.get()
                val mainOutput = target.extensions.getByType(org.gradle.api.tasks.SourceSetContainer::class.java)
                    .getByName("main").output
                task.group = "bml"
                task.description = "Run the BML application with incremental rebuild, in-process hot swap, and browser reload."
                task.dependsOn("classes", "bmlBundleClient")
                // Copy the run task's lazy file collection directly. Wrapping it in a Provider
                // makes Gradle query included-build dependencies while realizing help/tasks,
                // before every composite project is in a resolvable lifecycle state.
                task.classpath.from(run.classpath)
                task.reloadClasspath.from(mainOutput)
                task.mainClass.set(run.mainClass)
                task.mainModule.set(run.mainModule)
                task.inferModulePath.set(run.modularity.inferModulePath)
                task.arguments.set(target.provider {
                    run.args + run.argumentProviders.flatMap { it.asArguments() }
                })
                task.allJvmArguments.set(target.provider { run.allJvmArgs })
                task.environmentVariables.set(target.provider {
                    run.environment.mapValues { (_, value) -> value.toString() }
                })
                task.javaExecutable.set(run.javaLauncher.map { it.executablePath })
                task.workingDirectory.set(target.layout.dir(target.provider { run.workingDir }))
                task.clientDirectory.set(clientDir)
                task.generationDirectory.set(target.layout.buildDirectory.dir("bml/dev/generations"))
                task.generationMarker.set(target.layout.buildDirectory.file("bml/dev/current-generation"))
                task.outputs.upToDateWhen { false }
            }
        }
    }

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean = true

    override fun getCompilerPluginId(): String = "bml"

    override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(
        groupId = "io.bosca",
        artifactId = "bml-compiler",
        version = publishedPluginVersion,
    )

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        val project = kotlinCompilation.target.project
        val ext = project.extensions.findByType(BmlExtension::class.java)
        val sourceRoot = (ext?.sourceDir?.orNull?.asFile
            ?: project.layout.projectDirectory.dir("src/main/bml").asFile).absolutePath
        // Generated client TypeScript lands here for esbuild to bundle.
        val tsOutputDir = project.layout.buildDirectory.dir("generated/bml/ts").get().asFile.absolutePath
        // Generated Kotlin (+ `.kt.map`) is dumped here ONLY when opted in via `bml { generateKotlinSources = true }`
        // — NOT a compile source root (the compiler plugin feeds the in-memory copies), just a readable mirror.
        val kotlinOutputDir = project.layout.buildDirectory.dir("generated/bml/kotlin").get().asFile.absolutePath
        // Generated resources (the email manifest) land here; apply() adds the dir to the main resource set.
        val resourcesOutputDir = project.layout.buildDirectory.dir("generated/bml/resources").get().asFile.absolutePath
        // The site metrics manifest + per-component CSS chunks, read by `bmlMetrics`.
        val metricsOutputDir = project.layout.buildDirectory.dir("generated/bml/metrics").get().asFile.absolutePath
        return project.provider {
            buildList {
                add(SubpluginOption("enabled", "true"))
                add(SubpluginOption("bmlSourceRoot", sourceRoot))
                add(SubpluginOption("bmlTsOutputDir", tsOutputDir))
                add(SubpluginOption("bmlResourcesOutputDir", resourcesOutputDir))
                add(SubpluginOption("bmlMetricsOutputDir", metricsOutputDir))
                if (ext?.generateKotlinSources?.getOrElse(false) == true) {
                    add(SubpluginOption("bmlKotlinOutputDir", kotlinOutputDir))
                }
            }
        }
    }
}

/**
 * The BML Gradle plugin and compiler plugin are released together. The build expands this resource
 * from the same `PUBLISH_VERSION` that sets both publications, keeping consumer resolution aligned.
 */
private val publishedPluginVersion: String by lazy {
    val properties = Properties()
    val resource = BmlGradlePlugin::class.java.getResourceAsStream("bml-gradle-plugin.properties")
        ?: error("Missing bml-gradle-plugin.properties")
    resource.use(properties::load)
    properties.getProperty("version")?.takeIf { it.isNotBlank() }
        ?: error("Missing version in bml-gradle-plugin.properties")
}

/** Classpath resource holding the build identity read by bml-server (see `bmlBuildId`). */
internal const val BUILD_ID_RESOURCE: String = "META-INF/bosca/bml/build-id"
