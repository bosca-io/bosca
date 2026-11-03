plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.plugin.serialization)
    id("io.bosca.bml")
    id("io.bosca.graphql")
}

description = "System administration BML pages and components for Bosca Atlas"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
}

dependencies {
    api(project(":bml:bml-server"))
    api(project(":bml:bml-design"))
    implementation(project(":bml:bml-auth-ui"))
    implementation(project(":server:atlas-ui"))
    implementation(project(":bml:core-bml"))
    implementation(project(":bml:bml-annotations"))
    implementation(project(":bosca-graphql:bosca-graphql-client"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.logback.classic)
}

bml {
    packageName.set("bosca.system.ui.generated")
    generateKotlinSources.set(true)
    clientRuntime.set(layout.projectDirectory.file("../../bml/bml-runtime/src/index.ts"))
}

boscaGraphql {
    packageName.set("bosca.system.ui.graphql")
    generateTypeScript.set(false)
}

val typecheckClient by tasks.registering(Exec::class) {
    dependsOn("compileKotlin", "bmlPrepareClient", "bmlInstallClientDependencies")
    workingDir(projectDir)
    commandLine(bml.nodeExecutable.get(), layout.projectDirectory.file("node_modules/typescript/bin/tsc").asFile.absolutePath, "--noEmit")
}
tasks.named("check") { dependsOn(typecheckClient) }
