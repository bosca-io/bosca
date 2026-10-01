package bosca.nats.admin.graphql

import bosca.graphql.annotations.Schema
import bosca.graphql.annotations.Schemas
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SchemaRegistrarTest {

    @Test
    fun `SchemaRegistrar interface has Schemas annotation`() {
        val annotation = SchemaRegistrar::class.java.getAnnotation(Schemas::class.java)
        assertNotNull(annotation)
    }

    @Test
    fun `natsAdmin property has Schema annotation with correct resource name`() {
        val property = SchemaRegistrar::class.members.first { it.name == "natsAdmin" }
        val schemaAnnotation = property.annotations.filterIsInstance<Schema>().firstOrNull()
        assertNotNull(schemaAnnotation)
        assertTrue(schemaAnnotation.resource.contains("nats-admin"))
    }
}
