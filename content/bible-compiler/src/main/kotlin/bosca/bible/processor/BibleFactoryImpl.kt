package bosca.bible.processor

import bosca.bible.grammar.Compiler
import bosca.bible.usx.Chapter
import bosca.bible.usx.Root
import bosca.bible.Bible
import bosca.bible.BibleFactory
import bosca.bible.BibleMetadata
import bosca.bible.Book
import bosca.bible.IBible
import bosca.bible.IChapter
import bosca.bible.Reference
import bosca.bible.style.StyleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

class BibleFactoryImpl : BibleFactory {

    override suspend fun getBibles(inputStream: InputStream): List<IBible> {
        val files = mutableMapOf<String, ByteArray>()
        withContext(Dispatchers.IO) {
            ZipInputStream(inputStream).use { zip ->
                lateinit var entry: ZipEntry
                while (zip.nextEntry?.also { entry = it } != null) {
                    if (!entry.isDirectory) {
                        files[entry.name] = zip.readBytes()
                    }
                    zip.closeEntry()
                }
            }
        }
        return getBibles(files)
    }

    override suspend fun getBibles(filename: String): List<IBible> {
        val files = mutableMapOf<String, ByteArray>()
        withContext(Dispatchers.IO) {
            ZipFile(filename).use { file ->
                file.entries().asSequence().forEach { entry ->
                    file.getInputStream(entry).use { input ->
                        files[entry.name] = input.readAllBytes()
                    }
                }
            }
        }
        return getBibles(files)
    }

    private fun getBibles(files: Map<String, ByteArray>): List<IBible> {
        val bundleFiles = files.relativeToMetadataRoot()
        val registry = StyleRegistry()
        val metadata = bundleFiles["metadata.xml"]?.let { MetadataProcessor.process(it) }
            ?: error("missing metadata.xml")
        val stylesheet = bundleFiles["release/styles.xml"]?.let { StyleProcessor.process(it) }
        registry.register(stylesheet ?: emptyList())
        return metadata.map {
            val books = mutableListOf<Book>()
            val publication = it.publication
            for (name in it.names) {
                val content = publication.contents[name.id] ?: continue
                val file = bundleFiles[content.file.normalizedZipPath()] ?: continue
                val usx = try {
                    Compiler.compile(Path.of(name.usfm), file.decodeToString())
                } catch (error: Exception) {
                    throw IllegalStateException("failed to parse ${content.file}: ${error.message}", error)
                }
                if (usx is Root) {
                    val chapters = usx.items.filterIsInstance<IChapter>()
                    val serializableChapters = chapters.map {
                        if (it is Chapter) {
                            it.registry = registry
                        }
                        it.asSerializable()
                    }
                    books.add(Book(usx.reference ?: Reference(name.usfm), name.asSerializable(), serializableChapters))
                }
            }
            Bible(
                BibleMetadata(
                    identification = it.identification.asSerializable(),
                    publication = it.publication.asSerializable(),
                    language = it.language.asSerializable()
                ), books, stylesheet ?: emptyList()
            )
        }
    }

    private fun Map<String, ByteArray>.relativeToMetadataRoot(): Map<String, ByteArray> {
        val normalized = entries.associate { it.key.normalizedZipPath() to it.value }
        val metadataPaths = normalized.keys.filter { it == "metadata.xml" || it.endsWith("/metadata.xml") }
        val metadataPath = when {
            "metadata.xml" in metadataPaths -> "metadata.xml"
            metadataPaths.size == 1 -> metadataPaths.single()
            metadataPaths.isEmpty() -> error("missing metadata.xml")
            else -> error("multiple metadata.xml files found in DBL bundle")
        }
        val root = metadataPath.removeSuffix("metadata.xml")
        return normalized.entries
            .asSequence()
            .filter { it.key.startsWith(root) }
            .associate { it.key.removePrefix(root) to it.value }
    }

    private fun String.normalizedZipPath(): String = replace('\\', '/')
        .trimStart('/')
        .removePrefix("./")
}
