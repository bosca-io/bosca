plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.kover)
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
    // Generated <inject> bindings call Bosca DI's suspend provide<T>() API. Expose the small DI
    // contract so every BML consumer can compile generated renderers without an extra dependency.
    api(project(":services-di:di"))
    implementation(project(":bosca-core:core"))
    implementation(libs.slf4j.api)                    // i18n warn-once diagnostics
    implementation(libs.kotlinx.serialization.json)   // compiled-snapshot model
    implementation(libs.kotlinx.coroutines.core)      // GraphQL data-plane client
    implementation(libs.ksoup)                        // HTML parsing for email plain-text projection
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)                       // GraphQL data-plane transport (Bosca's standard HTTP client)
    // The typed-operation bridge (GraphQLClient.execute(BoscaOperation)) — api so every BML site
    // gets the generated-operation types alongside the data-plane client.
    api(project(":bosca-graphql:bosca-graphql-client"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))
}

ksp {
    arg("ProviderRegistrarPrefix", "CoreBml")
}
