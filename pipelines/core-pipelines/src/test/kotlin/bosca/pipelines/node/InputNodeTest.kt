package bosca.pipelines.node

import bosca.pipelines.annotation.SlotKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [InputNode] [HasDeclaredOutput]: a specific accepted type (an event or a catalogued object type)
 * declares the pipeline entry's output as that type, so downstream typed slots are validated and can be
 * introspected; JSON/blank stays untyped.
 */
class InputNodeTest {

    @Test
    fun `a specific object accepted type is declared as an OBJECT output of that type`() {
        val node = InputNode(id = "in", acceptedType = "bosca.workops.model.release.ReleaseProjectVersion")
        assertEquals(SlotKind.OBJECT, node.declaredOutputKind)
        assertEquals("bosca.workops.model.release.ReleaseProjectVersion", node.declaredOutputType)
    }

    @Test
    fun `an event fqdn is declared as its object type`() {
        val node = InputNode(id = "in", acceptedType = "bosca.workops.model.release.ReleaseStarted")
        assertEquals(SlotKind.OBJECT, node.declaredOutputKind)
        assertEquals("bosca.workops.model.release.ReleaseStarted", node.declaredOutputType)
    }

    @Test
    fun `a JSON input declares no specific output type`() {
        val node = InputNode(id = "in", acceptedType = InputNode.JSON_TYPE)
        assertEquals(SlotKind.ANY, node.declaredOutputKind)
        assertEquals("", node.declaredOutputType)
    }

    @Test
    fun `a blank accepted type declares no specific output type`() {
        val node = InputNode(id = "in", acceptedType = "")
        assertEquals(SlotKind.ANY, node.declaredOutputKind)
        assertEquals("", node.declaredOutputType)
    }
}
