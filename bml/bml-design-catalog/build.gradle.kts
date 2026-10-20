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
    runtimeOnly(libs.logback.classic)
    testImplementation(libs.kotlin.test.junit)
}

bml {
    packageName.set("bosca.design.catalog.generated")
    metricsGlobalAssets.from(layout.projectDirectory.file("src/main/resources/catalog.css"))
}

application {
    mainClass.set("bosca.design.catalog.MainKt")
}

val catalogRuntime = configurations.named("runtimeClasspath")
val catalogClasspath = files(tasks.jar.flatMap { it.archiveFile }, catalogRuntime).asPath
tasks.withType<Test>().configureEach {
    dependsOn(tasks.jar, catalogRuntime)
    inputs.dir(layout.projectDirectory.dir("src/test/browser"))
        .withPropertyName("browserChecks").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("catalog.classpath", catalogClasspath)
    systemProperty("catalog.java", javaToolchains.launcherFor(java.toolchain).get().executablePath.asFile.absolutePath)
    systemProperty("catalog.node", bml.nodeExecutable.get())
    systemProperty("catalog.browser", layout.projectDirectory.file("src/test/browser/catalog.mjs").asFile.absolutePath)
}

tasks.register<Test>("browserTest") {
    group = "verification"
    description = "Verify the packaged catalog over HTTP and in an isolated browser."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("bosca.design.catalog.CatalogApplicationTest") }
}
