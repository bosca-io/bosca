plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.jte.gradle)
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
    implementation(project(":social:core-profile"))
    implementation(project(":content:core-languages"))
    implementation(project(":content:core-localization"))
    implementation(project(":bosca-core:core-configuration"))
    implementation(project(":content:core-content"))
    implementation(project(":bosca-core:core-storage"))
    implementation(project(":content:slugs"))

    implementation(project(":bosca-core:security"))

    implementation(libs.logback.classic)

    implementation(libs.jte.kotlin)
    jteGenerate(libs.jte.models)

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)
}

ksp {
    arg("ProviderRegistrarPrefix", "MessagesPages")
}

afterEvaluate {
    tasks.findByName("kspKotlin")?.dependsOn("generateJte")
}

jte {
    precompile()
    generate()
    packageName = "bosca.messages.pages.jte"
    jteExtension("gg.jte.models.generator.ModelExtension") {
        property("language", "Kotlin")
    }
}
