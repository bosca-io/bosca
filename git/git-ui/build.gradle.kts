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
    implementation(project(":bml:core-bml"))
    implementation(project(":bml:bml-annotations"))
    implementation(project(":bosca-graphql:bosca-graphql-client"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

bml {
    generateKotlinSources.set(true)
    clientBundler.set(layout.projectDirectory.file("node_modules/@bosca/bml/tools/bundle.mjs"))
    clientRuntime.set(layout.projectDirectory.file("../../bml/bml-runtime/src/index.ts"))
}

tasks.named("bmlInstallClientDependencies") {
    inputs.files(fileTree("../../bml/bml-runtime") { include("package.json", "src/**", "tools/**") })
    inputs.files(fileTree("../../web/packages/auth") { include("package.json", "src/**") })
}

tasks.named("bmlBundleClient") {
    inputs.file("tsconfig.json").withPropertyName("clientTypeScriptConfig")
}

val typecheckClient by tasks.registering(Exec::class) {
    group = "verification"
    description = "Type-check the Git UI client and generated BML islands."
    dependsOn("compileKotlin", "bmlInstallClientDependencies")
    workingDir(projectDir)
    commandLine(bml.nodeExecutable.get(), layout.projectDirectory.file("node_modules/typescript/bin/tsc").asFile.absolutePath, "--noEmit")
}

val testClient by tasks.registering(Exec::class) {
    group = "verification"
    description = "Test Git authentication and pipeline log behavior."
    dependsOn("bmlInstallClientDependencies")
    workingDir(projectDir)
    commandLine(bml.nodeExecutable.get(), "--test", "src/test/client-auth.test.mjs", "src/test/client-pipeline-logs.test.mjs")
}

tasks.named("check") { dependsOn(typecheckClient, testClient) }

boscaGraphql {
    packageName.set("bosca.git.ui.graphql")
    generateTypeScript.set(false)
    endpoint.set(providers.environmentVariable("BML_GRAPHQL_ENDPOINT").orElse("http://localhost:8080/graphql"))
}

tasks.named<ProcessResources>("processResources") {
    from(layout.buildDirectory.dir("generated/bml/js-prod")) {
        into("git-ui/js")
    }
}

tasks.test {
    systemProperty("workspaceRoot", rootProject.projectDir.absolutePath)
    inputs.files(rootProject.fileTree(rootProject.projectDir) {
        include("**/src/main/resources/**/*.graphqls")
        exclude("**/build/**", "**/node_modules/**", "**/.intellijPlatform/**")
    })
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
