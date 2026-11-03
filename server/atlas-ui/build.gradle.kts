import java.nio.file.Files

plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.plugin.serialization)
    id("io.bosca.bml")
    id("io.bosca.graphql")
}

description = "Bosca Atlas landing, authentication, and shared BML application"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
}

dependencies {
    api(project(":bml:bml-auth-ui"))
    api(project(":bml:bml-server"))
    api(project(":bml:bml-design"))
    api(project(":bosca-core:core"))
    implementation(project(":bml:core-bml"))
    implementation(project(":bml:bml-annotations"))
    implementation(project(":bosca-graphql:bosca-graphql-client"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":bosca-core:test-support"))
    testRuntimeOnly(libs.logback.classic)
}

bml {
    packageName.set("bosca.atlas.ui.generated")
    generateKotlinSources.set(true)
}

boscaGraphql {
    packageName.set("bosca.atlas.ui.graphql")
    generateTypeScript.set(false)
}

tasks.named("bmlInstallClientDependencies") {
    val sharedAuthInstall = layout.projectDirectory.dir("node_modules/@bosca/bml-auth-ui").asFile
    val sdkInstall = layout.projectDirectory.dir("node_modules/@bosca/auth-client-browser").asFile
    doFirst {
        check(sharedAuthInstall.deleteRecursively()) { "Cannot refresh the local authentication client" }
        check(if (Files.isSymbolicLink(sdkInstall.toPath())) sdkInstall.delete() else sdkInstall.deleteRecursively()) { "Cannot refresh the local authentication SDK" }
    }
    inputs.files(fileTree("../../bml/bml-auth-ui") { include("package.json", "src/main/client/**") })
    inputs.files(fileTree("../../bml/bml-runtime") { include("package.json", "src/**", "tools/**") })
    inputs.files(fileTree("../../web/packages/auth") { include("package.json", "src/**") })
}

tasks.named("bmlBundleClient") {
    inputs.files(fileTree("../../bml/bml-auth-ui/src/main/client"))
        .withPropertyName("authClientSources").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file("tsconfig.json").withPropertyName("clientTypeScriptConfig")
}

val typecheckClient by tasks.registering(Exec::class) {
    group = "verification"
    description = "Type-check Atlas authentication and generated BML clients."
    dependsOn("compileKotlin", "bmlPrepareClient", "bmlInstallClientDependencies")
    workingDir(projectDir)
    commandLine(bml.nodeExecutable.get(), layout.projectDirectory.file("node_modules/typescript/bin/tsc").asFile.absolutePath, "--noEmit")
}

tasks.named("check") { dependsOn(typecheckClient, ":bml:bml-auth-ui:check") }
