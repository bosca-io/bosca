package bosca.content.graphql

import bosca.category.graphql.CategoryMutation
import bosca.content.collection.graphql.CollectionMutation
import bosca.content.healthcheck.ContentHealthCheckMutation
import bosca.content.metadata.graphql.MetadataMutation
import bosca.content.state.graphql.WorkflowStatesMutation
import bosca.content.tools.graphql.TemplateAttributeToolsMutation
import bosca.content.transition.graphql.TransitionsMutation
import bosca.source.graphql.SourceMutation
import io.mockk.clearAllMocks
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame

class ContentMutationControllerCoverageTest {

    private val controller = ContentMutationController()

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `collection returns CollectionMutation`() {
        assertSame(CollectionMutation, controller.collection())
    }

    @Test
    fun `metadata returns MetadataMutation`() {
        assertSame(MetadataMutation, controller.metadata())
    }

    @Test
    fun `rebuildStorageSystemContent returns false`() {
        assertFalse(controller.rebuildStorageSystemContent())
    }

    @Test
    fun `resizeImage returns false`() {
        assertFalse(controller.resizeImage())
    }

    @Test
    fun `sources returns SourceMutation`() {
        assertSame(SourceMutation, controller.sources())
    }

    @Test
    fun `states returns WorkflowStatesMutation`() {
        assertSame(WorkflowStatesMutation, controller.states())
    }

    @Test
    fun `category returns CategoryMutation`() {
        assertSame(CategoryMutation, controller.category())
    }

    @Test
    fun `healthCheck returns ContentHealthCheckMutation`() {
        assertSame(ContentHealthCheckMutation, controller.healthCheck())
    }

    @Test
    fun `transitions returns TransitionsMutation`() {
        assertSame(TransitionsMutation, controller.transitions())
    }

    @Test
    fun `templateAttributeTools returns TemplateAttributeToolsMutation`() {
        assertSame(TemplateAttributeToolsMutation, controller.templateAttributeTools())
    }
}
