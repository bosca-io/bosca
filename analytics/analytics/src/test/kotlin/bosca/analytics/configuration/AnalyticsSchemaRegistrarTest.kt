package bosca.analytics.configuration

import bosca.graphql.annotations.Schemas
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AnalyticsSchemaRegistrarTest {

    @Test
    fun `SchemaRegistrar interface has Schemas annotation`() {
        val annotation = SchemaRegistrar::class.java.getAnnotation(Schemas::class.java)
        assertNotNull(annotation)
    }

    @Test
    fun `SchemaRegistrar declares analytics getter`() {
        val method = SchemaRegistrar::class.java.getMethod("getAnalytics")
        assertNotNull(method)
        assertTrue(method.returnType == String::class.java)
    }

    @Test
    fun `SchemaRegistrar declares visualizations getter`() {
        val method = SchemaRegistrar::class.java.getMethod("getVisualizations")
        assertNotNull(method)
        assertTrue(method.returnType == String::class.java)
    }

    @Test
    fun `SchemaRegistrar declares dashboards getter`() {
        val method = SchemaRegistrar::class.java.getMethod("getDashboards")
        assertNotNull(method)
        assertTrue(method.returnType == String::class.java)
    }
}
