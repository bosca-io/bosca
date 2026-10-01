package bosca.cache

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State

/**
 * Measures what an empty `SerializerCache` costs [RequestCacheSerializerImpl.deserialize].
 *
 * `registered = true` matches a native image, where `BoscaFeature` fills `SerializerCache` at build time.
 * `registered = false` matches a JVM server, where it is empty, so each decode runs `Class.forName` and a reflective
 * `serializer()` lookup. JMH runs each parameter value in its own fork, so the global registration cannot leak.
 */
@State(Scope.Benchmark)
open class RequestCacheSerializerLookupBenchmark {

    @Param("true", "false")
    lateinit var registered: String

    private val serializer = RequestCacheSerializerImpl(CacheBenchmarkPayloads.json)
    private lateinit var encoded: String

    @Setup
    open fun setup() {
        if (registered.toBoolean()) CacheBenchmarkPayloads.registerSerializers()
        encoded = checkNotNull(serializer.serialize(CacheBenchmarkPayloads.permissionList()))
    }

    @Benchmark
    open fun deserialize(): Any? = serializer.deserialize(encoded)
}
