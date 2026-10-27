plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.kover)
    id("org.graalvm.buildtools.native")
    alias(libs.plugins.shadow)
    application
    id("io.bosca.bml.host")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

application {
    mainClass = "bosca.git.server.ApplicationKt"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "-XX:+UseG1GC")
}

tasks.named<JavaExec>("run") {
    outputs.upToDateWhen { false }
}

val projects = arrayOf(
    project(":bosca-core:core"),
    project(":pipelines:core-pipelines"),
    project(":git:core-git"),
    project(":git:core-git-ci"),
    project(":bosca-core:core-security"),
    project(":social:core-profile"),
    project(":social:profile"),
    project(":bosca-core:security"),
    project(":bosca-core:core-scheduler"),
    project(":bosca-core:scheduler"),
    project(":pipelines:pipelines"),
    project(":bosca-core:core-storage"),
    project(":bosca-core:storage"),
    project(":content:core-content"),
    project(":content:content"),
    project(":search:core-search"),
    project(":search:search"),
    project(":content:slugs"),
    project(":analytics:analytics-server-client"),
    project(":bosca-core:core-graalvm"),
    project(":git:git"),
    project(":git:git-ci"),
    project(":git:git-jobs"),
)

configurations.all {
    exclude(group = "org.jline", module = "jline")
    // Drop netty-all (legacy fat aggregator via Hadoop/Iceberg) so stale 4.1.x natives don't
    // shadow the catalog Netty version; epoll comes in cleanly via :bosca-core:core.
    exclude(group = "io.netty", module = "netty-all")
}

dependencies {
    projects.forEach { implementation(it) }

    implementation(project(":sharedqueue:sharedqueue"))
    implementation(project(":git:git-ui-server"))
    implementation(libs.jgit)
    implementation(libs.logback.classic)
    api(libs.postgresql)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)
    testImplementation(project(":bosca-core:test-support"))
    testImplementation(project(":bml:core-bml"))
    testImplementation(project(":bml:bml-server"))

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))
}

tasks.test {
    val serverClasspath = sourceSets.main.get().runtimeClasspath
    doFirst { systemProperty("apiServerClasspath", serverClasspath.asPath) }
    systemProperty("workspaceRoot", rootProject.projectDir.absolutePath)
}

tasks.register<Test>("nativeHostTest") {
    group = "verification"
    description = "Exercise both production Git listeners from the combined native executable."
    dependsOn(tasks.testClasses, "nativeCompile")
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("bosca.git.server.GitServerIntegrationTest") }
    val nativeDirectory = layout.buildDirectory.dir("native/nativeCompile")
    val executableName = "bosca-git-server" +
        if (org.gradle.internal.os.OperatingSystem.current().isWindows) ".exe" else ""
    val executable = nativeDirectory.map { it.file(executableName) }
    val resources = nativeDirectory.map { it.file("embedded-resources.json") }
    val buildIdPath = "generated/bml/build-id/META-INF/bosca/bml/build-id"
    val hostBuildId = layout.buildDirectory.file(buildIdPath)
    val uiBuildId = project(":git:git-ui-server").layout.buildDirectory.file(buildIdPath)
    inputs.file(executable).withPropertyName("nativeExecutable").withPathSensitivity(PathSensitivity.NONE)
    inputs.file(resources).withPropertyName("nativeResources").withPathSensitivity(PathSensitivity.NONE)
    inputs.files(hostBuildId, uiBuildId).withPropertyName("nativeBuildIds").withPathSensitivity(PathSensitivity.NONE)
    environment("GIT_SERVER_TEST_BINARY", executable.get().asFile.absolutePath)
    systemProperty("workspaceRoot", rootProject.projectDir.absolutePath)
    systemProperty("gitServer.native.resourceReport", resources.get().asFile.absolutePath)
    systemProperty("gitServer.native.hostBuildId", hostBuildId.get().asFile.absolutePath)
    systemProperty("gitServer.native.uiBuildId", uiBuildId.get().asFile.absolutePath)
    systemProperty("gitServer.test.artifacts", layout.buildDirectory.dir("reports/native-host").get().asFile.absolutePath)
    outputs.upToDateWhen { false }
}

ksp {
    arg("ProviderRegistrarPrefix", "GitServer")
}

tasks.shadowJar {
    isZip64 = true
    // Merged resources must reach their transformers before duplicate filtering.
    filesMatching(listOf("META-INF/services/**", "META-INF/bosca/bml/build-id")) {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    mergeServiceFiles()
    append("META-INF/bosca/bml/build-id")
}

afterEvaluate {
    configurations.matching { it.name.startsWith("nativeImageTest") }.configureEach {
        setExtendsFrom(emptyList())
    }
}

graalvmNative {
    testSupport.set(false)
    agent {
        enabled.set(false)
        metadataCopy {
            outputDirectories.add("src/main/resources/META-INF/native-image")
            mergeWithExisting.set(true)
        }
    }
    metadataRepository {
        enabled = true
        excludedModules.addAll(listOf(
            "net.bytebuddy:byte-buddy",
            "net.bytebuddy:byte-buddy-agent",
            "io.mockk:mockk",
            "io.mockk:mockk-jvm",
            "org.objenesis:objenesis",
            "org.junit.jupiter:junit-jupiter-api",
            "org.junit.platform:junit-platform-launcher",
            "org.jetbrains.kotlin:kotlin-test",
            "org.jetbrains.kotlin:kotlin-test-junit",
            "org.conscrypt:conscrypt-openjdk-uber",
            "io.grpc:grpc-netty-shaded"
        ))
    }
    binaries {
        named("main") {
            imageName = "bosca-git-server"
            mainClass = "bosca.git.server.ApplicationKt"
            buildArgs.add("-O3")
            buildArgs.add("-H:+GenerateEmbeddedResourcesFile")
            buildArgs.addAll(providers.gradleProperty("bosca.native.march")
                .map { listOf("-march=$it") }
                .orElse(emptyList()))
            buildArgs.add("--exclude-config")
            buildArgs.add(".*/.*.jar")
            buildArgs.add("^/META-INF/native-image/org\\.jline/.*")
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
