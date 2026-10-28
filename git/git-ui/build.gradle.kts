plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.plugin.serialization)
    id("io.bosca.bml")
    id("io.bosca.graphql")
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
}

dependencies {
    "boscaGraphqlGenerator"(project(":bosca-graphql:bosca-graphql-client"))
    api(project(":bml:bml-server"))
    api(project(":bml:bml-design"))
    api(project(":bosca-core:core"))
    implementation(project(":server:atlas-ui"))
    implementation(project(":bml:core-bml"))
    implementation(project(":bml:bml-annotations"))
    implementation(project(":bosca-graphql:bosca-graphql-client"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.logback.classic)
}

bml {
    generateKotlinSources.set(true)
    clientRuntime.set(layout.projectDirectory.file("../../bml/bml-runtime/src/index.ts"))
}

tasks.named("bmlInstallClientDependencies") {
    inputs.files(fileTree("../../bml/bml-runtime") { include("package.json", "src/**", "tools/**") })
    inputs.files(fileTree("../../web/packages/auth") { include("package.json", "src/**") })
    inputs.files(fileTree("../../server/atlas-ui") { include("package.json", "src/main/client/**") })
}

tasks.named("bmlBundleClient") {
    inputs.file("tsconfig.json").withPropertyName("clientTypeScriptConfig")
    inputs.files(fileTree("../../server/atlas-ui/src/main/client"))
        .withPropertyName("atlasClientSources").withPathSensitivity(PathSensitivity.RELATIVE)
}

val typecheckClient by tasks.registering(Exec::class) {
    group = "verification"
    description = "Type-check the Git UI client and generated BML islands."
    dependsOn("compileKotlin", "bmlPrepareClient", "bmlInstallClientDependencies")
    workingDir(projectDir)
    commandLine(bml.nodeExecutable.get(), layout.projectDirectory.file("node_modules/typescript/bin/tsc").asFile.absolutePath, "--noEmit")
}

val testClient by tasks.registering(Exec::class) {
    group = "verification"
    description = "Test Git pipeline log behavior."
    dependsOn("bmlInstallClientDependencies")
    workingDir(projectDir)
    commandLine(bml.nodeExecutable.get(), "--test", "src/test/client-pipeline-logs.test.mjs")
}

tasks.named("check") { dependsOn(typecheckClient, testClient) }

boscaGraphql {
    packageName.set("bosca.git.ui.graphql")
    generateTypeScript.set(false)
    endpoint.set(providers.environmentVariable("BML_GRAPHQL_ENDPOINT").orElse("http://localhost:8080/graphql"))
}

tasks.test {
    filter { excludeTestsMatching("bosca.git.ui.GitUiNativeHostTest") }
    systemProperty("workspaceRoot", rootProject.projectDir.absolutePath)
    inputs.files(rootProject.fileTree(rootProject.projectDir) {
        include("**/src/main/resources/**/*.graphqls")
        exclude("**/build/**", "**/node_modules/**", "**/.intellijPlatform/**")
    })
}

tasks.register<Test>("nativeUiHostTest") {
    group = "verification"
    description = "Exercise the production native Git UI host with isolated API and browser fixtures."
    dependsOn(tasks.testClasses, ":git:git-ui-server:nativeCompile")
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("bosca.git.ui.GitUiNativeHostTest") }
    val nativeDirectory = project(":git:git-ui-server").layout.buildDirectory.dir("native/nativeCompile")
    val executableName = "bosca-git-ui-server" +
        if (org.gradle.internal.os.OperatingSystem.current().isWindows) ".exe" else ""
    val executable = nativeDirectory.map { it.file(executableName) }
    val resources = nativeDirectory.map { it.file("embedded-resources.json") }
    val buildId = project(":git:git-ui-server").layout.buildDirectory.file("generated/bml/build-id/META-INF/bosca/bml/build-id")
    inputs.file(executable).withPropertyName("nativeUiExecutable").withPathSensitivity(PathSensitivity.NONE)
    inputs.file(resources).withPropertyName("nativeUiResources").withPathSensitivity(PathSensitivity.NONE)
    inputs.file(buildId).withPropertyName("nativeUiBuildId").withPathSensitivity(PathSensitivity.NONE)
    inputs.dir(layout.projectDirectory.dir("src/test/browser"))
        .withPropertyName("nativeBrowserChecks").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("gitUi.native.binary", executable.get().asFile.absolutePath)
    systemProperty("gitUi.native.resourceReport", resources.get().asFile.absolutePath)
    systemProperty("gitUi.native.buildId", buildId.get().asFile.absolutePath)
    systemProperty("gitUi.native.browser", layout.projectDirectory.file("src/test/browser/run.mjs").asFile.absolutePath)
    systemProperty("gitUi.native.node", bml.nodeExecutable.get())
    outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("runBrowserFixture") {
    group = "verification"
    description = "Run the isolated Git UI HTTP/WebSocket fixture for browser checks."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("bosca.git.ui.GitUiBrowserFixture")
    jvmArgs("--enable-native-access=ALL-UNNAMED", "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED")
    outputs.upToDateWhen { false }
}


tasks.register<JavaExec>("probePageLoading") {
    group = "verification"
    description = "Measure isolated Git page loading at zero and 50 ms API latency and check burst connection reuse."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("bosca.git.ui.GitUiPageLoadingProbe")
    jvmArgs("--enable-native-access=ALL-UNNAMED", "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED")
    outputs.upToDateWhen { false }
}
