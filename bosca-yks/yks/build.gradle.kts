@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.kover)
}

apply(plugin = "com.android.kotlin.multiplatform.library")

kotlin {
    extensions.configure<com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget>("androidLibrary") {
        namespace = "io.bosca.yks"
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
            baseName = "BoscaYks"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlin.test)
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
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}
