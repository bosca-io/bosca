
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
    implementation(project(":bosca-core:core"))
    implementation(project(":bosca-core:core-security"))
    implementation(project(":communications:core-communications"))
    implementation(project(":pipelines:core-pipelines"))   // contributes a suspendable HubSpot pipeline node
    implementation(project(":social:core-profile"))
    implementation(project(":content:core-languages"))
    implementation(project(":bosca-core:core-configuration"))
    implementation(project(":sharedqueue:sharedqueue"))

    implementation(libs.jsonata)

    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.mockk)
    testImplementation(platform(libs.okhttp.bom))
    testImplementation(libs.okhttp.mockwebserver)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
        
    }
}

ksp {
    arg("ProviderRegistrarPrefix", "HubSpot")
}
