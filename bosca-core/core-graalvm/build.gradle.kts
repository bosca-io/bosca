plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    compileOnly(libs.graalvm.nativeimage)

    testImplementation(libs.caffeine)
    testImplementation(libs.kotlin.test.junit)
}
