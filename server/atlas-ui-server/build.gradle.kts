plugins {
    id("org.jetbrains.kotlin.jvm")
    application
    id("io.bosca.bml.host")
    id("org.graalvm.buildtools.native")
}

description = "Bosca Atlas BML host and same-origin proxies"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
}

dependencies {
    api(project(":server:atlas-ui"))
    implementation(project(":git:git-ui"))
    implementation(project(":artifacts:artifacts-ui"))
    implementation(project(":git:git-ui-server"))
    implementation(project(":bosca-core:core-graalvm"))
    runtimeOnly(libs.logback.classic)
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":bosca-core:test-support"))
}

application {
    mainClass.set("bosca.atlas.ui.server.ApplicationKt")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.named<JavaExec>("run") { outputs.upToDateWhen { false } }

graalvmNative {
    testSupport.set(false)
    agent { enabled.set(false) }
    metadataRepository { enabled = true }
    binaries {
        named("main") {
            imageName.set("bosca-atlas-ui-server")
            mainClass.set("bosca.atlas.ui.server.ApplicationKt")
            buildArgs.add("-O3")
            buildArgs.addAll(providers.gradleProperty("bosca.native.march").map { listOf("-march=$it") }.orElse(emptyList()))
            buildArgs.addAll(listOf("--exclude-config", ".*/.*.jar", "^/META-INF/native-image/org\\.jline/.*"))
            if (org.gradle.internal.os.OperatingSystem.current().isLinux) buildArgs.add("--gc=G1")
        }
    }
}
