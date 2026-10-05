package bosca.segmentation.configuration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SegmentationMigrationTest {

    private val migration = SegmentationMigration()

    @Test
    fun schemaIsSegmentation() {
        assertEquals("segmentation", migration.schema)
    }

    @Test
    fun resourcesAreOrdered() {
        assertTrue(migration.resources.isNotEmpty())
        assertEquals("V1__segmentation.sql", migration.resources.first())
    }

    @Test
    fun allResourcesFollowNamingConvention() {
        for (resource in migration.resources) {
            assertTrue(resource.startsWith("V"), "Resource should start with V: $resource")
            assertTrue(resource.endsWith(".sql"), "Resource should end with .sql: $resource")
        }
    }

    @Test
    fun resourceCount() {
        assertEquals(5, migration.resources.size)
    }
}
