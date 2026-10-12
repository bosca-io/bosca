plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kover)
    application
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

// the dedicated SSR server composition root. Lean — bosca-core's
// Netty engine and the BML render runtime only; no domain registrars.
// Data uses an HTTP GraphQL endpoint or a host-provided GraphQL backend.
dependencies {
    implementation(project(":bosca-core:core"))
    implementation(project(":bml:core-bml"))
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)
}

application {
    mainClass.set("bosca.bml.server.MainKt")
}
