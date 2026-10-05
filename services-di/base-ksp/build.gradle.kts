plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    api(libs.kotlin.ksp)
    api(libs.kotlinpoet.ksp)
}
