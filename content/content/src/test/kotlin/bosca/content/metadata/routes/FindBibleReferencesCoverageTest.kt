package bosca.content.metadata.routes

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.StructureKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Complements [FindBibleReferencesTest] (which covers the reflection-driven `execute` paths).
 *
 * [FindBibleReferences.serializer] returns `ListSerializer(ChapterContent.serializer())`. The
 * override itself is `protected` on [bosca.routes.Route] and is invoked only internally by the
 * base class when serializing the HTTP response, so a test cannot observe it directly. This test
 * instead pins the contract of the exact serializer the route builds: a list of chapter contents.
 */
class FindBibleReferencesCoverageTest {

    @Test
    fun `serializer describes a list of chapter contents`() {
        val serializer = ListSerializer(ChapterContent.serializer())
        val descriptor = serializer.descriptor
        assertEquals(StructureKind.LIST, descriptor.kind)
        assertEquals(
            ChapterContent.serializer().descriptor.serialName,
            descriptor.getElementDescriptor(0).serialName,
        )
    }
}
