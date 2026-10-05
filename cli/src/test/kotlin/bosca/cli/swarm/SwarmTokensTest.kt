package bosca.cli.swarm

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SwarmTokensTest {
    @Test
    fun `setup creates three site-specific tokens once and persists each raw value`() = runBlocking {
        val original = SwarmConfig().withSecrets()
        val created = mutableListOf<Triple<String, String, List<String>?>>()
        val saved = mutableListOf<SwarmConfig>()
        val updated = provisionSwarmTokens(original, create = { site, name, scopes ->
            created += Triple(site.id, name, scopes)
            "bsk_${site.id}_${created.size}"
        }, persist = saved::add)

        assertEquals(6, created.size)
        assertEquals(6, saved.size)
        assertEquals(null, created[0].third)
        assertEquals(listOf("artifacts:ml:model/*:*:push"), created[1].third)
        assertEquals(listOf("artifacts:ml:model/*:*:pull"), created[2].third)
        assertEquals("bsk_site1_1", saved[0].sites[0].ml.boscaToken)
        assertEquals("", saved[0].sites[0].ml.artifactsPushToken)
        assertEquals("bsk_site2_6", updated.sites[1].ml.artifactsPullToken)

        val repeated = provisionSwarmTokens(updated, create = { _, _, _ -> error("should not mint another token") },
            persist = { error("should not rewrite the config") })
        assertEquals(updated, repeated)
    }

    @Test
    fun `a failed later token keeps earlier tokens saved for retry`() = runBlocking {
        val original = SwarmConfig().withSecrets()
        var saved = original
        var count = 0
        assertFailsWith<IllegalStateException> {
            provisionSwarmTokens(original, create = { _, _, _ ->
                count++
                if (count == 3) error("issuer failed")
                "bsk_$count"
            }, persist = { saved = it })
        }
        assertEquals("bsk_1", saved.sites[0].ml.boscaToken)
        assertEquals("bsk_2", saved.sites[0].ml.artifactsPushToken)
        assertEquals("", saved.sites[0].ml.artifactsPullToken)
    }

    @Test
    fun `setup creates and saves a scoped root image pull token once`() = runBlocking {
        val base = SwarmConfig().withSecrets()
        val site = base.sites.first().copy(
            rootImage = "artifacts.example.org/sites/home:0.0.4",
            rootRegistryAuth = SwarmRegistryAuth(server = "artifacts.example.org", username = "api_token", password = ""),
        )
        val original = base.copy(sites = listOf(site) + base.sites.drop(1))
        val created = mutableListOf<Triple<String, String, List<String>?>>()
        val saved = mutableListOf<SwarmConfig>()
        val updated = provisionSwarmTokens(original, create = { target, name, scopes ->
            created += Triple(target.id, name, scopes)
            "bsk_${created.size}"
        }, persist = saved::add)

        assertEquals(7, created.size)
        assertEquals(7, saved.size)
        assertEquals("swarm-root-image-pull", created[3].second)
        assertEquals(listOf("artifacts:docker:sites/home:*:pull"), created[3].third)
        assertEquals("bsk_4", saved[3].sites[0].rootRegistryAuth?.password)
        assertEquals("bsk_4", updated.sites[0].rootRegistryAuth?.password)
        assertEquals(updated, provisionSwarmTokens(updated,
            create = { _, _, _ -> error("should not mint another token") },
            persist = { error("should not rewrite the config") }))
    }
}
