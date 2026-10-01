plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    implementation(project(":services-di:base-ksp"))
    implementation(project(":bosca-graphql:bosca-graphql-server"))
    implementation(libs.kotlinx.serialization.core)
    implementation(project(":services-di:di"))
    implementation(project(":bosca-core:core-annotations"))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}
