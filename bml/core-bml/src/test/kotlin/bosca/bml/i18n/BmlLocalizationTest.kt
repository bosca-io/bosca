package bosca.bml.i18n

import bosca.bml.graphql.GraphQLClient
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The cached localization binding: synchronous first load, TTL +
 * stale-while-revalidate, failure degradation (never throws, keeps last good, backs off a TTL),
 * and the dev zero-TTL inline cadence — over a scripted upstream with an injected clock.
 */
class BmlLocalizationTest {

    private val projectId = "6a4f0b6e-7f3a-4b1e-9a5d-2c8e1f000002"

    /** Scripted upstream: switchable answers + failure mode + call counting. */
    private class Upstream : GraphQLClient {
        @Volatile var failing = false
        @Volatile var title = "Welcome"
        @Volatile var source = "en"
        @Volatile var targets = listOf("es")
        val configCalls = AtomicInteger()
        val catalogCalls = AtomicInteger()
        val totalCalls = AtomicInteger() // every attempt, including ones that fail

        override suspend fun execute(
            query: String,
            variables: JsonObject?,
            operationName: String?,
            token: String?,
        ): JsonElement {
            totalCalls.incrementAndGet()
            if (failing) throw RuntimeException("upstream down")
            return when (operationName) {
                "BmlLocalizationConfig" -> {
                    configCalls.incrementAndGet()
                    val languages = targets.joinToString(",") { """{"languageTag": "$it"}""" }
                    Json.parseToJsonElement(
                        """{"localization": {"project": {"sourceLanguage": "$source", "languages": [$languages]}}}""",
                    )
                }
                "BmlLocalizationCatalog" -> {
                    catalogCalls.incrementAndGet()
                    Json.parseToJsonElement(
                        """{"localization": {"project": {"strings": [
                            {"key": "home.title", "plural": false, "translations": [
                                {"languageTag": "en", "text": "$title", "state": "PUBLISHED"}
                            ], "pluralTranslations": []}
                        ]}}}""",
                    )
                }
                else -> Json.parseToJsonElement("{}")
            }
        }
    }

    private fun localization(
        upstream: Upstream,
        ttl: Duration = 5.minutes,
        clock: () -> Long,
    ) = BmlLocalization(GraphQLLocalizationClient(upstream, projectId), ttl, clock)

    @Test
    fun `first read loads synchronously and later reads hit the cache`() = runBlocking {
        val upstream = Upstream()
        var time = 0L
        localization(upstream, clock = { time }).use { l10n ->
            assertEquals(listOf("en", "es"), l10n.current().supported.map { it.toLanguageTag() })
            assertEquals("Welcome", l10n.catalog(Locale.ENGLISH).message("home.title"))
            repeat(5) { l10n.catalog(Locale.ENGLISH) }
            assertEquals(1, upstream.catalogCalls.get(), "fresh reads must not refetch")
            assertEquals("en", l10n.defaultLocale.toLanguageTag())
        }
    }

    @Test
    fun `an unreachable upstream degrades to the fallback policy and empty catalogs`() = runBlocking {
        val upstream = Upstream().apply { failing = true }
        var time = 0L
        localization(upstream, clock = { time }).use { l10n ->
            assertEquals(listOf("en"), l10n.current().supported.map { it.toLanguageTag() })
            assertEquals(true, l10n.catalog(Locale.ENGLISH).isEmpty)
        }
    }

    @Test
    fun `a stale read serves the old value and refreshes in the background`() = runBlocking {
        val upstream = Upstream()
        var time = 0L
        localization(upstream, ttl = 5.minutes, clock = { time }).use { l10n ->
            assertEquals("Welcome", l10n.catalog(Locale.ENGLISH).message("home.title"))
            upstream.title = "Hello again"
            time += 6.minutes.inWholeMilliseconds
            // Stale read: still the old value, refresh fired behind it.
            assertEquals("Welcome", l10n.catalog(Locale.ENGLISH).message("home.title"))
            withTimeout(5_000) {
                while (l10n.catalog(Locale.ENGLISH).message("home.title") != "Hello again") delay(10)
            }
        }
    }

    @Test
    fun `a failed refresh keeps the last good value and backs off a full ttl`() = runBlocking {
        val upstream = Upstream()
        var time = 0L
        localization(upstream, ttl = 5.minutes, clock = { time }).use { l10n ->
            assertEquals("Welcome", l10n.catalog(Locale.ENGLISH).message("home.title"))
            upstream.failing = true
            time += 6.minutes.inWholeMilliseconds
            assertEquals("Welcome", l10n.catalog(Locale.ENGLISH).message("home.title"))
            // Wait for the background attempt to fire and the call rate to go quiet.
            withTimeout(5_000) {
                while (true) {
                    val calls = upstream.totalCalls.get()
                    delay(50)
                    if (upstream.totalCalls.get() == calls) break
                }
            }
            val callsAfterFailure = upstream.totalCalls.get()
            repeat(5) { l10n.catalog(Locale.ENGLISH) }
            assertEquals(callsAfterFailure, upstream.totalCalls.get(), "failure must back off a full ttl")
            assertEquals("Welcome", l10n.catalog(Locale.ENGLISH).message("home.title"), "last good value survives")
        }
    }

    @Test
    fun `zero ttl refreshes inline on every read`() = runBlocking {
        val upstream = Upstream()
        var time = 0L
        localization(upstream, ttl = Duration.ZERO, clock = { time }).use { l10n ->
            assertEquals("Welcome", l10n.catalog(Locale.ENGLISH).message("home.title"))
            upstream.title = "Edited"
            assertEquals("Edited", l10n.catalog(Locale.ENGLISH).message("home.title"))
            upstream.failing = true
            assertEquals("Edited", l10n.catalog(Locale.ENGLISH).message("home.title"), "failure serves last good")
        }
    }

    @Test
    fun `policy updates when the project gains a language`() = runBlocking {
        val upstream = Upstream()
        var time = 0L
        localization(upstream, ttl = 5.minutes, clock = { time }).use { l10n ->
            assertEquals(listOf("en", "es"), l10n.current().supported.map { it.toLanguageTag() })
            upstream.targets = listOf("es", "pt-BR")
            time += 6.minutes.inWholeMilliseconds
            l10n.current() // stale read triggers the refresh
            withTimeout(5_000) {
                while (l10n.current().supported.size != 3) delay(10)
            }
            assertEquals(listOf("en", "es", "pt-BR"), l10n.current().supported.map { it.toLanguageTag() })
        }
    }
}
