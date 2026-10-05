package bosca.workops.graphql

import bosca.workops.model.artifact.ArtifactType
import bosca.workops.model.artifact.PublicationStatus
import bosca.workops.model.dependency.DependencyType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Kotlin enums and their SDL counterparts drift SILENTLY: a value added only in Kotlin serializes
 * fine at the model layer and then fails GraphQL coercion the first time a real row carries it —
 * surfacing as an empty list behind a swallowed error, far from the cause. (HELM_VALUES publications
 * vanished from every artifacts query this way.) This pins each enum to its schema declaration.
 */
class SchemaEnumParityTest {

    private val sdl: String by lazy {
        checkNotNull(javaClass.getResourceAsStream("/graphql/workops/multirepo.graphqls")) {
            "multirepo.graphqls not on the test classpath"
        }.bufferedReader().readText()
    }

    private fun sdlEnumValues(name: String): List<String> {
        val match = Regex("enum $name \\{ ([^}]*) }").find(sdl)
            ?: error("enum $name not found in multirepo.graphqls")
        return match.groupValues[1].split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    @Test
    fun `WorkOpsArtifactType matches ArtifactType exactly, in order`() {
        assertEquals(ArtifactType.entries.map { it.name }, sdlEnumValues("WorkOpsArtifactType"))
    }

    @Test
    fun `WorkOpsPublicationStatus matches PublicationStatus exactly, in order`() {
        assertEquals(PublicationStatus.entries.map { it.name }, sdlEnumValues("WorkOpsPublicationStatus"))
    }

    @Test
    fun `WorkOpsDependencyType matches DependencyType exactly, in order`() {
        assertEquals(DependencyType.entries.map { it.name }, sdlEnumValues("WorkOpsDependencyType"))
    }
}
