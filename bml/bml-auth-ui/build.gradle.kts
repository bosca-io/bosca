plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.plugin.serialization)
    id("io.bosca.bml")
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
}

dependencies {
    api(project(":bml:bml-server"))
    api(project(":bml:bml-design"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test.junit)
}

bml {
    packageName.set("bosca.bml.auth.generated")
    library {
        exports.set(listOf("auth-layout", "auth-brand", "auth-heading", "auth-providers",
            "auth-login-form", "auth-signup-form", "auth-forgot-form", "auth-reset-form",
            "auth-verify-form", "auth-link-form"))
    }
}

tasks.named("bmlInstallClientDependencies") {
    inputs.files(fileTree("../../web/packages/auth") { include("package.json", "src/**") })
    inputs.files(fileTree("../bml-runtime") { include("package.json", "src/**", "tools/**") })
}

val typecheckClient by tasks.registering(Exec::class) {
    dependsOn("compileKotlin", "bmlPrepareClient", "bmlInstallClientDependencies")
    workingDir(projectDir)
    commandLine(bml.nodeExecutable.get(), layout.projectDirectory.file("node_modules/typescript/bin/tsc").asFile.absolutePath, "--noEmit")
}

val testClient by tasks.registering(Exec::class) {
    dependsOn("bmlInstallClientDependencies")
    workingDir(projectDir)
    commandLine(bml.nodeExecutable.get(), "--test", "src/test/client-auth.test.mjs")
}

tasks.named("check") { dependsOn(typecheckClient, testClient) }
