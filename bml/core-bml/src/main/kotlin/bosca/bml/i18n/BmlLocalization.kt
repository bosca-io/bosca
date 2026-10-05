package bosca.bml.i18n

import bosca.bml.render.BmlLocalePolicy
import bosca.bml.render.BmlLocales
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

/**
 * The site's live localization binding: one object that is BOTH the
 * [MessageSource] renders resolve against and the [BmlLocalePolicy] requests negotiate against —
 * both derived from the site's bound Bosca localization project and cached together, so locales
 * and strings can never disagree about which project defines them.
 *
 * Caching is per slot (the project config, each locale's catalog) with a [ttl] and
 * stale-while-revalidate: the first request loads synchronously; after expiry the stale value
 * keeps serving while ONE background refresh runs; a failed load logs a warning, keeps the last
 * good value, and backs off a full [ttl] before the next attempt — a down upstream degrades
 * rendering to authored fallbacks/keys, it never fails a request and never log-storms.
 * `Duration.ZERO` (dev) refreshes inline on every read so translation edits land immediately.
 *
 * Structured concurrency: refreshes run in an owned supervisor scope; [close] cancels it.
 */
class BmlLocalization(
    private val client: GraphQLLocalizationClient,
    private val ttl: Duration = 5.minutes,
    private val now: () -> Long = System::currentTimeMillis,
) : MessageSource, BmlLocalePolicy, AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val config = Slot("project config", FALLBACK_CONFIG) { client.fetchConfig() }
    private val catalogs = ConcurrentHashMap<String, Slot<MessageCatalog>>()

    /** The negotiation policy the project defines: source language first, then targets. */
    override suspend fun current(): BmlLocales = config.get().locales

    /** Every lookup chain's terminal — the project's source language (en until first load). */
    override val defaultLocale: Locale get() = config.value.sourceLocale

    override suspend fun catalog(locale: Locale): MessageCatalog {
        val tag = locale.toLanguageTag().lowercase()
        return catalogs.computeIfAbsent(tag) {
            Slot("catalog '$tag'", MessageCatalog.EMPTY) { client.fetchCatalog(locale) }
        }.get()
    }

    override fun close(): Unit = scope.cancel()

    /**
     * One cached value: [fallback] until the first successful load, then always the last good
     * value. Reads never throw and — outside the very first load and `ttl == ZERO` — never wait.
     */
    private inner class Slot<T>(
        private val name: String,
        private val fallback: T,
        private val load: suspend () -> T,
    ) {
        @Volatile var value: T = fallback
            private set

        // Millis of the last load ATTEMPT (success or failure). Expiry is measured from the
        // attempt, not the success, so a down upstream is retried once per ttl instead of on
        // every request. `attempted` is a separate flag — the clock is injectable, so no
        // timestamp value can double as a "never tried" sentinel.
        @Volatile private var attemptedAt: Long = 0L
        @Volatile private var attempted = false
        private val refreshing = AtomicBoolean(false)
        private val firstLoad = Mutex()

        suspend fun get(): T {
            // Dev cadence: always load inline; a failure serves the last good value.
            if (ttl == Duration.ZERO) {
                attempt()
                return value
            }
            if (!attempted) {
                // First request: load synchronously, single-flight — concurrent first readers
                // wait for one fetch instead of racing duplicates upstream.
                firstLoad.withLock { if (!attempted) attempt() }
                return value
            }
            if (now() - attemptedAt >= ttl.inWholeMilliseconds && refreshing.compareAndSet(false, true)) {
                // Stale: serve it now, refresh once in the background.
                val staleValue = value
                scope.launch {
                    try {
                        attempt()
                    } finally {
                        refreshing.set(false)
                    }
                }
                return staleValue
            }
            return value
        }

        private suspend fun attempt() {
            try {
                value = load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("bml i18n: loading {} failed; serving the previous value: {}", name, e.toString())
            } finally {
                attemptedAt = now()
                attempted = true
            }
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(BmlLocalization::class.java)

        /** Before the project is reachable: single-locale `en`, no strings — rendering degrades, never fails. */
        val FALLBACK_CONFIG = LocalizationProjectConfig(
            sourceLocale = BmlLocales.DEFAULT,
            locales = BmlLocales(),
        )
    }
}
