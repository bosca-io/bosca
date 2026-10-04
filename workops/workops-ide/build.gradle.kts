import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    java
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

group = "io.bosca"
version = "0.1.0"

dependencies {
    implementation(project(":bosca-kmp:auth-shared"))
    implementation(project(":bosca-graphql:bosca-graphql-client"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.java)
    implementation(libs.ktor.client.websockets)
    intellijPlatform {
        intellijIdeaUltimate("2026.1.2") {
            useInstaller = false
        }
        bundledPlugin("Git4Idea")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation(libs.junit)
}

intellijPlatform {
    pluginConfiguration {
        name = "Bosca"
        ideaVersion {
            sinceBuild = "261"
        }
    }
}

kotlin {
    jvmToolchain(25)
    compilerOptions.jvmTarget = JvmTarget.JVM_21
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}
