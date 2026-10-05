plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kover)
    // `maven-publish` + the `maven` publication (artifactId `bml-compiler`, from the java
    // component) are applied to every module by the root `allprojects` block.
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
    }
}

val kotlinVersion = libs.versions.kotlin.get()

// KEFS / IDE build (mirrors Volt's `volt.ideKotlinVersion`): cross-compile the K2 plugin
// against the IDE's internal Kotlin compiler so the published FIR extension matches IntelliJ.
//   Build version (project Kotlin):  ./gradlew :bml:bml-compiler:publishMavenPublicationToLocalRepository
//   IDE version   (KEFS):            … -Pbml.ideKotlinVersion=<ide-kotlin-version>
val ideKotlinVersion: String? = providers.gradleProperty("bml.ideKotlinVersion").orNull
val compilerVersion = ideKotlinVersion ?: kotlinVersion
if (ideKotlinVersion != null) {
    version = "$ideKotlinVersion-$version" // e.g. 2.3.21-0.0.1, distinct from the build artifact
}

val publishedCompilerVersion = version.toString()
tasks.processResources {
    val compilerResourceVersion = publishedCompilerVersion
    inputs.property("bmlCompilerVersion", compilerResourceVersion)
    filesMatching("bosca/bml/compiler/bml-compiler.properties") {
        expand("compilerVersion" to compilerResourceVersion)
    }
}

// the .bml frontend (lexer/parser/AST + spans) — pure Kotlin.
// the K2 compiler plugin (FIR + IR), compiled against the Kotlin
// compiler. The compiler is `compileOnly` — it is provided by kotlinc at runtime.
// Plugin registration is via META-INF/services (no AutoService dependency).
dependencies {
    implementation(project(":bml:core-bml"))   // tag registry used by the codegen
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:$compilerVersion")

    testImplementation(libs.kotlin.test.junit)
    // End-to-end test: generate .kt, compile it with the embedded compiler, run render().
    testImplementation(project(":bml:bml-annotations"))
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:$kotlinVersion")
    testImplementation(libs.kotlin.reflect)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.serialization.json) // email e2e builds a BmlMessageContext payload
}

// Add the local Maven repo KEFS points at (Settings > Tools > Kotlin External FIR Support),
// alongside the root's optional Bosca registry. Task: publishMavenPublicationToLocalRepository.
