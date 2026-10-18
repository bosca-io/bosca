plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.plugin.serialization)
    id("io.bosca.bml")
    application
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
}

dependencies {
    implementation(project(":bml:bml-design"))
    implementation(project(":bml:bml-server"))
    implementation(project(":bml:bml-annotations"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(project(":bml:bml-compiler"))
}

bml {
    packageName.set("bosca.design.example.generated")
    metricsGlobalAssets.from(layout.projectDirectory.file("src/main/resources/design-example.css"))
}

application {
    mainClass.set("bosca.design.example.MainKt")
}

val fixtureRuntime = configurations.named("runtimeClasspath")
tasks.test {
    dependsOn(tasks.jar, fixtureRuntime)
    systemProperty("design.fixture.classpath", files(tasks.jar.flatMap { it.archiveFile }, fixtureRuntime).asPath)
    systemProperty("design.fixture.java", javaToolchains.launcherFor(java.toolchain).get().executablePath.asFile.absolutePath)
    systemProperty("design.fixture.node", bml.nodeExecutable.get())
    systemProperty("design.fixture.browser", layout.projectDirectory.file("src/test/browser/controls.mjs").asFile.absolutePath)
}
