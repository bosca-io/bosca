package bosca.bml.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.util.jar.JarFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Compiles and publishes a real Kotlin/BML producer, then consumes only its published binary. */
class BmlLibraryPublicationTest {
    @Test
    fun `replacement binary must contain the current Kotlin implementation`() {
        val work = createTempDirectory("bml-library-stale-binary").toFile()
        try {
            val fixture = BmlCompilerFixture(work)
            val producer = File(work, "producer").apply { mkdirs() }
            File(producer, "settings.gradle").writeText("rootProject.name = 'design'")
            File(producer, "gradle.properties").writeText("kotlin.compiler.execution.strategy=in-process\norg.gradle.jvmargs=-Xmx1g\n")
            val script = File(producer, "build.gradle")
            val validBuild = """
                plugins { id 'org.jetbrains.kotlin.jvm'; id 'io.bosca.bml'; id 'maven-publish' }
                group = 'example'; version = '1.0'
                ${fixture.shared}
                bml { packageName.set('example.generated'); library { exports.set(['button']) } }
                publishing {
                    publications { maven(MavenPublication) { from components.java } }
                    repositories { maven { name = 'fixture'; url = uri(${quote(fixture.repository)}) } }
                }
            """.trimIndent()
            script.writeText(validBuild)
            File(producer, "src/main/bml/button.bml").apply {
                parentFile.mkdirs(); writeText("<component tag=\"button\"><html:button>{ example.Model.value() }</html:button></component>")
            }
            val model = File(producer, "src/main/kotlin/example/Model.kt").apply {
                parentFile.mkdirs(); writeText("package example\nobject Model { fun value(): String = \"before\" }")
            }
            runner(producer, "publishMavenPublicationToFixtureRepository").build()
            val binary = File(fixture.repository, "example/design/1.0/design-1.0.jar")
            val sources = File(binary.parentFile, "design-1.0-sources.jar")
            val oldBinary = binary.readBytes()
            val oldSources = sources.readBytes()
            val stale = File(work, "stale.jar").apply { writeBytes(oldBinary) }
            model.writeText(model.readText().replace("before", "after"))
            script.writeText(validBuild + """

                afterEvaluate {
                    publishing.publications.maven.setArtifacts([
                        [source: file(${quote(stale)}), classifier: ''], tasks.sourcesJar
                    ])
                    tasks.withType(GenerateModuleMetadata).configureEach { enabled = false }
                }
            """.trimIndent())
            val rejected = runner(producer, "publishMavenPublicationToFixtureRepository").buildAndFail()
            assertTrue("Binary archive contains a different version of compiled library output example/Model.class" in rejected.output, rejected.output)
            assertEquals(TaskOutcome.SUCCESS, rejected.task(":bmlValidateLibrary")?.outcome)
            assertEquals(TaskOutcome.FAILED, rejected.task(":mavenBmlValidatePublication")?.outcome)
            assertEquals(null, rejected.task(":publishMavenPublicationToFixtureRepository"))
            assertTrue(oldBinary.contentEquals(binary.readBytes()))
            assertTrue(oldSources.contentEquals(sources.readBytes()))

            script.writeText(validBuild + """

                def replacement = tasks.register('replacementJar', Jar) {
                    archiveClassifier.set('replacement')
                    from(sourceSets.main.output)
                }
                afterEvaluate {
                    publishing.publications.maven.setArtifacts([
                        [source: replacement, classifier: ''], tasks.sourcesJar
                    ])
                    tasks.withType(GenerateModuleMetadata).configureEach { enabled = false }
                }
            """.trimIndent())
            val accepted = runner(producer, "publishMavenPublicationToFixtureRepository").build()
            assertEquals(TaskOutcome.SUCCESS, accepted.task(":mavenBmlValidatePublication")?.outcome)
            java.net.URLClassLoader(arrayOf(binary.toURI().toURL()), javaClass.classLoader).use { loader ->
                val type = loader.loadClass("example.Model")
                assertEquals("after", type.getMethod("value").invoke(type.getField("INSTANCE").get(null)))
            }
            JarFile(sources).use { jar ->
                assertEquals(model.readText(), jar.getInputStream(jar.getJarEntry("example/Model.kt")).reader().readText())
            }
        } finally { work.deleteRecursively() }
    }

    @Test
    fun `published library carries compiled models metadata and navigable sources`() {
        val work = createTempDirectory("bml-library-publication").toFile()
        try {
            val fixture = BmlCompilerFixture(work)
            val repository = fixture.repository
            val shared = fixture.shared
            val producer = File(work, "producer").apply { mkdirs() }
            File(producer, "settings.gradle").writeText("rootProject.name = 'design'")
            File(producer, "gradle.properties").writeText("kotlin.compiler.execution.strategy=in-process\norg.gradle.jvmargs=-Xmx1g\n")
            File(producer, "build.gradle").writeText("""
                plugins { id 'org.jetbrains.kotlin.jvm'; id 'io.bosca.bml'; id 'maven-publish' }
                group = 'example'
                version = '1.2.3'
                $shared
                kotlin.sourceSets.main.kotlin.srcDir('models')
                kotlin.sourceSets.main.kotlin.exclude('excluded/**')
                tasks.withType(Jar).configureEach { if (name == 'sourcesJar') exclude('excluded/**') }
                bml {
                    sourceDir.set(layout.projectDirectory.dir('markup'))
                    packageName.set('example.design.generated')
                    library { exports.set(['button']) }
                }
                publishing {
                    publications { maven(MavenPublication) { from components.java } }
                    repositories { maven { name = 'fixture'; url = uri(${quote(repository)}) } }
                }
            """.trimIndent())
            val button = File(producer, "markup/components/button.bml").apply {
                parentFile.mkdirs()
                writeText("""
                    <component tag="button">
                      <prop name="model" type="example.design.ButtonModel"/>
                      <html:button>{ model.label }<slot/></html:button>
                    </component>
                """.trimIndent())
            }
            val model = File(producer, "src/main/kotlin/example/design/ButtonModel.kt").apply {
                parentFile.mkdirs()
                writeText("""
                    package example.design
                    class ButtonModel(label: String) {
                        val label = LabelFormatter.format(label)
                        class Details(val suffix: String)
                    }
                """.trimIndent())
            }
            // Archive entries use the source-root relative path, which need not match the Kotlin package.
            val helper = File(producer, "models/formatting/LabelFormatter.kt").apply {
                parentFile.mkdirs()
                writeText("""
                    package example.design
                    object LabelFormatter {
                        fun format(label: String) = label + ButtonModel.Details("").suffix
                    }
                """.trimIndent())
            }
            File(producer, "models/excluded/NotASource.kt").apply {
                parentFile.mkdirs(); writeText("This file is excluded from the main source set.")
            }
            val published = runner(producer, "publishMavenPublicationToFixtureRepository").build()
            assertEquals(TaskOutcome.SUCCESS, published.task(":bmlValidateLibrary")?.outcome)
            assertEquals(TaskOutcome.SUCCESS, published.task(":mavenBmlValidatePublication")?.outcome)
            val republished = runner(producer, "publishMavenPublicationToFixtureRepository").build()
            assertTrue("Reusing configuration cache" in republished.output, republished.output)
            // Identical archives were already validated; publication still depends on that result.
            assertEquals(TaskOutcome.UP_TO_DATE, republished.task(":mavenBmlValidatePublication")?.outcome)
            val artifacts = File(repository, "example/design/1.2.3")
            JarFile(File(artifacts, "design-1.2.3.jar")).use { jar ->
                assertTrue(jar.getJarEntry("example/design/generated/ButtonComponent.class") != null)
                assertTrue(jar.getJarEntry("example/design/ButtonModel.class") != null)
                assertTrue(jar.getJarEntry("example/design/LabelFormatter.class") != null)
                val manifest = jar.getJarEntry("META-INF/bml/library-manifest.json")
                assertTrue(manifest != null)
                val metadata = jar.getInputStream(manifest).reader().readText()
                assertTrue("bml/components/button.bml" in metadata, metadata)
                assertTrue("example.design.generated.ButtonComponent" in metadata, metadata)
                assertFalse(jar.entries().asSequence().any { "build-id" in it.name || "BmlPages" in it.name })
            }
            JarFile(File(artifacts, "design-1.2.3-sources.jar")).use { jar ->
                assertEquals(button.readText(), jar.getInputStream(jar.getJarEntry("bml/components/button.bml")).reader().readText())
                assertEquals(model.readText(), jar.getInputStream(jar.getJarEntry("example/design/ButtonModel.kt")).reader().readText())
                assertEquals(helper.readText(), jar.getInputStream(jar.getJarEntry("formatting/LabelFormatter.kt")).reader().readText())
                assertEquals(null, jar.getJarEntry("excluded/NotASource.kt"))
            }
            helper.appendText("\n// Source attachment revision\n")
            val changedSources = runner(producer, "publishMavenPublicationToFixtureRepository").build()
            assertEquals(TaskOutcome.SUCCESS, changedSources.task(":compileKotlin")?.outcome)
            assertEquals(TaskOutcome.SUCCESS, changedSources.task(":sourcesJar")?.outcome)
            assertTrue("Reusing configuration cache" in changedSources.output, changedSources.output)
            JarFile(File(artifacts, "design-1.2.3-sources.jar")).use { jar ->
                assertEquals(helper.readText(), jar.getInputStream(jar.getJarEntry("formatting/LabelFormatter.kt")).reader().readText())
            }
            // Resource output is owned by compileKotlin: deleting it must invalidate an otherwise cached compile.
            val metadata = File(producer, "build/generated/bml/resources/META-INF/bml/library-manifest.json")
            assertTrue(metadata.delete())
            val rebuilt = runner(producer, "jar").build()
            assertEquals(TaskOutcome.SUCCESS, rebuilt.task(":compileKotlin")?.outcome)
            assertTrue(metadata.isFile)
            File(producer, "src/test/kotlin/example/design/LibraryTestProbe.kt").apply {
                parentFile.mkdirs()
                writeText("package example.design\nobject LibraryTestProbe { val renderer = example.design.generated.ButtonComponent }")
            }
            val manifestModified = metadata.lastModified()
            runner(producer, "compileTestKotlin").build()
            assertFalse(File(producer, "build/classes/kotlin/test/example/design/generated/ButtonComponent.class").exists(),
                "Test compilation must use the compiled main component, not compile its own copy")
            assertEquals(manifestModified, metadata.lastModified(), "Test compilation must not overwrite main metadata")
            val producerBuild = File(producer, "build.gradle")
            val validBuild = producerBuild.readText()
            val publishedBinary = File(artifacts, "design-1.2.3.jar").readBytes()
            producerBuild.appendText("\npublishing.publications.maven.artifactId = 'renamed'\n")
            val wrongCoordinates = runner(producer, "publishMavenPublicationToFixtureRepository").buildAndFail()
            assertTrue("BML publication coordinates must match example:design:1.2.3" in wrongCoordinates.output,
                wrongCoordinates.output)
            assertFalse(File(repository, "example/renamed/1.2.3/renamed-1.2.3.jar").exists())
            producerBuild.writeText(validBuild)
            for ((excluded, message) in listOf(
                "example/design/generated/**" to "Binary archive is missing compiled BML renderer example.design.generated.ButtonComponent",
                "META-INF/bml/library-manifest.json" to "Binary archive is missing the BML library manifest",
                "example/design/ButtonModel.class" to "Binary archive is missing compiled library output example/design/ButtonModel.class",
                "example/design/LabelFormatter.class" to "Binary archive is missing compiled library output example/design/LabelFormatter.class",
                "example/design/ButtonModel\$Details.class" to "Binary archive is missing compiled library output example/design/ButtonModel\$Details.class",
                "example/design/generated/ButtonComponent\$*.class" to "Binary archive is missing compiled library output example/design/generated/ButtonComponent\$",
                "META-INF/*.kotlin_module" to "Binary archive is missing compiled library output META-INF/example_design.kotlin_module",
            )) {
                producerBuild.writeText(validBuild + "\ntasks.named('jar') { exclude('$excluded') }\n")
                val invalidBinary = try {
                    runner(producer, "publishMavenPublicationToFixtureRepository").buildAndFail()
                } catch (failure: org.gradle.testkit.runner.UnexpectedBuildSuccess) {
                    throw AssertionError("Excluding '$excluded' must invalidate the archive: ${failure.message}", failure)
                }
                assertTrue(message in invalidBinary.output, invalidBinary.output)
                assertTrue(invalidBinary.tasks.any {
                    it.path in setOf(":bmlValidateLibrary", ":mavenBmlValidatePublication") && it.outcome == TaskOutcome.FAILED
                })
                assertEquals(null, invalidBinary.task(":publishMavenPublicationToFixtureRepository"))
                assertTrue(publishedBinary.contentEquals(File(artifacts, "design-1.2.3.jar").readBytes()),
                    "Invalid packaging must not replace the published artifact")
            }
            producerBuild.writeText(validBuild)
            producerBuild.appendText("""

                def replacement = tasks.register('replacementJar', Jar) {
                    archiveClassifier.set('replacement')
                    from(sourceSets.main.output)
                    exclude('example/design/generated/**')
                }
                afterEvaluate {
                    publishing.publications.maven.setArtifacts([
                        [source: replacement, classifier: ''], tasks.sourcesJar
                    ])
                    tasks.withType(GenerateModuleMetadata).configureEach { enabled = false }
                }
            """.trimIndent())
            val replacedBinary = runner(producer, "publishMavenPublicationToFixtureRepository").buildAndFail()
            assertTrue("Binary archive is missing compiled BML renderer" in replacedBinary.output, replacedBinary.output)
            assertEquals(TaskOutcome.FAILED, replacedBinary.task(":mavenBmlValidatePublication")?.outcome)
            assertEquals(null, replacedBinary.task(":publishMavenPublicationToFixtureRepository"))
            assertTrue(publishedBinary.contentEquals(File(artifacts, "design-1.2.3.jar").readBytes()))
            producerBuild.writeText(producerBuild.readText().replace(
                "exclude('example/design/generated/**')", "exclude('example/design/ButtonModel.class')",
            ))
            val replacedModel = runner(producer, "publishMavenPublicationToFixtureRepository").buildAndFail()
            assertTrue("Binary archive is missing compiled library output example/design/ButtonModel.class" in replacedModel.output,
                replacedModel.output)
            assertEquals(TaskOutcome.FAILED, replacedModel.task(":mavenBmlValidatePublication")?.outcome)
            assertEquals(null, replacedModel.task(":publishMavenPublicationToFixtureRepository"))
            producerBuild.writeText(validBuild)
            producerBuild.appendText("\nafterEvaluate { publishing.publications.maven.artifacts.removeAll { it.classifier == 'sources' } }\n")
            val missingAttachment = runner(producer, "publishMavenPublicationToFixtureRepository").buildAndFail()
            assertTrue("requires exactly one sources JAR" in missingAttachment.output ||
                "Cannot publish module metadata because an artifact from the 'java' component has been removed" in missingAttachment.output,
                missingAttachment.output)
            producerBuild.writeText(validBuild)
            File(producer, "build.gradle").appendText("\ntasks.withType(Jar).configureEach { if (name == 'sourcesJar') exclude('**/*.bml') }\n")
            val invalidSources = runner(producer, "jar").buildAndFail()
            assertTrue("Sources archive is missing bml/components/button.bml" in invalidSources.output, invalidSources.output)
            val publishedSources = File(artifacts, "design-1.2.3-sources.jar").readBytes()
            for ((excluded, missing) in listOf(
                "**/*.kt" to "Sources archive is missing Kotlin source",
                "formatting/LabelFormatter.kt" to "Sources archive is missing Kotlin source formatting/LabelFormatter.kt",
            )) {
                producerBuild.writeText(validBuild + "\ntasks.withType(Jar).configureEach { if (name == 'sourcesJar') exclude('$excluded') }\n")
                val missingKotlin = runner(producer, "publishMavenPublicationToFixtureRepository").buildAndFail()
                assertTrue(missing in missingKotlin.output, missingKotlin.output)
                assertEquals(null, missingKotlin.task(":publishMavenPublicationToFixtureRepository"))
                assertTrue(publishedSources.contentEquals(File(artifacts, "design-1.2.3-sources.jar").readBytes()))
            }
            producerBuild.writeText(validBuild + """

                def replacementSources = tasks.register('replacementSourcesJar', Jar) {
                    dependsOn('classes')
                    archiveClassifier.set('replacement-sources')
                    from(sourceSets.main.allSource) { exclude('**/*.kt', '**/*.bml') }
                    from('markup') { include('**/*.bml'); into('bml') }
                }
                afterEvaluate {
                    publishing.publications.maven.setArtifacts([
                        tasks.jar, [source: replacementSources, classifier: 'sources']
                    ])
                    tasks.withType(GenerateModuleMetadata).configureEach { enabled = false }
                }
            """.trimIndent())
            val replacedSources = runner(producer, "publishMavenPublicationToFixtureRepository").buildAndFail()
            assertTrue("Sources archive is missing Kotlin source" in replacedSources.output, replacedSources.output)
            // The standard archives are unchanged and already valid; only the replacement publication fails.
            assertEquals(TaskOutcome.UP_TO_DATE, replacedSources.task(":bmlValidateLibrary")?.outcome)
            assertEquals(TaskOutcome.FAILED, replacedSources.task(":mavenBmlValidatePublication")?.outcome)
            assertEquals(null, replacedSources.task(":publishMavenPublicationToFixtureRepository"))
            assertTrue(publishedSources.contentEquals(File(artifacts, "design-1.2.3-sources.jar").readBytes()))
            producerBuild.writeText(validBuild + """

                tasks.withType(Jar).configureEach {
                    if (name == 'sourcesJar') filesMatching('**/*.kt') {
                        filter { line -> line.replace('class ButtonModel(', 'class AlteredModel(') }
                    }
                }
            """.trimIndent())
            // Gradle does not track changes to per-file filter actions as archive inputs.
            val alteredKotlin = runner(producer, "publishMavenPublicationToFixtureRepository", "--rerun-tasks").buildAndFail()
            assertTrue("Sources archive contains a different version of Kotlin source example/design/ButtonModel.kt" in alteredKotlin.output,
                alteredKotlin.output)
            assertEquals(null, alteredKotlin.task(":publishMavenPublicationToFixtureRepository"))
            producer.deleteRecursively()

            val consumer = File(work, "consumer").apply { mkdirs() }
            File(consumer, "settings.gradle").writeText("rootProject.name = 'consumer'")
            File(consumer, "gradle.properties").writeText("kotlin.compiler.execution.strategy=in-process\norg.gradle.jvmargs=-Xmx1g\n")
            File(consumer, "build.gradle").writeText("""
                plugins { id 'org.jetbrains.kotlin.jvm'; id 'application' }
                $shared
                dependencies { implementation 'example:design:1.2.3' }
                application { mainClass = 'consumer.MainKt' }
            """.trimIndent())
            File(consumer, "src/main/kotlin/consumer/Main.kt").apply {
                parentFile.mkdirs()
                writeText("""
                    package consumer
                    fun main() = kotlinx.coroutines.runBlocking {
                        val context = bosca.bml.render.RenderContext()
                        example.design.generated.ButtonComponent.render(
                            context, mapOf("model" to example.design.ButtonModel("Published")),
                        ) { context.writer.text(" library") }
                        println(context.toString())
                    }
                """.trimIndent())
            }
            val consumed = runner(consumer, "run").build()
            assertTrue("<button>Published library</button>" in consumed.output, consumed.output)
        } finally {
            work.deleteRecursively()
        }
    }

    private fun runner(project: File, task: String, vararg options: String) = GradleRunner.create().withProjectDir(project).withPluginClasspath()
        .withArguments(listOf(task, "--stacktrace", "--configuration-cache", "--max-workers=2") + options)

}
