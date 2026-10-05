package bosca.installer.graphql

import kotlin.test.Test
import kotlin.test.assertNotNull

class PackagesTest {

    @Test
    fun packagesIsObject() {
        assertNotNull(Packages)
    }

    @Test
    fun packagesMutationIsObject() {
        assertNotNull(PackagesMutation)
    }
}
