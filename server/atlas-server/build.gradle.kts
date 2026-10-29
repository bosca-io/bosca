plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.plugin.serialization)
    id("org.graalvm.buildtools.native")
    alias(libs.plugins.shadow)
    application
    id("io.bosca.bml.host")
}

description = "Bosca Atlas backend composition"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
}

dependencies {
    implementation(project(":bosca-core:core"))
    implementation(project(":bosca-core:core-security"))
    implementation(project(":bosca-core:security"))
    implementation(project(":bosca-core:core-storage"))
    implementation(project(":bosca-core:storage"))
    implementation(project(":bosca-core:core-scheduler"))
    implementation(project(":bosca-core:scheduler"))
    implementation(project(":social:core-profile"))
    implementation(project(":social:profile"))
    implementation(project(":content:core-content"))
    implementation(project(":content:content"))
    implementation(project(":content:slugs"))
    implementation(project(":search:core-search"))
    implementation(project(":search:search"))
    implementation(project(":pipelines:core-pipelines"))
    implementation(project(":pipelines:pipelines"))
    implementation(project(":git:core-git"))
    implementation(project(":git:core-git-ci"))
    implementation(project(":git:git"))
    implementation(project(":git:git-ci"))
    implementation(project(":git:git-jobs"))
    implementation(project(":artifacts:core-artifacts"))
    implementation(project(":artifacts:artifacts-base"))
    implementation(project(":artifacts:artifacts-git"))
    implementation(project(":artifacts:artifacts-installer"))
    implementation(project(":artifacts:artifacts-admin"))
    implementation(project(":artifacts:artifacts-docker"))
    implementation(project(":artifacts:artifacts-helm"))
    implementation(project(":artifacts:artifacts-maven"))
    implementation(project(":artifacts:artifacts-npm"))
    implementation(project(":artifacts:artifacts-raw"))
    implementation(project(":artifacts:artifacts-ml"))
    implementation(project(":sharedqueue:sharedqueue"))
    implementation(project(":analytics:analytics-server-client"))
    implementation(project(":server:atlas-ui-server"))
    implementation(project(":bosca-core:core-graalvm"))
    implementation(libs.logback.classic)
    implementation(libs.jgit)
    implementation(libs.postgresql)

    ksp(project(":bosca-core:core-ksp"))
    ksp(project(":services-di:service-ksp"))
    ksp(project(":services-di:di-ksp"))

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":bosca-core:test-support"))
    testImplementation(libs.mockk)
    testImplementation(project(":bml:core-bml"))
    testImplementation(project(":bml:bml-server"))
}

ksp {
    arg("ProviderRegistrarPrefix", "Atlas")
}

application {
    mainClass.set("bosca.atlas.server.ApplicationKt")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "-XX:+UseG1GC")
}

tasks.named<JavaExec>("run") { outputs.upToDateWhen { false } }

tasks.test {
    val serverClasspath = sourceSets.main.get().runtimeClasspath
    doFirst { systemProperty("atlasServerClasspath", serverClasspath.asPath) }
    systemProperty("workspaceRoot", rootProject.projectDir.absolutePath)
    systemProperty("atlas.artifacts.browser", providers.gradleProperty("atlas.artifacts.browser").getOrElse("false"))
    inputs.file(rootProject.file("artifacts/artifacts-ui/src/test/browser/artifacts.mjs"))
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
}

tasks.register<Test>("nativeHostTest") {
    group = "verification"
    description = "Exercise native Atlas API, registry, workers, and BML hosts with isolated infrastructure."
    dependsOn(tasks.testClasses, "nativeCompile", ":server:atlas-ui-server:nativeCompile")
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        includeTestsMatching("bosca.atlas.server.AtlasServerIntegrationTest")
        includeTestsMatching("bosca.atlas.server.AtlasArtifactsIntegrationTest")
    }
    val extension = if (org.gradle.internal.os.OperatingSystem.current().isWindows) ".exe" else ""
    val backend = layout.buildDirectory.file("native/nativeCompile/bosca-atlas-server$extension")
    val ui = project(":server:atlas-ui-server").layout.buildDirectory.file("native/nativeCompile/bosca-atlas-ui-server$extension")
    inputs.file(backend).withPropertyName("nativeBackend").withPathSensitivity(PathSensitivity.NONE)
    inputs.file(ui).withPropertyName("nativeUi").withPathSensitivity(PathSensitivity.NONE)
    inputs.file(rootProject.file("artifacts/artifacts-ui/src/test/browser/artifacts.mjs"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    environment("ATLAS_SERVER_TEST_BINARY", backend.get().asFile.absolutePath)
    environment("ATLAS_UI_TEST_BINARY", ui.get().asFile.absolutePath)
    systemProperty("workspaceRoot", rootProject.projectDir.absolutePath)
    systemProperty("atlas.artifacts.browser", "true")
    systemProperty("atlas.native.artifacts", layout.buildDirectory.dir("reports/native-host").get().asFile.absolutePath)
    outputs.upToDateWhen { false }
}

configurations.all {
    exclude(group = "org.jline", module = "jline")
    exclude(group = "io.netty", module = "netty-all")
}

tasks.shadowJar {
    isZip64 = true
    filesMatching(listOf("META-INF/services/**", "META-INF/bosca/bml/build-id")) {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    mergeServiceFiles()
    append("META-INF/bosca/bml/build-id")
}

graalvmNative {
    testSupport.set(false)
    agent { enabled.set(false) }
    metadataRepository { enabled = true }
    binaries {
        named("main") {
            imageName.set("bosca-atlas-server")
            mainClass.set("bosca.atlas.server.ApplicationKt")
            buildArgs.add("-O3")
            buildArgs.add("-H:+GenerateEmbeddedResourcesFile")
            buildArgs.addAll(providers.gradleProperty("bosca.native.march").map { listOf("-march=$it") }.orElse(emptyList()))
            buildArgs.addAll(listOf("--exclude-config", ".*/.*.jar", "^/META-INF/native-image/org\\.jline/.*"))
            if (org.gradle.internal.os.OperatingSystem.current().isLinux) buildArgs.add("--gc=G1")
        }
    }
}
