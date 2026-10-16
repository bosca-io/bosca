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

gradlePlugin {
    plugins {
        create("bml") {
            id = "io.bosca.bml"
            implementationClass = "bosca.bml.gradle.BmlGradlePlugin"
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
