package bible.grammar

import bosca.bible.processor.BibleFactoryImpl
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BibleBundleLayoutTest {

    @Test
    fun `loads a DBL bundle contained in a wrapper directory`() = runTest {
        val resource = javaClass.getResource("/asv.zip")
        if (resource == null) {
            System.err.println("\n************************************************************************\n" +
                "WARNING: ASV BIBLE BUNDLE IS MISSING; WRAPPED BUNDLE TEST IS SKIPPED.\n" +
                "Set BIBLE_RAW_ARTIFACTS and run downloadBibleTestResources.\n" +
                "************************************************************************\n")
        }
        assumeTrue("ASV test bundle is required", resource != null)
        val source = checkNotNull(resource).openStream()
        val wrapped = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { target ->
                ZipInputStream(source).use { input ->
                    var entry = input.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            target.putNextEntry(ZipEntry("text-example-123/${entry.name}"))
                            input.copyTo(target)
                            target.closeEntry()
                        }
                        input.closeEntry()
                        entry = input.nextEntry
                    }
                }
            }
            output.toByteArray()
        }

        val bibles = BibleFactoryImpl().getBibles(ByteArrayInputStream(wrapped))

        assertTrue(bibles.isNotEmpty())
        assertEquals("eng", bibles.first().metadata.language.iso)
        assertTrue(bibles.maxOf { it.books.size } >= 66)
    }

    @Test
    fun `reports a missing metadata file clearly`() = runTest {
        val archive = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { target ->
                target.putNextEntry(ZipEntry("release/GEN.usx"))
                target.write("<usx/>".toByteArray())
                target.closeEntry()
            }
            output.toByteArray()
        }

        val exception = assertFailsWith<IllegalStateException> {
            BibleFactoryImpl().getBibles(ByteArrayInputStream(archive))
        }

        assertEquals("missing metadata.xml", exception.message)
    }
}
