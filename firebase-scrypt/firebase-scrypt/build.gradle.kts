import gobley.gradle.GobleyHost
import gobley.gradle.Variant
import gobley.gradle.cargo.dsl.*
import gobley.gradle.rust.CrateType
import gobley.gradle.rust.targets.RustJvmTarget
import org.gradle.jvm.tasks.Jar

plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.kover)
    id("dev.gobley.cargo") version "0.3.7"
    id("dev.gobley.uniffi") version "0.3.7"
    kotlin("plugin.atomicfu") version libs.versions.kotlin

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

dependencies {
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)
}

cargo {
    builds.jvm {
        embedRustLibrary = (GobleyHost.current.rustTarget == rustTarget)
        jvmVariant = Variant.Release
        jvmPublishingVariant = Variant.Release
    }
    packageDirectory = layout.projectDirectory.dir("src/main/rust")
}

// Gobley exposes the JVM native library as a local runtime-only classifier JAR. File
// dependencies are not represented in published module metadata, so consumers otherwise
// receive the UniFFI bindings without the shared library they load at runtime.
val hostRustTarget = GobleyHost.current.rustTarget as RustJvmTarget
val hostRustRuntimeJar = tasks.named<Jar>(
    "jarJvmRustRuntime${hostRustTarget.friendlyName}Release"
)
val hostNativeLibraryPath = "${hostRustTarget.jnaResourcePrefix}/${requireNotNull(
    hostRustTarget.outputFileName("firebase_scrypt_util", CrateType.SystemDynamicLibrary)
)}"
val mainJar = tasks.named<Jar>("jar") {
    dependsOn(hostRustRuntimeJar)
    from(zipTree(hostRustRuntimeJar.flatMap { it.archiveFile })) {
        include("${hostRustTarget.jnaResourcePrefix}/**")
    }
}

val verifyHostNativeLibraryInJar = tasks.register("verifyHostNativeLibraryInJar") {
    group = "verification"
    description = "Verifies that the published JAR contains the host Firebase Scrypt library."
    dependsOn(mainJar)

    val archiveFile = mainJar.flatMap { it.archiveFile }
    val nativeLibraryPath = hostNativeLibraryPath
    val nativeLibrary = zipTree(archiveFile).matching { include(nativeLibraryPath) }
    inputs.file(archiveFile)
    inputs.property("nativeLibraryPath", nativeLibraryPath)

    doLast {
        check(!nativeLibrary.isEmpty) {
            "Native library $nativeLibraryPath is missing from ${archiveFile.get().asFile.name}"
        }
    }
}

tasks.named("check") {
    dependsOn(verifyHostNativeLibraryInJar)
}
