package bosca.bml.gradle

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.security.MessageDigest
import java.util.HexFormat
import java.util.jar.JarFile

/** Checks the packaged library manifest against its binary and original source archives. */
abstract class BmlValidateLibraryTask : DefaultTask() {
    @get:Input abstract val libraryId: Property<String>
    @get:Input abstract val artifactVersion: Property<String>
    @get:Input abstract val exports: ListProperty<String>
    @get:Input abstract val providerRegistrars: ListProperty<String>

    init { providerRegistrars.convention(emptyList()) }
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val binaryArchive: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourcesArchive: RegularFileProperty

    /** Exact main compilation outputs, including models, generated helpers, and Kotlin module metadata. */
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val compiledOutputs: ConfigurableFileCollection

    /** Source trees preserve archive-relative paths independently of source JAR filtering or replacement. */
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val kotlinSources: ConfigurableFileCollection

    /** Bundled client modules, stylesheets, resources, and their inventory. */
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val assetOutputs: ConfigurableFileCollection

    /** Original asset files actually used by the producer build. */
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val assetSources: ConfigurableFileCollection

    /** Standalone client files and public resources require an inventory even without inline scripts. */
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val clientAssetInputs: ConfigurableFileCollection

    /** Written after a successful check so unchanged archives are not validated again. */
    @get:OutputFile abstract val result: RegularFileProperty

    /** Fails validation before publication if the packaged metadata, bytecode, and sources disagree. */
    @TaskAction
    fun validate() {
        val componentCount = JarFile(binaryArchive.get().asFile).use { binary ->
            val manifest = binary.getJarEntry("META-INF/bml/library-manifest.json")
                ?: error("Binary archive is missing the BML library manifest for ${libraryId.get()}")
            val metadata = binary.getInputStream(manifest).use { JsonSlurper().parse(it) } as? Map<*, *>
                ?: error("Invalid BML library manifest")
            require(metadata["formatVersion"] == 4) { "Unsupported BML library manifest format" }
            require(metadata["library"] == libraryId.get() && metadata["version"] == artifactVersion.get()) {
                "BML library manifest coordinates do not match ${libraryId.get()}:${artifactVersion.get()}"
            }
            val components = (metadata["components"] as? List<*>)?.map {
                it as? Map<*, *> ?: error("Invalid BML component metadata")
            } ?: error("Missing BML component metadata")
            require(components.filter { it["exported"] == true }.map { it["tag"] }.toSet() == exports.get().toSet()) {
                "BML library exports do not match the compiled manifest"
            }
            val registrars = (metadata["providerRegistrars"] as? List<*>).orEmpty()
            require(registrars.distinct().size == registrars.size && registrars.toSet() == providerRegistrars.get().toSet()) {
                "BML library provider registrars do not match the compiled manifest"
            }
            registrars.forEach { entry ->
                val registrar = entry as? String ?: error("Invalid BML provider registrar")
                require(registrar.split('.').size > 1 && registrar.split('.').all { Regex("[A-Za-z_][A-Za-z0-9_]*").matches(it) }) {
                    "Invalid BML provider registrar $registrar"
                }
                require(binary.getJarEntry(registrar.replace('.', '/') + ".class")?.isDirectory == false) {
                    "Binary archive is missing BML provider registrar $registrar"
                }
            }
            JarFile(sourcesArchive.get().asFile).use { sources ->
                components.forEach { component ->
                    val renderer = component["renderer"] as? String ?: error("Missing BML renderer")
                    val classPath = renderer.replace('.', '/') + ".class"
                    require(binary.getJarEntry(classPath)?.isDirectory == false) { "Binary archive is missing compiled BML renderer $renderer" }
                    val source = component["source"] as? Map<*, *> ?: error("Missing source metadata for $renderer")
                    val entryName = source["entry"] as? String ?: error("Missing source entry for $renderer")
                    val entry = sources.getJarEntry(entryName) ?: error("Sources archive is missing $entryName for $renderer")
                    val digest = sources.getInputStream(entry).use { input ->
                        "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readBytes()))
                    }
                    require(digest == source["digest"]) { "Sources archive contains a different version of $entryName; rebuild the BML library" }
                }
                compiledOutputs.asFileTree.visit { output ->
                    if (!output.isDirectory) {
                        val entryName = output.relativePath.pathString
                        val entry = binary.getJarEntry(entryName)?.takeUnless { it.isDirectory }
                            ?: error("Binary archive is missing compiled library output $entryName")
                        require(binary.getInputStream(entry).use { it.readBytes() }.contentEquals(output.file.readBytes())) {
                            "Binary archive contains a different version of compiled library output $entryName; rebuild the BML library"
                        }
                    }
                }
                kotlinSources.asFileTree.visit { source ->
                    if (!source.isDirectory) {
                        val entryName = source.relativePath.pathString
                        val entry = sources.getJarEntry(entryName)?.takeUnless { it.isDirectory }
                            ?: error("Sources archive is missing Kotlin source $entryName")
                        val archived = sources.getInputStream(entry).use { it.readBytes() }
                        require(archived.contentEquals(source.file.readBytes())) {
                            "Sources archive contains a different version of Kotlin source $entryName; rebuild the BML library"
                        }
                    }
                }
                verifyAssets(binary, assetOutputs, "Binary")
                verifyAssets(sources, assetSources, "Sources")
                if (components.any { it["clientModule"] != null } || !clientAssetInputs.asFileTree.isEmpty) {
                    require(binary.getJarEntry("META-INF/bml/client-manifest.json") != null) {
                        "Binary archive is missing bundled library client assets"
                    }
                }
            }
            components.size
        }
        result.get().asFile.apply { parentFile.mkdirs() }
            .writeText("Validated ${libraryId.get()}:${artifactVersion.get()} ($componentCount components)\n")
    }

    private fun verifyAssets(archive: JarFile, expected: ConfigurableFileCollection, kind: String) {
        expected.asFileTree.visit { file ->
            if (!file.isDirectory) {
                val path = file.relativePath.pathString
                val entry = archive.getJarEntry(path)?.takeUnless { it.isDirectory }
                    ?: error("$kind archive is missing library asset $path")
                require(archive.getInputStream(entry).use { it.readBytes() }.contentEquals(file.file.readBytes())) {
                    "$kind archive contains a different version of library asset $path"
                }
            }
        }
    }
}
