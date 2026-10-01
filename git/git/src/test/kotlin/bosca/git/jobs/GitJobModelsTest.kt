package bosca.git.jobs

import bosca.search.IndexStorageSystem
import bosca.serialization.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Serializer round-trips for the git module's job payloads (sparse and
 * defaults-encoded forms cover both arms of every field's write/skip and
 * decode/mask branches), mirroring DfsModelsTest.
 */
class GitJobModelsTest {

    private val json = Json {
        serializersModule = SerializersModule {
            contextual(kotlin.uuid.Uuid::class, kotlinx.serialization.serializer<kotlin.uuid.Uuid>())
            contextual(java.time.OffsetDateTime::class, bosca.serialization.OffsetDateTimeSerializer())
        }
    }
    private val jsonDefaults = Json(json) { encodeDefaults = true }

    private fun <T> roundTrip(serializer: kotlinx.serialization.KSerializer<T>, value: T) {
        for (encoder in listOf(json, jsonDefaults)) {
            assertEquals(value, json.decodeFromString(serializer, encoder.encodeToString(serializer, value)))
        }
    }

    @Test
    fun `file content index job round-trips in sparse and full forms`() {
        val id = UUID.random()
        roundTrip(FileContentIndexJob.serializer(), FileContentIndexJob(repositoryId = id, ref = "refs/heads/main"))
        roundTrip(
            FileContentIndexJob.serializer(),
            FileContentIndexJob(
                storage = IndexStorageSystem(UUID.random(), "git-code"),
                repositoryId = id, ref = "refs/heads/main",
                beforeSha = "a".repeat(40), afterSha = "b".repeat(40),
            ),
        )
        // Equality arms.
        val a = FileContentIndexJob(repositoryId = id, ref = "r")
        assertEquals(a, a.copy())
        kotlin.test.assertNotEquals(a, a.copy(ref = "other"))
        kotlin.test.assertNotEquals(a, a.copy(beforeSha = "x"))
        kotlin.test.assertNotEquals(a, a.copy(afterSha = "x"))
        kotlin.test.assertNotEquals(a, a.copy(storage = IndexStorageSystem(null, "s")))
    }

    @Test
    fun `repository index and reindex jobs round-trip in sparse and full forms`() {
        val id = UUID.random()
        roundTrip(RepositoryIndexJob.serializer(), RepositoryIndexJob())
        roundTrip(
            RepositoryIndexJob.serializer(),
            RepositoryIndexJob(storage = IndexStorageSystem(UUID.random(), "git-code"), repositoryId = id, deleteOnly = true),
        )
        roundTrip(ReindexAllJob.serializer(), ReindexAllJob())
        roundTrip(ReindexAllJob.serializer(), ReindexAllJob(storage = IndexStorageSystem(UUID.random(), "git-code")))

        val a = RepositoryIndexJob(repositoryId = id)
        assertEquals(a, a.copy())
        kotlin.test.assertNotEquals(a, a.copy(deleteOnly = true))
        kotlin.test.assertNotEquals(ReindexAllJob(), ReindexAllJob(storage = IndexStorageSystem(null, "x")))
    }

    @Test
    fun `commit file input round-trips in sparse and full forms`() {
        val id = UUID.random()
        val sparse = bosca.git.graphql.GraphQLCommitFileInput(
            repositoryId = id, path = "a", content = "x", message = "m",
            authorName = "n", authorEmail = "e",
        )
        roundTrip(bosca.git.graphql.GraphQLCommitFileInput.serializer(), sparse)
        roundTrip(bosca.git.graphql.GraphQLCommitFileInput.serializer(), sparse.copy(branch = "dev"))
    }
}
