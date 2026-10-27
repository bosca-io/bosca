plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-gradle-plugin`
    `maven-publish`
}

val publishVersion: String = System.getenv("PUBLISH_VERSION")?.removePrefix("v") ?: "0.0.1"

group = "io.bosca"
version = publishVersion

// Gradle plugin project: a KotlinCompilerPluginSupportPlugin that wires
// the BML K2 compiler plugin into Kotlin compilation. It only needs the Gradle/Kotlin plugin APIs —
// it references io.bosca:bml-compiler by coordinates (getPluginArtifact), not as a compile dependency.
dependencies {
    compileOnly(kotlin("gradle-plugin-api"))
    implementation(kotlin("gradle-plugin"))
    testImplementation(gradleTestKit())
    testImplementation(kotlin("test-junit"))
    testImplementation(project(":bml:bml-server"))
    testImplementation(project(":bml:core-bml"))
    testImplementation(project(":bosca-core:core"))
    testImplementation(libs.kotlinx.coroutines.core)
    // JTE brings an older embedded compiler through the server SDK. Match the KGP test harness.
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:${libs.versions.kotlin.get()}")
}

// Real producer/consumer TestKit builds use workspace compiler/runtime artifacts, without publishing
// them to Maven Local or requiring a released compiler that already implements the new manifest.
val libraryFixtureClasspath = configurations.create("libraryFixtureClasspath") {
    isCanBeConsumed = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}
dependencies {
    add(libraryFixtureClasspath.name, project(":bml:bml-compiler"))
    add(libraryFixtureClasspath.name, project(":bml:bml-annotations"))
}
tasks.test {
    dependsOn(libraryFixtureClasspath)
    inputs.files(libraryFixtureClasspath).withPropertyName("libraryFixtureClasspath").withNormalizer(ClasspathNormalizer::class.java)
    systemProperty("bml.libraryFixtureClasspath", libraryFixtureClasspath.asPath)
    inputs.files(fileTree("../bml-runtime/tools"), fileTree("../bml-runtime/src"), file("../bml-runtime/package-lock.json"))
        .withPropertyName("bmlClientToolchain").withPathSensitivity(PathSensitivity.RELATIVE)
}

val developmentFixtureClasspath = configurations.create("developmentFixtureClasspath") {
    isCanBeConsumed = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}
dependencies {
    add(developmentFixtureClasspath.name, project(":bml:bml-server"))
    add(developmentFixtureClasspath.name, libs.logback.classic)
}
tasks.test {
    dependsOn(developmentFixtureClasspath)
    inputs.files(developmentFixtureClasspath).withPropertyName("developmentFixtureClasspath")
        .withNormalizer(ClasspathNormalizer::class.java)
    systemProperty("bml.developmentFixtureClasspath", developmentFixtureClasspath.asPath)
}

// Exercise the real GraphQL plugin's independently generated browser output in cache tests.
val graphqlFixturePlugin = configurations.create("graphqlFixturePlugin") {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies {
    add(graphqlFixturePlugin.name, project(":bosca-graphql:bosca-graphql-gradle"))
}
tasks.pluginUnderTestMetadata {
    pluginClasspath.from(graphqlFixturePlugin)
}

// Source-free DI fixtures publish real KSP-generated providers and compiled state serializers.
val diFixtureProcessors = configurations.create("diFixtureProcessors") {
    isCanBeConsumed = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}
val diFixturePlugins = configurations.create("diFixturePlugins") { isCanBeConsumed = false }
dependencies {
    add(diFixtureProcessors.name, project(":services-di:di-ksp"))
    add(diFixtureProcessors.name, project(":services-di:service-ksp"))
    add(diFixturePlugins.name, "com.google.devtools.ksp:symbol-processing-gradle-plugin:${libs.versions.ksp.get()}")
    add(diFixturePlugins.name, "org.jetbrains.kotlin:kotlin-serialization:${libs.versions.kotlin.get()}")
}
tasks.pluginUnderTestMetadata { pluginClasspath.from(diFixturePlugins) }
tasks.test {
    dependsOn(diFixtureProcessors)
    inputs.files(diFixtureProcessors).withPropertyName("diFixtureProcessors").withNormalizer(ClasspathNormalizer::class.java)
    systemProperty("bml.diFixtureProcessors", diFixtureProcessors.asPath)
}

gradlePlugin {
    plugins {
        create("bml") {
            id = "io.bosca.bml"
            implementationClass = "bosca.bml.gradle.BmlGradlePlugin"
        }
        create("bmlHost") {
            id = "io.bosca.bml.host"
            implementationClass = "bosca.bml.gradle.BmlHostGradlePlugin"
        }
    }
}

tasks.processResources {
    val pluginResourceVersion = publishVersion
    inputs.property("bmlPluginVersion", pluginResourceVersion)
    filesMatching("bosca/bml/gradle/bml-gradle-plugin.properties") {
        expand("pluginVersion" to pluginResourceVersion)
    }
}


tasks.register("cleanProjects") {
    description = "Run clean in all projects"
    dependsOn(
        subprojects.mapNotNull { runCatching { it.tasks.named("clean") }.getOrNull() }
    )
}
