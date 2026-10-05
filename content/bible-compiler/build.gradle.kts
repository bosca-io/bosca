plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.kover)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

configurations {
    create("antlr")
}

sourceSets {
    main {
        java.srcDir("${project.layout.buildDirectory.asFile.get().absolutePath}/antlr/java")
    }
}

dependencies {
    implementation(project(":bosca-core:core-annotations"))
    implementation(libs.antlr.runtime)
    api(project(":bosca-kmp:bible-dom-shared"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    add("antlr", libs.antlr.tool)
}

tasks.register("generateGrammarSource", JavaExec::class.java) {
    val workingDirectory = file(project.projectDir.path + "/src/main/antlr")
    classpath = project.configurations.getByName("antlr")
    workingDir = workingDirectory
    mainClass = "org.antlr.v4.Tool"
    args = listOf(
        "-o",
        "${project.layout.buildDirectory.asFile.get().absolutePath}/antlr/java/bosca/bible/grammar",
        "-package",
        "bosca.bible.grammar",
        "USXLexer.g4",
        "USXParser.g4"
    )
    inputs.dir(workingDirectory)
    outputs.dir(file("${project.layout.buildDirectory.asFile.get().absolutePath}/antlr/java"))
}

tasks.register("updateLexerTokens", Copy::class.java) {
    from(file("${project.layout.buildDirectory.asFile.get().absolutePath}/antlr/java/bosca/bible/grammar"))
    into(file("${project.projectDir.path}/src/main/antlr"))
    include("USXLexer.tokens")
    dependsOn("generateGrammarSource")
}

tasks.findByName("compileKotlin")?.dependsOn("generateGrammarSource")
