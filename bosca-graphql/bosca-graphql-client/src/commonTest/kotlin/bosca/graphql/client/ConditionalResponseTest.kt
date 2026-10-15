package bosca.graphql.client

import bosca.graphql.client.conditional.ConditionalFragment
import bosca.graphql.client.conditional.ConditionalNode
import bosca.graphql.client.conditional.ConditionalNodeData
import bosca.graphql.client.conditional.IRunFields
import bosca.graphql.client.conditional.PipelineCatalog
import bosca.graphql.client.conditional.PipelineCatalogData
import bosca.graphql.client.conditional.RepeatedField
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** These operations are generated during the build, then compiled with the serialization plugin. */
class ConditionalResponseTest {
    private fun response(run: String) = """{"git":{"pipelines":[{"runs":[$run]}]}}"""

    @Test
    fun `pipeline runs decode when job details and summary are omitted`() {
        val payload = response("""{"id":"run-1"}""")
        val run = PipelineCatalog.decodeData(GraphQLJson.parseToJsonElement(payload))
            .git.pipelines.single().runs.single()
        assertEquals("run-1", run.id)
        assertNull(run.jobs)
        assertNull(run.summary)
        assertNull(run.note)
        assertNull(run.latest)
        assertNull(run.labels)
        // A strict decoder also needs explicit defaults for absent conditional properties.
        assertEquals(run, Json.decodeFromString(PipelineCatalogData.serializer(), payload)
            .git.pipelines.single().runs.single())
    }

    @Test
    fun `included job details retain their values and list element nullability`() {
        val payload = response("""{
            "id":"run-1", "jobs":[{"id":"job-1","name":"Build"}],
            "summary":"Passed", "note":null, "latest":{"id":"job-1"},
            "labels":[["linux",null]]
        }""")
        val run = PipelineCatalog.decodeData(GraphQLJson.parseToJsonElement(payload))
            .git.pipelines.single().runs.single()
        assertEquals("Build", run.jobs.orEmpty().single().name)
        assertEquals("Passed", run.summary)
        assertNull(run.note)
        assertEquals("job-1", run.latest?.id)
        assertEquals(listOf(listOf("linux", null)), run.labels)
        val emptyJobs = PipelineCatalog.decodeData(GraphQLJson.parseToJsonElement(
            response("""{"id":"run-1","jobs":[]}"""),
        )).git.pipelines.single().runs.single().jobs
        assertEquals(emptyList(), emptyJobs)
    }

    @Test
    fun `unconditional fields and included child fields remain required`() {
        for (run in listOf("{}", """{"id":"run-1","jobs":[{"id":"job-1"}]}""",
            """{"id":"run-1","jobs":[null]}""")) {
            assertFailsWith<SerializationException> {
                PipelineCatalog.decodeData(GraphQLJson.parseToJsonElement(response(run)))
            }
        }
        assertTrue("jobs @include(if:" in PipelineCatalog.document)
        assertTrue("summary @skip(if:" in PipelineCatalog.document)
    }

    @Test
    fun `conditional fields in a named fragment satisfy its generated interface`() {
        val run: IRunFields = ConditionalFragment.decodeData(GraphQLJson.parseToJsonElement(
            response("""{"id":"run-1"}"""),
        )).git.pipelines.single().runs.single()
        assertEquals("run-1", run.id)
        assertNull(run.jobs)
    }

    @Test
    fun `an unconditional fragment field remains required after a conditional selection`() {
        val run = RepeatedField.decodeData(GraphQLJson.parseToJsonElement(
            response("""{"id":"run-1"}"""),
        )).git.pipelines.single().runs.single()
        assertEquals("run-1", run.id)
        assertFailsWith<SerializationException> {
            RepeatedField.decodeData(GraphQLJson.parseToJsonElement(response("{}")))
        }
    }

    @Test
    fun `conditional common and branch fields decode in polymorphic responses`() {
        val job = assertIs<ConditionalNodeData.Node.Job>(ConditionalNode.decodeData(
            GraphQLJson.parseToJsonElement("""{"node":{"__typename":"Job"}}"""),
        ).node)
        assertNull(job.id)
        assertNull(job.name)
        val other = assertIs<ConditionalNodeData.Node.Other>(ConditionalNode.decodeData(
            GraphQLJson.parseToJsonElement("""{"node":{"__typename":"Unknown"}}"""),
        ).node)
        assertNull(other.id)
    }
}
