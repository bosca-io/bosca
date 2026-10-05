package bosca.cache

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer

/**
 * Measures [RequestCacheSerializerImpl], which runs on every remote read and write of every ServiceCache.
 *
 * The `direct*` benchmarks encode the same value with its concrete [KSerializer] and no type envelope. They are
 * the lower bound for a design where each ServiceCache holds its value serializer, and the gap between
 * `serialize`/`deserialize` and `directEncode`/`directDecode` is the cost of the self-describing wrapper.
 *
 * Payload types are pre-registered in `SerializerCache`; [RequestCacheSerializerLookupBenchmark] measures the
 * unregistered JVM case.
 */
@State(Scope.Benchmark)
open class RequestCacheSerializerBenchmark {

    @Param(CacheBenchmarkPayloads.PERMISSION_LIST, CacheBenchmarkPayloads.DOCUMENT)
    lateinit var payload: String

    private val serializer = RequestCacheSerializerImpl(CacheBenchmarkPayloads.json)
    private lateinit var value: Any
    private lateinit var encoded: String
    private lateinit var directSerializer: KSerializer<Any>
    private lateinit var directEncoded: String

    @Setup
    open fun setup() {
        CacheBenchmarkPayloads.registerSerializers()
        value = CacheBenchmarkPayloads.value(payload)
        encoded = checkNotNull(serializer.serialize(value)) { "Serializer produced no payload for $payload" }
        check(serializer.deserialize(encoded) == value) { "Round trip changed the $payload payload" }
        @Suppress("UNCHECKED_CAST")
        directSerializer = when (payload) {
            CacheBenchmarkPayloads.PERMISSION_LIST -> ListSerializer(BenchmarkPermission.serializer())
            CacheBenchmarkPayloads.DOCUMENT -> BenchmarkDocument.serializer()
            else -> error("Unknown payload kind: $payload")
        } as KSerializer<Any>
        directEncoded = CacheBenchmarkPayloads.json.encodeToString(directSerializer, value)
        check(directEncoded.length < encoded.length) { "Direct encoding should omit the envelope" }
    }

    @Benchmark
    open fun serialize(): String? = serializer.serialize(value)

    @Benchmark
    open fun deserialize(): Any? = serializer.deserialize(encoded)

    @Benchmark
    open fun directEncode(): String = CacheBenchmarkPayloads.json.encodeToString(directSerializer, value)

    @Benchmark
    open fun directDecode(): Any = CacheBenchmarkPayloads.json.decodeFromString(directSerializer, directEncoded)
}
