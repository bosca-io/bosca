package bosca.bml.gradle

import java.io.File

/** Supplies the current compiler/runtime to isolated TestKit builds without Maven Local. */
internal class BmlCompilerFixture(work: File) {
    val repository = File(work, "repository").apply { mkdirs() }
    val runtime: String
    val shared: String

    init {
        val fixtureClasspath = System.getProperty("bml.libraryFixtureClasspath").split(File.pathSeparator).map(::File)
        val compiler = fixtureClasspath.single { it.name.startsWith("bml-compiler-") }
        val compilerVersion = BmlGradlePlugin().getPluginArtifact().version
        val compilerDirectory = File(repository, "io/bosca/bml-compiler/$compilerVersion").apply { mkdirs() }
        compiler.copyTo(File(compilerDirectory, "bml-compiler-$compilerVersion.jar"))
        val toolchainDependencies = fixtureClasspath.filterNot { it == compiler }.mapIndexed { index, file ->
            val artifact = "artifact-$index"
            val directory = File(repository, "fixture/toolchain/$artifact/1").apply { mkdirs() }
            file.copyTo(File(directory, "$artifact-1.jar"))
            File(directory, "$artifact-1.pom").writeText("""
                <project><modelVersion>4.0.0</modelVersion><groupId>fixture.toolchain</groupId>
                <artifactId>$artifact</artifactId><version>1</version></project>
            """.trimIndent())
            "<dependency><groupId>fixture.toolchain</groupId><artifactId>$artifact</artifactId><version>1</version></dependency>"
        }.joinToString("\n")
        File(compilerDirectory, "bml-compiler-$compilerVersion.pom").writeText("""
            <project><modelVersion>4.0.0</modelVersion><groupId>io.bosca</groupId>
            <artifactId>bml-compiler</artifactId><version>$compilerVersion</version>
            <dependencies>$toolchainDependencies</dependencies></project>
        """.trimIndent())
        runtime = fixtureClasspath.filterNot { it == compiler }.joinToString(",", transform = ::quote)
        shared = """
            repositories { maven { url = uri(${quote(repository)}) }; mavenCentral() }
            kotlin { jvmToolchain(25) }
            dependencies { implementation files($runtime) }
        """.trimIndent()
    }
}

internal fun quote(file: File): String = "'" + file.absolutePath.replace("\\", "\\\\").replace("'", "\\'") + "'"
