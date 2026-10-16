package bosca.bml.gradle

import groovy.json.JsonSlurper
import org.gradle.api.Project
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider
import java.io.File
import java.io.Serializable
import java.nio.file.Files
import java.nio.file.LinkOption

/** Prepares inline CSS before compilation; browser modules are packaged after compilation. */
internal object BmlLibraryAssets {
    const val OUTPUT = "generated/bml/library-assets"
    const val SOURCES = "generated/bml/library-asset-sources"
    const val STYLES = "generated/bml/library-styles"

    fun configure(project: Project, ext: BmlExtension) {
        project.afterEvaluate {
            if (!ext.library.enabled.get()) return@afterEvaluate
            val compiler = project.configurations.create("bmlLibraryAssetCompiler") {
                it.isCanBeConsumed = false
                it.attributes { attributes ->
                    attributes.attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
                    attributes.attribute(Category.CATEGORY_ATTRIBUTE, project.objects.named(Category::class.java, Category.LIBRARY))
                    attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, project.objects.named(LibraryElements::class.java, LibraryElements.JAR))
                }
            }
            val artifact = BmlGradlePlugin().getPluginArtifact()
            project.dependencies.add(compiler.name, "${artifact.groupId}:${artifact.artifactId}:${artifact.version}")
            val inputs = project.layout.buildDirectory.file("generated/bml/style-inputs/styles.json")
            val styles = project.layout.buildDirectory.dir(STYLES)
            val extract = project.tasks.register("bmlExtractLibraryStyles", JavaExec::class.java) { task ->
                task.group = "bml"
                task.description = "Read library stylesheet bodies using the BML parser."
                task.classpath = compiler
                val toolchains = project.extensions.getByType(org.gradle.jvm.toolchain.JavaToolchainService::class.java)
                task.javaLauncher.set(toolchains.launcherFor(project.extensions.getByType(org.gradle.api.plugins.JavaPluginExtension::class.java).toolchain))
                task.mainClass.set("bosca.bml.codegen.BmlLibraryStyles")
                task.inputs.files(project.fileTree(ext.sourceDir) {
                    it.include("**/*.bml")
                    it.exclude("**/node_modules/**")
                })
                    .withPathSensitivity(PathSensitivity.RELATIVE)
                task.outputs.file(inputs)
                task.argumentProviders.add(CommandLineArgumentProvider {
                    listOf(ext.sourceDir.get().asFile.absolutePath, inputs.get().asFile.absolutePath)
                })
            }
            val packagedBundler = project.file("node_modules/@bosca/bml/tools/bundle.mjs")
            val projectDir = project.projectDir
            val buildDir = project.layout.buildDirectory.get().asFile.toPath()
            // Node resolves symlinked sources to their real locations before searching ancestors.
            // Keep both paths: generated GraphQL/contract stubs still resolve from the producer.
            val producerRoot = projectDir.canonicalFile
            val dependencyPaths = project.providers.of(BmlLibraryDependencyLocations::class.java) { spec ->
                spec.parameters.sourceDirectories.add(projectDir)
                spec.parameters.sourceDirectories.add(ext.sourceDir.map { it.asFile })
                spec.parameters.buildDirectory.set(project.layout.buildDirectory)
            }
            val dependencyFiles = project.files(dependencyPaths.map { it.directories }).asFileTree.matching { tree ->
                // A workspace's link back to this producer must not snapshot its build outputs.
                tree.exclude {
                    it.isDirectory && (it.file.canonicalFile == producerRoot ||
                        it.file.canonicalFile.toPath().startsWith(buildDir.toFile().canonicalFile.toPath()))
                }
            }
            val sourceFiles = project.fileTree(projectDir) {
                it.exclude(".gradle/**", ".kotlin/**", "node_modules/**", ".git", ".git/**")
                it.exclude { entry -> entry.file.toPath().startsWith(buildDir) }
            }
            val bundle = project.tasks.register("bmlBundleLibraryStyles", Exec::class.java) { task ->
                task.group = "bml"
                task.description = "Bundle inline library CSS and referenced resources before scoping."
                task.dependsOn(extract, "bmlInstallClientDependencies")
                task.inputs.file(inputs).withPathSensitivity(PathSensitivity.NONE)
                task.inputs.files(sourceFiles, project.fileTree(ext.sourceDir)).withPathSensitivity(PathSensitivity.RELATIVE)
                task.inputs.files(dependencyFiles).withPropertyName("clientDependencies").withPathSensitivity(PathSensitivity.RELATIVE)
                task.inputs.files(dependencyPaths.map { it.configurations }).withPropertyName("clientResolverConfigurations")
                    .withPathSensitivity(PathSensitivity.ABSOLUTE)
                task.inputs.property("clientDependencyDirectories", dependencyPaths.map { it.directories.map { directory -> directory.path } })
                task.inputs.property("clientDependencyLinks", dependencyPaths.map { it.links })
                task.inputs.files(project.provider { (ext.clientBundler.orNull?.asFile ?: packagedBundler).parentFile.listFiles()?.toList().orEmpty() })
                task.inputs.property("library", "${project.group}:${project.name}")
                task.inputs.property("version", project.version.toString())
                task.outputs.dir(styles)
                task.executable = ext.nodeExecutable.get()
                task.onlyIf {
                    val hasStyles = (JsonSlurper().parse(inputs.get().asFile) as List<*>).isNotEmpty()
                    val script = (ext.clientBundler.orNull?.asFile ?: packagedBundler).resolveSibling("library.mjs")
                    require(!hasStyles || script.isFile) {
                        "BML library CSS requires the current @bosca/bml bundler; install it or configure bml.clientBundler"
                    }
                    // Run empty input cleanup when a previous stylesheet has been removed.
                    script.isFile && (hasStyles || styles.get().asFile.exists())
                }
                val library = "${project.group}:${project.name}"
                val version = project.version.toString()
                task.argumentProviders.add(CommandLineArgumentProvider {
                    listOf((ext.clientBundler.orNull?.asFile ?: packagedBundler).resolveSibling("library.mjs").absolutePath,
                        "--input", inputs.get().asFile.absolutePath, "--source-root", ext.sourceDir.get().asFile.absolutePath,
                        "--public-in", projectDir.resolve("public").absolutePath, "--project-dir", projectDir.absolutePath,
                        "--out", styles.get().asFile.absolutePath, "--library-id", library, "--library-version", version)
                })
            }
            project.tasks.named("compileKotlin").configure { task ->
                task.dependsOn(bundle)
                task.inputs.files(project.fileTree(styles) { it.include("styles.json") })
                    .withPropertyName("libraryStyles").withPathSensitivity(PathSensitivity.RELATIVE)
            }
            project.tasks.named("bmlBundleClient").configure { task ->
                task.inputs.files(dependencyFiles).withPropertyName("libraryClientDependencies").withPathSensitivity(PathSensitivity.RELATIVE)
                task.inputs.files(dependencyPaths.map { it.configurations }).withPropertyName("libraryClientResolverConfigurations")
                    .withPathSensitivity(PathSensitivity.ABSOLUTE)
                task.inputs.property("libraryClientDependencyDirectories", dependencyPaths.map { it.directories.map { directory -> directory.path } })
                task.inputs.property("libraryClientDependencyLinks", dependencyPaths.map { it.links })
                task.inputs.files(sourceFiles, project.fileTree(ext.sourceDir)).withPropertyName("libraryAssetSources").withPathSensitivity(PathSensitivity.RELATIVE)
                task.inputs.files(project.fileTree(styles)).withPropertyName("libraryStyleAssets").withPathSensitivity(PathSensitivity.RELATIVE)
                task.outputs.dir(project.layout.buildDirectory.dir(OUTPUT)).withPropertyName("libraryAssets")
                task.outputs.dir(project.layout.buildDirectory.dir(SOURCES)).withPropertyName("libraryAssetSources")
            }
        }
    }
}

/** Dependency trees, link identities, and configuration candidates used by browser module resolution. */
internal data class BmlLibraryDependencyPaths(
    val directories: List<File>,
    val links: Map<String, String>,
    val configurations: List<File>,
) : Serializable

/** Discovers nested source/package links without recursing through directory cycles or build output. */
internal abstract class BmlLibraryDependencyLocations : ValueSource<BmlLibraryDependencyPaths, BmlLibraryDependencyLocations.Parameters> {
    interface Parameters : ValueSourceParameters {
        val sourceDirectories: ListProperty<File>
        val buildDirectory: DirectoryProperty
    }

    /** Follows source and installed-package links until no further lookup directories are found. */
    override fun obtain(): BmlLibraryDependencyPaths {
        val buildRoot = parameters.buildDirectory.get().asFile.canonicalFile.toPath()
        val directories = sortedSetOf<String>()
        val links = sortedMapOf<String, String>()
        val pending = ArrayDeque<File>()
        val visited = mutableSetOf<File>()
        val ignored = setOf(".git", ".gradle", ".kotlin")

        fun dependencyLookup(source: File) {
            generateSequence(source) { it.parentFile }.forEach { directory ->
                val modules = directory.resolve("node_modules")
                if (directories.add(modules.path)) pending.add(modules)
            }
        }
        val configurations = BmlLibraryConfigurations(::dependencyLookup)
        fun lookupFrom(source: File) {
            dependencyLookup(source)
            configurations.ancestors(source)
        }
        parameters.sourceDirectories.get().forEach { source ->
            val root = source.absoluteFile.normalize()
            lookupFrom(root)
            pending.add(root)
        }
        while (pending.isNotEmpty()) {
            val path = pending.removeFirst()
            val real = path.canonicalFile
            if (real.toPath().startsWith(buildRoot)) continue
            if (path != real) {
                links[path.path] = real.path
                lookupFrom(if (real.isFile) real.parentFile else real)
            }
            if (!real.isDirectory) continue
            if (path.name == "node_modules") directories.add(path.path)
            if (!visited.add(real)) continue
            configurations.local(real, includeMissing = false)
            Files.newDirectoryStream(real.toPath()).use { entries ->
                entries.sortedBy { it.fileName.toString() }.forEach { entry ->
                    if (entry.fileName.toString() !in ignored &&
                        (Files.isSymbolicLink(entry) || Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS))
                    ) pending.add(entry.toFile())
                }
            }
        }
        return BmlLibraryDependencyPaths(directories.map(::File), links, configurations.files)
    }
}
