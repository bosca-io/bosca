plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.kover)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    api(project(":bosca-kmp:bible-dom-shared"))
    api(project(":content:core-content"))
    api(project(":bosca-core:core"))

    testImplementation(project(":content:bible-compiler"))
    testImplementation(libs.kotlin.test.junit)
}
