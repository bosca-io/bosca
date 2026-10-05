package bosca.graphql.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

/**
 * The `io.bosca.graphql` Gradle plugin. Generates the typed Bosca GraphQL client from a
 * consumer's `.graphql` operation files + checked-in `schema.graphqls` under `src/main/graphql` into
 * `build/generated/bosca-graphql/kotlin`, wiring that directory into the Kotlin/JVM `main` source set so a
 * module just compiles — no manual codegen step. Web consumers may also enable the TypeScript target, with
 * its output/runtime module configured independently. Also exposes a `downloadBoscaGraphqlSchema` task that
 * refreshes the checked-in schema from a live endpoint, so builds otherwise stay offline.
 *
 * The codegen itself runs in a worker JVM ([JavaExec]) over the generator resolved by Maven coordinate —
 * the plugin stays a thin Gradle-API-only shim (mirrors `bml-gradle`).
 */
class BoscaGraphqlPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        val extension = target.extensions.create("boscaGraphql", BoscaGraphqlExtension::class.java)
        val graphqlDir = target.layout.projectDirectory.dir("src/main/graphql")
        extension.sourceDir.convention(graphqlDir)
        extension.schemaFile.convention(graphqlDir.file("schema.graphqls"))
        extension.packageName.convention("bosca.graphql.client.generated")
        extension.generateTypeScript.convention(false)
        extension.typeScriptOutputDir.convention(target.layout.buildDirectory.dir("generated/bosca-graphql/typescript"))
        extension.typeScriptRuntimeModule.convention("@bosca/bml")
        extension.generatorCoordinate.convention("io.bosca:bosca-graphql-client:$GENERATOR_VERSION")

        // Worker classpath for the codegen CLI — resolved by coordinate from the consumer's repositories,
        // kept off Gradle's own classpath (mirrors how bml-gradle resolves io.bosca:bml-compiler).
        val generatorClasspath = target.configurations.create("boscaGraphqlGenerator") { config ->
            config.isCanBeConsumed = false
            config.isCanBeResolved = true
            // Select the generator's JVM runtime variant the way a Kotlin/JVM runtime classpath does — by the
            // BASE coordinate (io.bosca:bosca-graphql-client) plus JVM attributes — rather than the flattened
            // `…-client-jvm` Maven coordinate. The base coordinate is what the workspace composite source-
            // substitutes to the local multiplatform project (the `-jvm` secondary coordinate isn't), so this
            // resolves with no publish step; published consumers still pick the jvm variant via Gradle metadata.
            val objects = target.objects
            config.attributes { attrs ->
                attrs.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
                attrs.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
                attrs.attribute(
                    LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                    objects.named(LibraryElements::class.java, LibraryElements.JAR),
                )
                attrs.attribute(KotlinPlatformType.attribute, KotlinPlatformType.jvm)
            }
            config.defaultDependencies { dependencies ->
                dependencies.add(target.dependencies.create(extension.generatorCoordinate.get()))
            }
        }

        val outputDir = target.layout.buildDirectory.dir("generated/bosca-graphql/kotlin")

        val generate = target.tasks.register("generateBoscaGraphqlClient", JavaExec::class.java) { task ->
            task.group = GROUP
            task.description = "Generate the typed Bosca GraphQL client from src/main/graphql/*.graphql + schema.graphqls."
            task.classpath = generatorClasspath
            task.mainClass.set(CLI_MAIN)
            task.inputs.file(extension.schemaFile).withPropertyName("schema")
            task.inputs.dir(extension.sourceDir).withPropertyName("operations")
                .withPathSensitivity(PathSensitivity.RELATIVE)
            task.outputs.dir(outputDir).withPropertyName("generated")
            // Zero-config: only run when the module actually has `.graphql` operations to generate from
            // (an absent or empty source dir is a no-op, not a build failure). `schema.graphqls` is excluded
            // by extension, so it never counts as an operation.
            task.onlyIf {
                val dir = extension.sourceDir.get().asFile
                dir.isDirectory && dir.walkTopDown().any { it.isFile && it.extension.lowercase() in OPERATION_EXTENSIONS }
            }
            task.argumentProviders.add(
                CommandLineArgumentProvider {
                    buildList {
                        add("generate")
                        add("--schema"); add(extension.schemaFile.get().asFile.absolutePath)
                        add("--queries"); add(extension.sourceDir.get().asFile.absolutePath)
                        add("--out"); add(outputDir.get().asFile.absolutePath)
                        add("--package"); add(extension.packageName.get())
                        extension.scalarMappings.get().forEach { (name, mapping) ->
                            add("--scalar"); add("$name:$mapping")
                        }
                    }
                },
            )
        }

        target.tasks.register("generateBoscaGraphqlTypeScriptClient", JavaExec::class.java) { task ->
            task.group = GROUP
            task.description = "Generate typed browser TypeScript operations from src/main/graphql/*.graphql + schema.graphqls."
            task.classpath = generatorClasspath
            task.mainClass.set(CLI_MAIN)
            task.inputs.file(extension.schemaFile).withPropertyName("schema")
            task.inputs.dir(extension.sourceDir).withPropertyName("operations")
                .withPathSensitivity(PathSensitivity.RELATIVE)
            task.inputs.property("runtimeModule", extension.typeScriptRuntimeModule)
            task.inputs.property("scalarMappings", extension.typeScriptScalarMappings)
            task.outputs.dir(extension.typeScriptOutputDir).withPropertyName("generatedTypeScript")
            task.onlyIf {
                if (!extension.generateTypeScript.get()) return@onlyIf false
                val dir = extension.sourceDir.get().asFile
                dir.isDirectory && dir.walkTopDown().any { it.isFile && it.extension.lowercase() in OPERATION_EXTENSIONS }
            }
            task.argumentProviders.add(
                CommandLineArgumentProvider {
                    buildList {
                        add("generate")
                        add("--target"); add("typescript")
                        add("--schema"); add(extension.schemaFile.get().asFile.absolutePath)
                        add("--queries"); add(extension.sourceDir.get().asFile.absolutePath)
                        add("--out"); add(extension.typeScriptOutputDir.get().asFile.absolutePath)
                        add("--runtime-module"); add(extension.typeScriptRuntimeModule.get())
                        extension.typeScriptScalarMappings.get().forEach { (name, mapping) ->
                            add("--scalar"); add("$name:$mapping")
                        }
                    }
                },
            )
        }

        target.tasks.register("downloadBoscaGraphqlSchema", JavaExec::class.java) { task ->
            task.group = GROUP
            task.description = "Refresh the checked-in schema.graphqls from a live GraphQL endpoint (introspection)."
            task.classpath = generatorClasspath
            task.mainClass.set(CLI_MAIN)
            task.argumentProviders.add(
                CommandLineArgumentProvider {
                    val endpoint = extension.endpoint.orNull
                        ?: error("Set boscaGraphql.endpoint to refresh the schema (e.g. the gateway's GraphQL URL).")
                    buildList {
                        add("download-schema")
                        add("--endpoint"); add(endpoint)
                        add("--out"); add(extension.schemaFile.get().asFile.absolutePath)
                        extension.headers.get().forEach { (name, value) -> add("--header"); add("$name: $value") }
                    }
                },
            )
        }

        // Zero-config wiring for Kotlin/JVM consumers: put the generated dir on the main source path and make
        // compilation depend on codegen, so applying the plugin to a module with src/main/graphql is enough.
        target.plugins.withId("org.jetbrains.kotlin.jvm") {
            val kotlin = target.extensions.getByName("kotlin") as KotlinProjectExtension
            kotlin.sourceSets.getByName("main").kotlin.srcDir(outputDir)
            target.tasks.named("compileKotlin").configure { it.dependsOn(generate) }
        }
    }

    companion object {
        private const val GROUP = "bosca graphql"
        private const val CLI_MAIN = "bosca.graphql.codegen.cli.BoscaGraphqlCodegenCliKt"
        private val OPERATION_EXTENSIONS = setOf("graphql", "gql")

        /** Must match the published bosca-graphql-client version. */
        const val GENERATOR_VERSION = "0.0.1"
    }
}
