plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    implementation(libs.kotlin.ksp)
    implementation(project(":services-di:di"))
    implementation(project(":services-di:base-ksp"))
    implementation(libs.kotlinpoet.ksp)
}
