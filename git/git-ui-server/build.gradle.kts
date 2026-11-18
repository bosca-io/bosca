plugins {
    id("org.jetbrains.kotlin.jvm")
    application
    id("io.bosca.bml.host")
    id("org.graalvm.buildtools.native")
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
}

dependencies {
    implementation(project(":git:git-ui"))
    implementation(project(":bosca-core:core-graalvm"))
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)
    runtimeOnly(libs.logback.classic)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":bosca-core:test-support"))
    testImplementation(project(":bml:core-bml"))
}

application {
    mainClass.set("bosca.git.ui.server.ApplicationKt")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.named<JavaExec>("run") {
    outputs.upToDateWhen { false }
}

graalvmNative {
    testSupport.set(false)
    agent { enabled.set(false) }
    metadataRepository { enabled = true }
    binaries {
        named("main") {
            imageName.set("bosca-git-ui-server")
            mainClass.set("bosca.git.ui.server.ApplicationKt")
            buildArgs.add("-O3")
            buildArgs.add("-H:+GenerateEmbeddedResourcesFile")
            buildArgs.addAll(providers.gradleProperty("bosca.native.march")
                .map { listOf("-march=$it") }
                .orElse(emptyList()))
            buildArgs.addAll(listOf("--exclude-config", ".*/.*.jar", "^/META-INF/native-image/org\\.jline/.*"))
            if (org.gradle.internal.os.OperatingSystem.current().isLinux) {
                buildArgs.add("--gc=G1")
            }
        }
    }
}

tasks.named("nativeCompile") {
    val embeddedResources = layout.buildDirectory.file("native/nativeCompile/embedded-resources.json")
    outputs.file(embeddedResources).withPropertyName("embeddedResources")
    doFirst {
        val report = embeddedResources.get().asFile
        check(!report.exists() || report.delete()) { "Cannot clear the native resource report" }
    }
    doLast {
        check(embeddedResources.get().asFile.isFile) { "Native compilation did not emit its resource report" }
    }
}
