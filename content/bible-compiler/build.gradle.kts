import java.net.URI
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import org.gradle.work.DisableCachingByDefault

plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.kover)
}

@DisableCachingByDefault(because = "Bible bundles must not be distributed through the build cache")
abstract class DownloadBibleTestBundle : DefaultTask() {
    @get:Input
    @get:Optional
    abstract val sourceUrl: Property<String>

    @get:Input
    abstract val failOnMissingBundle: Property<Boolean>

    @get:OutputFile
    abstract val destination: RegularFileProperty

    @TaskAction
    fun download() {
        val target = destination.get().asFile
        if (target.exists()) {
            validateBundle(target)
            return
        }
        val url = sourceUrl.orNull
        if (url.isNullOrBlank()) {
            handleMissingBundle(target.name, "Set BIBLE_RAW_ARTIFACTS to the raw artifact download base URL.")
            return
        }
        target.parentFile.mkdirs()
        val temporary = Files.createTempFile(target.parentFile.toPath(), target.name, ".part")
        try {
            val connection = URI(url).toURL().openConnection().apply {
                connectTimeout = 30_000
                readTimeout = 120_000
            }
            connection.getInputStream().use { input ->
                Files.newOutputStream(temporary).use { output -> input.copyTo(output) }
            }
            validateBundle(temporary.toFile())
            Files.move(temporary, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (exception: IOException) {
            handleMissingBundle(target.name, "Download failed: ${exception.javaClass.simpleName}.", exception)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun handleMissingBundle(name: String, reason: String, cause: IOException? = null) {
        if (failOnMissingBundle.get()) {
            throw GradleException("Required Bible test resource $name is missing in CI. $reason", cause)
        }
        logger.warn("""
            |
            |************************************************************************
            |WARNING: BIBLE TEST RESOURCE $name IS MISSING
            |$reason
            |Tests requiring this bundle will be SKIPPED.
            |************************************************************************
        """.trimMargin())
    }

    private fun validateBundle(bundle: File) {
        ZipFile(bundle).use { zip ->
            val names = zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
            if (!names.any { it == "metadata.xml" || it.endsWith("/metadata.xml") } ||
                !names.any { it.endsWith("/styles.xml") } || !names.any { it.endsWith(".usx") }) {
                throw IOException("${bundle.name} is not a DBL bundle: missing metadata, styles, or USX files")
            }
        }
    }
}

val bibleTestBundles = layout.projectDirectory.dir(".test-resources")
val bundleDownloads = listOf("kjv", "asv").map { translation ->
    tasks.register<DownloadBibleTestBundle>("download${translation.replaceFirstChar { it.uppercase() }}TestBundle") {
        group = "verification"
        description = "Download the $translation DBL bundle for Bible compiler tests"
        sourceUrl.set(providers.environmentVariable("BIBLE_RAW_ARTIFACTS")
            .map { "${it.trimEnd('/')}/$translation.zip" })
        failOnMissingBundle.set(providers.environmentVariable("CI")
            .map { it.isNotBlank() && !it.equals("false", ignoreCase = true) && it != "0" }
            .orElse(false))
        destination.set(bibleTestBundles.file("$translation.zip"))
        outputs.upToDateWhen { false }
    }
}

tasks.register("downloadBibleTestResources") {
    group = "verification"
    description = "Download the KJV and ASV test bundles into the ignored local resource directory"
    dependsOn(bundleDownloads)
}

tasks.withType<Test>().configureEach {
    dependsOn(bundleDownloads)
    classpath += files(bibleTestBundles)
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
