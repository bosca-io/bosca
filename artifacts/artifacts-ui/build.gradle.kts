plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.plugin.serialization)
    id("io.bosca.bml")
    id("io.bosca.graphql")
}

description = "Artifacts BML pages and components for Bosca Atlas"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
}

dependencies {
    api(project(":bml:bml-server"))
    api(project(":bml:bml-design"))
    implementation(project(":server:atlas-ui"))
    implementation(project(":bml:core-bml"))
    implementation(project(":bml:bml-annotations"))
    implementation(project(":bosca-graphql:bosca-graphql-client"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":bosca-core:test-support"))
    testRuntimeOnly(libs.logback.classic)
}

tasks.test { systemProperty("workspaceRoot", rootProject.projectDir.absolutePath) }

bml {
    packageName.set("bosca.artifacts.ui.generated")
    generateKotlinSources.set(true)
    clientRuntime.set(layout.projectDirectory.file("../../bml/bml-runtime/src/index.ts"))
}

tasks.named("bmlInstallClientDependencies") {
    inputs.files(fileTree("../../bml/bml-runtime") { include("package.json", "src/**", "tools/**") })
    inputs.files(fileTree("../../web/packages/auth") { include("package.json", "src/**") })
    inputs.files(fileTree("../../server/atlas-ui") { include("package.json", "src/main/client/**") })
}
tasks.named("bmlBundleClient") { inputs.file("tsconfig.json") }
val typecheckClient by tasks.registering(Exec::class) {
    dependsOn("compileKotlin", "bmlPrepareClient", "bmlInstallClientDependencies")
    workingDir(projectDir)
    commandLine(bml.nodeExecutable.get(), layout.projectDirectory.file("node_modules/typescript/bin/tsc").asFile.absolutePath, "--noEmit")
}
val testClient by tasks.registering(Exec::class) {
    dependsOn("bmlInstallClientDependencies")
    workingDir(projectDir)
    commandLine(bml.nodeExecutable.get(), "--test", "src/test/client-upload.test.mjs")
}
tasks.named("check") { dependsOn(typecheckClient, testClient) }

boscaGraphql {
    packageName.set("bosca.artifacts.ui.graphql")
    generateTypeScript.set(false)
}
