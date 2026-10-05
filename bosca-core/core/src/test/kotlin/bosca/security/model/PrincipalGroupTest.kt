package bosca.security.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class PrincipalGroupTest {

    @Test
    fun fieldsArePreserved() {
        val pid = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
        val gid = Uuid.parse("660e8400-e29b-41d4-a716-446655440001")
        val pg = PrincipalGroup(principal = pid, groupId = gid)
        assertEquals(pid, pg.principal)
        assertEquals(gid, pg.groupId)
    }

    @Test
    fun dataClassEquality() {
        val pid = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
        val gid = Uuid.parse("660e8400-e29b-41d4-a716-446655440001")
        val a = PrincipalGroup(principal = pid, groupId = gid)
        val b = PrincipalGroup(principal = pid, groupId = gid)
        assertEquals(a, b)
    }
}
