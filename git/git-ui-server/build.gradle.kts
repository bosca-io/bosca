plugins {
    id("org.jetbrains.kotlin.jvm")
    application
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
    testImplementation(project(":git:git-server"))
}

application {
    mainClass.set("bosca.git.ui.server.ApplicationKt")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.named<JavaExec>("run") {
    outputs.upToDateWhen { false }
}

tasks.test {
    val uiClasspath = sourceSets.main.get().runtimeClasspath
    val apiClasspath = sourceSets.test.get().runtimeClasspath
    doFirst {
        systemProperty("uiServerClasspath", uiClasspath.asPath)
        systemProperty("apiServerClasspath", apiClasspath.asPath)
    }
    systemProperty("workspaceRoot", rootProject.projectDir.absolutePath)
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
