package bosca.security.graphql

import bosca.graphql.GraphQLController
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GroupControllerTest {

    private val controller = GroupController()

    private val groupId = UUID.random()
    private val group = Group(
        id = groupId,
        name = "administrators",
        description = "Admin group",
        type = GroupType.SYSTEM
    )

    @Test
    fun `GroupController implements GraphQLController`() {
        assertIs<GraphQLController<Group>>(controller)
    }

    @Test
    fun `id returns group id`() {
        assertEquals(groupId, controller.id(group))
    }

    @Test
    fun `type returns group type`() {
        assertEquals(GroupType.SYSTEM, controller.type(group))
    }

    @Test
    fun `name returns group name`() {
        assertEquals("administrators", controller.name(group))
    }

    @Test
    fun `description returns group description`() {
        assertEquals("Admin group", controller.description(group))
    }
}
