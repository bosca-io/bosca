plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.plugin.serialization)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    api(project(":bosca-core:core"))
    api(project(":bosca-core:core-storage"))

    testImplementation(libs.kotlin.test.junit)
}
