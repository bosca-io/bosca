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
    api(project(":social:core-chat"))
    implementation(project(":bosca-core:core"))
    implementation(project(":bosca-core:core-security"))
    implementation(project(":social:core-profile"))
    implementation(project(":communications:core-communications"))
    implementation(project(":sharedqueue:sharedqueue"))


    implementation(libs.kotlinx.datetime)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))
}

ksp {
    arg("ProviderRegistrarPrefix", "CoreCommunity")
}
