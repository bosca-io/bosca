package bosca.server

import bosca.content.collection.model.CollectionMetadataRelationship
import bosca.content.metadata.model.MetadataRelationship
import bosca.content.model.ContentRelationship
import bosca.di.provideBlockingNoSuspend
import bosca.di.provides
import bosca.observability.ErrorCapture
import bosca.serialization.JsonElementSerializer
import bosca.serialization.LocalDateTimeSerializer
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUIDSerializer
import bosca.serialization.ZonedDateTimeSerializer
import bosca.server.config.ApplicationConfig
import bosca.server.middleware.AuthMiddleware
import bosca.server.middleware.CallMiddleware
import bosca.server.middleware.HandlerMiddleware
import bosca.server.routing.Router
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlinx.serialization.modules.plus
import kotlinx.serialization.protobuf.ProtoBuf
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

/**
 * The central application object that holds configuration, routing, and server lifecycle state.
 *
 * Replaces Ktor's Application class with a lightweight container that manages the [Router],
 * [ApplicationConfig], and provides access to logging and development mode settings.
 * All server modules register their routes and middleware through this object.
 *
 * Implements [CoroutineScope] with a [SupervisorJob] so that background tasks launched
 * during server startup (job runners, schedulers) run in the application's lifecycle
 * without blocking module initialization.
 */
@OptIn(ExperimentalSerializationApi::class)
class BoscaApplication(
    val config: ApplicationConfig
) : CoroutineScope {

    private val boscaSerializersModules = SerializersModule {
        contextual(UUIDSerializer())
        contextual(OffsetDateTimeSerializer())
        contextual(LocalDateTimeSerializer())
        contextual(ZonedDateTimeSerializer())

        polymorphic(ContentRelationship::class, MetadataRelationship::class, MetadataRelationship.serializer())
        polymorphic(ContentRelationship::class, CollectionMetadataRelationship::class, CollectionMetadataRelationship.serializer())
    }

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false
        encodeDefaults = false
        explicitNulls = false
        serializersModule = boscaSerializersModules
    }

    @OptIn(ExperimentalSerializationApi::class)
    val protobuf: ProtoBuf = ProtoBuf {
        encodeDefaults = false
        serializersModule = boscaSerializersModules + SerializersModule {
            contextual(JsonElementSerializer())
        }
    }

    init {
        provides(singleton = true) { this }
        provides(singleton = true) { json }
        provides(singleton = true) { protobuf }
    }

    private val job = SupervisorJob()

    override val coroutineContext: CoroutineContext get() = job + Dispatchers.Default

    /** The application-wide logger. */
    val log: Logger = LoggerFactory.getLogger("bosca.server.Application")

    val errorCapture: ErrorCapture by lazy {
        try {
            provideBlockingNoSuspend<ErrorCapture>()
        } catch (e: Exception) {
            log.error("ErrorCapture DI resolution failed, falling back to Noop — errors will only be logged, not tracked", e)
            ErrorCapture.Noop
        }
    }

    /** Whether the server is running in development mode with extra diagnostics. */
    val developmentMode: Boolean = config.propertyOrNull("bosca.server.development")
        ?.getString()?.toBooleanStrictOrNull() ?: false

    /** The router that manages all HTTP route registrations and dispatching. */
    val router: Router = Router()

    private val _middleware: MutableList<CallMiddleware> = java.util.concurrent.CopyOnWriteArrayList()
    private val _authMiddleware: MutableList<AuthMiddleware> = java.util.concurrent.CopyOnWriteArrayList()
    private val _handlerMiddleware: MutableList<HandlerMiddleware> = java.util.concurrent.CopyOnWriteArrayList()

    /** Registered wrappers applied only to route handler execution. */
    var handlerMiddleware: List<HandlerMiddleware> = _handlerMiddleware
        private set

    /** Registered HTTP middleware executed in order for every request. */
    var middleware: List<CallMiddleware> = _middleware
        private set

    /** Registered authentication middleware for validating credentials. */
    var authMiddleware: List<AuthMiddleware> = _authMiddleware
        private set

    private val _shutdownHooks = java.util.concurrent.CopyOnWriteArrayList<suspend () -> Unit>()

    private val _modules = java.util.concurrent.ConcurrentHashMap<KClass<out BoscaApplicationModule>, BoscaApplicationModule>()

    /** Installed application modules, keyed by their concrete class to prevent duplicates. */
    val modules: Map<KClass<out BoscaApplicationModule>, BoscaApplicationModule> get() = _modules

    /**
     * Installs a [BoscaApplicationModule], delegating to its [BoscaApplicationModule.install]
     * method. Each module type can only be installed once; duplicate installs are skipped
     * with a warning.
     */
    suspend fun install(module: BoscaApplicationModule) {
        val type = module::class
        if (_modules.putIfAbsent(type, module) != null) {
            log.warn("Module ${type.simpleName} is already installed, skipping")
            return
        }
        module.install(this)
    }

    /** Provides a DSL block for registering routes on the application's router. */
    fun routing(block: Router.() -> Unit) {
        router.block()
    }

    /** Registers a middleware instance to be executed for all requests. */
    fun install(middleware: CallMiddleware) {
        _middleware.add(middleware)
        if (middleware is HandlerMiddleware) installHandler(middleware)
    }

    /** Registers a handler wrapper. Call middleware implementing both interfaces is registered by [install]. */
    fun installHandler(middleware: HandlerMiddleware) {
        _handlerMiddleware.add(middleware)
    }

    /** Executes a route handler through the registered handler wrappers after authentication. */
    suspend fun onHandler(call: ServerCall, handler: suspend () -> Unit) {
        val wrappers = handlerMiddleware
        suspend fun next(index: Int) {
            if (index == wrappers.size) handler()
            else wrappers[index].onHandler(call) { next(index + 1) }
        }
        next(0)
    }

    /** Registers an authentication middleware instance. */
    fun installAuth(authMiddleware: AuthMiddleware) {
        _authMiddleware.add(authMiddleware)
    }

    /**
     * Freezes the middleware lists by converting them to immutable snapshots.
     * Call this after all modules have been installed and before serving requests,
     * so that concurrent request handlers read from a stable, immutable list.
     */
    fun freezeMiddleware() {
        middleware = _middleware.toList()
        authMiddleware = _authMiddleware.toList()
        handlerMiddleware = _handlerMiddleware.toList()
    }

    /**
     * Registers a suspend function to run during [shutdown], used for closing connection pools,
     * cache clients, and other resources that require cleanup on server stop.
     * Hooks run in reverse registration order (LIFO) inside a [NonCancellable] context.
     */
    fun onShutdown(hook: suspend () -> Unit) {
        _shutdownHooks.add(hook)
    }

    /**
     * Shuts down the application by first gracefully stopping all
     * registered [bosca.service.Service] instances (draining channels,
     * closing background jobs), then running registered cleanup hooks
     * in reverse order, and finally cancelling the coroutine scope.
     */
    @OptIn(bosca.di.annotation.InternalDI::class)
    suspend fun shutdown() {
        // Drain services that hold background resources (channels,
        // coroutine jobs, etc.) before tearing down infrastructure.
        for (provider in bosca.di.ProviderRegistry.findAll(bosca.service.Service::class)) {
            withContext(NonCancellable) {
                try {
                    provider.get().shutdown()
                } catch (e: Exception) {
                    log.error("Service shutdown error", e)
                }
            }
        }
        for (hook in _shutdownHooks.reversed()) {
            withContext(NonCancellable) {
                try {
                    hook()
                } catch (e: Exception) {
                    log.error("Shutdown hook error", e)
                }
            }
        }
        job.cancel()
    }

    /**
     * The application environment providing access to configuration.
     * Provides compatibility with code that accesses `application.environment.config`.
     */
    val environment = Environment(config)

    /**
     * Holds the application configuration, providing a familiar access pattern
     * for code migrated from Ktor's environment.config style.
     */
    class Environment(val config: ApplicationConfig)
}
