package bosca.workops.pipeline

import bosca.pipelines.node.NodePosition
import bosca.workops.model.artifact.ArtifactType
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PipelineNodeConfigurationSerializationTest {

    private val sparseJson = Json
    private val explicitJson = Json { encodeDefaults = true }

    private inline fun <reified T> assertConfigurationRoundTrips(value: T) {
        val sparse = sparseJson.encodeToString(value)
        assertEquals(sparse, sparseJson.encodeToString(sparseJson.decodeFromString<T>(sparse)))

        val explicit = explicitJson.encodeToString(value)
        assertEquals(explicit, explicitJson.encodeToString(explicitJson.decodeFromString<T>(explicit)))
    }

    private inline fun <reified T> assertRequiresId() {
        assertFailsWith<SerializationException> { sparseJson.decodeFromString<T>("{}") }
    }

    @Test
    fun `pipeline node wire formats reject a missing authored id`() {
        assertRequiresId<GetEnvironmentNode>()
        assertRequiresId<CreateEnvironmentPromotionNode>()
        assertRequiresId<CreateEnvironmentDeploymentNode>()
        assertRequiresId<SelectPublicationNode>()
        assertRequiresId<WaitForHealthyNode>()
        assertRequiresId<GetVersionNode>()
        assertRequiresId<SpecEventToSpecNode>()
        assertRequiresId<RequirementEventToRequirementNode>()
        assertRequiresId<MarkDeploymentDeployedNode>()
        assertRequiresId<TaskEventToTaskNode>()
        assertRequiresId<GetProjectRepositoriesNode>()
        assertRequiresId<GetProjectNode>()
        assertRequiresId<GetSprintNode>()
    }

    @Test
    fun `pipeline node configuration restores omitted defaults and accepts explicit defaults`() {
        assertConfigurationRoundTrips(GetEnvironmentNode("environment"))
        assertConfigurationRoundTrips(CreateEnvironmentPromotionNode("promote"))
        assertConfigurationRoundTrips(CreateEnvironmentDeploymentNode("deploy"))
        assertConfigurationRoundTrips(SelectPublicationNode("publication"))
        assertConfigurationRoundTrips(WaitForHealthyNode("healthy"))
        assertConfigurationRoundTrips(GetVersionNode("version"))
        assertConfigurationRoundTrips(SpecEventToSpecNode("spec"))
        assertConfigurationRoundTrips(RequirementEventToRequirementNode("requirement"))
        assertConfigurationRoundTrips(MarkDeploymentDeployedNode("deployed"))
        assertConfigurationRoundTrips(TaskEventToTaskNode("task"))
        assertConfigurationRoundTrips(GetProjectRepositoriesNode("repositories"))
        assertConfigurationRoundTrips(GetProjectNode("project"))
        assertConfigurationRoundTrips(GetSprintNode("sprint"))
    }

    @Test
    fun `pipeline node configuration preserves every authored setting`() {
        val position = NodePosition(12.5, 24.0)
        assertConfigurationRoundTrips(
            GetEnvironmentNode(
                "environment",
                "Environment",
                "Resolve production",
                "production",
                "prod-us",
                listOf("staging"),
                true,
                position,
            ),
        )
        assertConfigurationRoundTrips(CreateEnvironmentPromotionNode("promote", "Promote", "Promote", position))
        assertConfigurationRoundTrips(CreateEnvironmentDeploymentNode("deploy", "Deploy", "Deploy", position))
        assertConfigurationRoundTrips(
            SelectPublicationNode("publication", "Publication", "Select", ArtifactType.HELM, position),
        )
        assertConfigurationRoundTrips(WaitForHealthyNode("healthy", "Healthy", "Wait", position))
        assertConfigurationRoundTrips(GetVersionNode("version", "Version", "Resolve", position))
        assertConfigurationRoundTrips(SpecEventToSpecNode("spec", "Spec", "Resolve", position))
        assertConfigurationRoundTrips(
            RequirementEventToRequirementNode("requirement", "Requirement", "Resolve", position),
        )
        assertConfigurationRoundTrips(MarkDeploymentDeployedNode("deployed", "Deployed", "Mark", position))
        assertConfigurationRoundTrips(TaskEventToTaskNode("task", "Task", "Resolve", position))
        assertConfigurationRoundTrips(
            GetProjectRepositoriesNode("repositories", "Repositories", "Resolve", position),
        )
        assertConfigurationRoundTrips(GetProjectNode("project", "Project", "Resolve", position))
        assertConfigurationRoundTrips(GetSprintNode("sprint", "Sprint", "Resolve", position))
    }
}
