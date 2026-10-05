@file:OptIn(ExperimentalWasmDsl::class, ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.kotlin.plugin.serialization)
}

apply(plugin = "com.android.kotlin.multiplatform.library")

kotlin {
    applyDefaultHierarchyTemplate {
        common {
            group("web") {
                withJs()
                withWasmJs()
            }
        }
    }

    extensions.configure<com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget>("androidLibrary") {
        namespace = "io.bosca.di"
        compileSdk = 36
    }

    jvm("desktop")

    js {
        browser {
            testTask {
                useKarma {
                    useChromeHeadless()
                }
                enabled = false
            }
        }
        nodejs()
        binaries.executable()
    }

    wasmJs {
        browser {
            testTask {
                useKarma {
                    useChromeHeadless()
                }
                enabled = false
            }
        }
        nodejs()
        binaries.executable()
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            baseName = "BoscaDiKit"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }

        val androidMain by getting {
            dependencies {

            }
        }

        val desktopMain by getting {
            dependencies {

            }
        }

        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlin.test)
        }
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}
