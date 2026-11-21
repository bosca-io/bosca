plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.kover)
    id("io.bosca.bml")
    id("io.bosca.graphql")
}

description = "WorkOps BML pages and components for Bosca Atlas"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
}

dependencies {
    api(project(":bml:bml-server"))
    api(project(":bml:bml-design"))
    implementation(project(":bml:bml-auth-ui"))
    implementation(project(":server:atlas-ui"))
    implementation(project(":workops:core-workops"))
    implementation(project(":content:core-content"))
    implementation(project(":bosca-yks:yks"))
    implementation(project(":bml:core-bml"))
    implementation(project(":bml:bml-annotations"))
    implementation(project(":bosca-graphql:bosca-graphql-client"))
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":bosca-core:test-support"))
    testRuntimeOnly(libs.logback.classic)
}

bml {
    packageName.set("bosca.workops.ui.generated")
    generateKotlinSources.set(true)
    clientRuntime.set(layout.projectDirectory.file("../../bml/bml-runtime/src/index.ts"))
}

boscaGraphql {
    packageName.set("bosca.workops.ui.graphql")
    generateTypeScript.set(false)
}

val typecheckClient = tasks.register<Exec>("typecheckClient") {
    group = "verification"
    description = "Type-check WorkOps adapters and generated BML clients."
    dependsOn("compileKotlin", "bmlPrepareClient", "bmlInstallClientDependencies")
    workingDir(projectDir)
    commandLine(bml.nodeExecutable.get(), layout.projectDirectory.file("node_modules/typescript/bin/tsc").asFile.absolutePath, "--noEmit")
}

tasks.named("bmlBundleClient") { inputs.file("tsconfig.json") }
tasks.named("check") { dependsOn(typecheckClient, "koverXmlReport") }

kover {
    reports {
        filters {
            excludes {
                classes("bosca.workops.ui.generated.*", "bosca.workops.ui.graphql.*")
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("workops.test.node", bml.nodeExecutable.get())
    systemProperty("workops.test.project", projectDir.absolutePath)
    inputs.dir("src/test/client").withPropertyName("collaborationClientFixtures")
}
