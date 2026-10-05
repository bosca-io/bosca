package bosca.scripting.engine

import bosca.scripting.context.BoscaScriptContext
import bosca.scripting.context.ScriptContext
import bosca.scripting.repository.CompiledScriptEntry
import bosca.scripting.repository.CompiledScriptRepository
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.Executors
import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.constructorArgs
import kotlin.script.experimental.api.valueOrThrow
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.time.Duration.Companion.seconds

class BoscaCompiledScriptImpl<T>
internal constructor(
    private val executionTimeoutSeconds: Long,
    private val compiled: CompiledScript,
    private val host: BasicJvmScriptingHost,
    private val classLoader: ClassLoader
) : BoscaCompiledScript<T> {

    override suspend fun execute(context: ScriptContext): T? {
        return withTimeout(executionTimeoutSeconds.seconds) {
            val evaluationConfiguration = ScriptEvaluationConfiguration {
                jvm {
                    baseClassLoader(classLoader)
                }
                constructorArgs(context)
            }
            val result = host.evaluator(compiled, evaluationConfiguration)
            val returnValue = result.value()
            val finalResult = if (returnValue is Deferred<*>) {
                returnValue.await()
            } else {
                returnValue
            }
            @Suppress("UNCHECKED_CAST")
            finalResult as? T
        }
    }

    private fun ResultWithDiagnostics<EvaluationResult>.value(): Any? {
        return when (this) {
            is ResultWithDiagnostics.Success -> {
                when (val returnValue = value.returnValue) {
                    is ResultValue.Value -> returnValue.value
                    is ResultValue.Unit -> null
                    is ResultValue.Error -> throw returnValue.error
                    is ResultValue.NotEvaluated -> null
                }
            }

            is ResultWithDiagnostics.Failure -> {
                val messages = reports.joinToString("\n") { it.message }
                error("Script execution failed: $messages")
            }
        }
    }
}

class KtsEngine(
    config: ScriptingSecurityConfiguration = ScriptingSecurityConfiguration(),
    private val validator: ScriptSourceValidator = ScriptSourceValidatorImpl(),
    private val persistentCache: CompiledScriptRepository? = null
) : Engine {

    private val log = LoggerFactory.getLogger(KtsEngine::class.java)
    private val compilationDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val host = BasicJvmScriptingHost()
    private val classLoader = RestrictedClassLoader(
        BoscaScriptContext::class.java.classLoader,
        config.allowedPrefixes
    )

    private val compileMutex = Mutex()
    private val executionTimeoutSeconds: Long = config.executionTimeoutSeconds
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val compiledCache = Caffeine.newBuilder()
        .maximumSize(500)
        .expireAfterAccess(Duration.ofHours(1))
        .build<String, BoscaCompiledScript<*>>()

    override suspend fun validate(script: String) {
        validator.validate(script)
    }

    override suspend fun <T> compile(key: String, version: Int, source: String): BoscaCompiledScript<T> {
        validator.validate(source)
        val cacheKey = cacheKey(key, version)
        compiledCache.getIfPresent(cacheKey)?.let {
            @Suppress("UNCHECKED_CAST")
            return it as BoscaCompiledScript<T>
        }
        return compileMutex.withLock {
            compiledCache.getIfPresent(cacheKey)?.let {
                @Suppress("UNCHECKED_CAST")
                return@withLock it as BoscaCompiledScript<T>
            }
            loadFromPersistentCache<T>(key, version)?.let { restored ->
                compiledCache.put(cacheKey, restored)
                return@withLock restored
            }
            log.info("Compiling script: {} v{}", key, version)
            val compiledScript = withContext(compilationDispatcher) {
                val scriptSource = source.toScriptSource("$key.bosca.kts")
                val result = host.compiler(scriptSource, BoscaScriptCompilationConfiguration)
                result.valueOrThrow()
            }
            log.info("Script compiled: {} v{}", key, version)
            val compiled = BoscaCompiledScriptImpl<T>(executionTimeoutSeconds, compiledScript, host, classLoader)
            compiledCache.put(cacheKey, compiled)
            saveToPersistentCache(key, version, compiledScript)
            compiled
        }
    }

    override fun invalidate(key: String, version: Int) {
        compiledCache.invalidate(cacheKey(key, version))
        val repo = persistentCache ?: return
        // Fire-and-forget: the cache is shared across nodes and the in-memory entry is already
        // gone, so DB cleanup completing later is acceptable. Blocking the suspend caller via
        // runBlocking on a restricted dispatcher could starve the DB pool.
        persistenceScope.launch {
            runCatching { repo.deleteByKeyAndVersion(key, version) }
                .onFailure { log.warn("Failed to evict persistent compiled script {} v{}", key, version, it) }
        }
    }

    override fun invalidateAll() {
        compiledCache.invalidateAll()
        val repo = persistentCache ?: return
        persistenceScope.launch {
            runCatching { repo.deleteAll() }
                .onFailure { log.warn("Failed to clear persistent compiled scripts", it) }
        }
    }

    fun close() {
        compiledCache.invalidateAll()
        persistenceScope.cancel()
        compilationDispatcher.close()
    }

    private fun cacheKey(key: String, version: Int): String = "$key:$version:$COMPILER_FINGERPRINT"

    private suspend fun <T> loadFromPersistentCache(key: String, version: Int): BoscaCompiledScript<T>? {
        val repo = persistentCache ?: return null
        val script = runCatching { repo.get(key, version, COMPILER_FINGERPRINT) }
            .onFailure { log.warn("Failed to read persistent compiled script {} v{}", key, version, it) }
            .getOrNull() ?: return null
        return try {
            val compiledScript = ByteArrayInputStream(script.compiled).use { bais ->
                ObjectInputStream(bais).use { ois -> ois.readObject() as CompiledScript }
            }
            log.info("Loaded compiled script from persistent cache: {} v{}", key, version)
            BoscaCompiledScriptImpl(executionTimeoutSeconds, compiledScript, host, classLoader)
        } catch (e: Exception) {
            log.warn("Discarding unreadable persistent compiled script {} v{}: {}", key, version, e.message)
            runCatching { repo.deleteByKeyAndVersion(key, version) }
            null
        }
    }

    private suspend fun saveToPersistentCache(key: String, version: Int, compiled: CompiledScript) {
        val repo = persistentCache ?: return
        val bytes = try {
            ByteArrayOutputStream().use { baos ->
                ObjectOutputStream(baos).use { oos -> oos.writeObject(compiled) }
                baos.toByteArray()
            }
        } catch (e: Exception) {
            log.warn("Failed to serialize compiled script {} v{}: {}", key, version, e.message)
            return
        }
        runCatching { repo.put(CompiledScriptEntry(key, version, COMPILER_FINGERPRINT, bytes)) }
            .onFailure { log.warn("Failed to persist compiled script {} v{}", key, version, it) }
    }

    companion object {
        // Bump COMPILER_VERSION whenever BoscaScriptCompilationConfiguration or any
        // serialization-affecting compiler input changes — entries written by older
        // engines will be ignored rather than deserialized into incompatible state.
        private const val COMPILER_VERSION = "1"
        private val COMPILER_FINGERPRINT: String by lazy {
            val raw = listOf(
                "v=$COMPILER_VERSION",
                "kotlin=${KotlinVersion.CURRENT}",
                "jvmTarget=25"
            ).joinToString("|")
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            digest.joinToString("") { "%02x".format(it) }.take(16)
        }
    }
}
