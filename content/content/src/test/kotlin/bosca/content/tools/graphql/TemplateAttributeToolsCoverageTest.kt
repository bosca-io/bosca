package bosca.content.tools.graphql

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * Coverage for the two marker objects in TemplateAttributeTools.kt.
 *
 * Both are empty Kotlin `object` declarations used purely as GraphQL type markers. Referencing
 * each object triggers its static initializer (the INSTANCE field), which is the only executable
 * line in the file.
 */
class TemplateAttributeToolsCoverageTest {

    @Test
    fun `TemplateAttributeTools object is instantiated`() {
        assertNotNull(TemplateAttributeTools)
        assertSame(TemplateAttributeTools, TemplateAttributeTools)
    }

    @Test
    fun `TemplateAttributeToolsMutation object is instantiated`() {
        assertNotNull(TemplateAttributeToolsMutation)
        assertSame(TemplateAttributeToolsMutation, TemplateAttributeToolsMutation)
    }
}
