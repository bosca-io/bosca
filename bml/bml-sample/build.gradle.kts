plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.kover)
    id("io.bosca.bml")
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

// Integration test for the BML K2 compiler plugin (Volt's `:example` pattern): applying
// `io.bosca.bml` wires the compiler plugin onto this module's compilation and points it at
// `src/main/bml`. The plugin generates `bml.generated.*Page` in-memory (no `.kt` files); if
// Consumer.kt compiles, the plugin fired in a real Gradle build and the embedded Kotlin resolved.
dependencies {
    implementation(project(":bml:core-bml"))        // RenderContext / HtmlWriter (generated code calls these)
    implementation(project(":bml:bml-annotations"))  // @BmlPage / @BmlGenerated (generated code references these)
    implementation(project(":bml:bml-server"))       // BmlServer — run this sample as an actual SSR server
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)  // @Serializable on the sample's island state model
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// `./gradlew :bml:bml-sample:bmlDev` serves the sample with hot reload at http://localhost:9090/.
application {
    mainClass.set("bosca.bml.sample.MainKt")
}

// Point the client-TS bundler at the in-repo @bosca/bml runtime (not installed in node_modules here),
// so `bmlBundleClient` bundles the generated TypeScript into browser JS end-to-end.
bml {
    clientBundler.set(rootProject.layout.projectDirectory.file("bml/bml-runtime/tools/bundle.mjs"))
    clientRuntime.set(rootProject.layout.projectDirectory.file("bml/bml-runtime/src/index.ts"))
    // Dump the generated Kotlin to build/generated/bml/kotlin so it's inspectable/debuggable (opt-in).
    generateKotlinSources.set(true)
}
