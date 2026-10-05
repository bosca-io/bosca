plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

application {
    mainClass.set("bosca.scripting.host.HostKt")
}

dependencies {
    implementation(project(":scripting:scripting-engine"))
    implementation(project(":bosca-core:core"))
    implementation(project(":scripting:core-scripting"))
    implementation(project(":content:core-content"))
    implementation(project(":content:content"))
    implementation(project(":bosca-core:configuration"))

    implementation(libs.kotlin.scripting.common)
    implementation(libs.kotlin.scripting.jvm)
    implementation(libs.kotlin.scripting.jvm.host)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}
