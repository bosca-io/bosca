package bosca.cli.artifacts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ArtifactRegistryTest {
    @Test
    fun `registry transport defaults to HTTPS except for local development`() {
        for ((input, expected) in mapOf(
            "artifacts.example.com" to "https://artifacts.example.com",
            "artifacts.example.com:8443/" to "https://artifacts.example.com:8443",
            "localhost:8084" to "http://localhost:8084",
            "127.0.0.1:8084" to "http://127.0.0.1:8084",
            "[::1]:8084" to "http://[::1]:8084",
            "https://localhost:8084/" to "https://localhost:8084",
            "http://artifacts.example.com:8084/" to "http://artifacts.example.com:8084",
        )) {
            assertEquals(expected, artifactRegistryUrl(input).toString(), input)
        }
    }

    @Test
    fun `registry URLs cannot include credentials paths or query parameters`() {
        for (input in listOf(
            "ftp://artifacts.example.com", "https://user:password@artifacts.example.com",
            "https://artifacts.example.com/npm/", "https://artifacts.example.com?token=value",
            "https://artifacts.example.com#fragment", "",
        )) {
            assertFailsWith<IllegalArgumentException>(input) { artifactRegistryUrl(input) }
        }
    }
}
