import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.kover)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
    }
}

dependencies {
    implementation(project(":bosca-core:core"))
    implementation(project(":search:core-search"))
    implementation(project(":bosca-core:core-storage"))

    implementation(libs.kotlinx.serialization.json)

    "ksp"(project(":bosca-core:core-ksp"))
    "ksp"(project(":services-di:service-ksp"))
    "ksp"(project(":services-di:di-ksp"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
}

ksp {
    arg("ProviderRegistrarPrefix", "Docs")
}

val compileKotlin: KotlinCompile by tasks
compileKotlin.compilerOptions {
    
}

tasks.register<JavaExec>("compileSources") {
    description = "Compile Kotlin source files into pre-parsed JSON for documentation indexing"
    dependsOn("classes")

    val rootDir = rootProject.layout.projectDirectory.asFile
    val outputFile = layout.projectDirectory.file("src/main/resources/docs/source-docs.json").asFile

    mainClass.set("bosca.docs.index.SourceDocsCompiler")
    classpath = sourceSets["main"].runtimeClasspath
    args = listOf(
        outputFile.absolutePath,
        File(rootDir, "backend/framework").absolutePath,
        File(rootDir, "backend/kit").absolutePath,
    )
}
