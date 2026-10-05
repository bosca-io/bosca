@file:OptIn(ExperimentalWasmDsl::class, ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.targets.native.tasks.PodBuildTask

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.kotlin.plugin.serialization)
    id("org.jetbrains.kotlin.native.cocoapods")
}

// auth-shared is bosca-core's only KMP module. Its Android target is gated on
// SDK availability (set in settings.gradle.kts) so a JVM-only checkout still
// configures and builds the rest of bosca-core unchanged.
val androidEnabled = gradle.extra["androidEnabled"] as Boolean
if (androidEnabled) {
    apply(plugin = "com.android.kotlin.multiplatform.library")
}

kotlin {
    applyDefaultHierarchyTemplate {
        common {
            group("web") {
                withJs()
                withWasmJs()
            }
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
        freeCompilerArgs.add("-opt-in=kotlin.time.ExperimentalTime")
    }

    if (androidEnabled) {
        extensions.configure<com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget>("androidLibrary") {
            namespace = "io.bosca.auth"
            compileSdk = 36
        }
    }

    // Plain `jvm()` (NOT jvm("desktop")): publishes the standard `-jvm` variant
    // so the plain-JVM / GraalVM CLI resolves it by attributes. KMP consumers
    // resolve the same variant for their desktop targets.
    jvm()

    js {
        browser {
            testTask {
                useKarma { useChromeHeadless() }
                enabled = false
            }
        }
        nodejs()
        binaries.executable()
    }

    wasmJs {
        browser {
            testTask {
                useKarma { useChromeHeadless() }
                enabled = false
            }
        }
        nodejs()
        binaries.executable()
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach {
        it.binaries.framework {
            baseName = "BoscaAuthKit"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }

        if (androidEnabled) {
            val androidMain by getting {
                dependencies {
                    implementation(libs.androidx.security.crypto)
                    api(libs.ktor.client.android)
                }
            }
        }

        val jvmMain by getting {
            dependencies {
                api(libs.ktor.client.java)
            }
        }

        val iosMain by getting {
            dependencies {
                api(libs.ktor.client.darwin)
            }
        }

        val webMain by getting {
            dependencies {
                implementation(libs.kotlinx.browser)
                api(libs.ktor.client.js)
            }
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

val iosDeploymentTarget = "18.2"

kotlin {
    cocoapods {
        version = "1.0"
        ios.deploymentTarget = iosDeploymentTarget

        pod("GoogleSignIn")
    }
}

tasks.withType<PodBuildTask>().configureEach {
    xcodeBuildSettings.put("IPHONEOS_DEPLOYMENT_TARGET", iosDeploymentTarget)
}

// Kotlin resolves commonized C-interop outputs during metadata compilation (KT-76147).
tasks.matching { it.name == "compileIosMainKotlinMetadata" }.configureEach {
    notCompatibleWithConfigurationCache(
        "Kotlin C-interop output resolution accesses Task.project during metadata compilation (KT-76147)"
    )
}
