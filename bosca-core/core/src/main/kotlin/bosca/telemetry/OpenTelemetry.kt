package bosca.telemetry

import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.extension.kotlin.asContextElement
import io.opentelemetry.extension.kotlin.getOpenTelemetryContext
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import io.opentelemetry.sdk.common.internal.AttributesMap
import io.opentelemetry.semconv.ServiceAttributes
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.reflect.KClass

@OptIn(InternalDI::class)
fun getOpenTelemetry(serviceName: String): OpenTelemetry {
    System.setProperty("otel.metrics.exporter", "none")
    val sdk = AutoConfiguredOpenTelemetrySdk
        .builder()
        .addResourceCustomizer { oldResource, _ ->
            oldResource
                .toBuilder()
                .putAll(oldResource.attributes)
                .put(ServiceAttributes.SERVICE_NAME, serviceName)
                .build()
        }.build().openTelemetrySdk
    GlobalOpenTelemetry.set(sdk)
    val tracer = sdk.getTracer(serviceName)
    ProviderRegistry.register(Tracer::class, object : ObjectProvider<Tracer> {
        override val type: KClass<Tracer> = Tracer::class
        override suspend fun get(): Tracer {
            return tracer
        }
    }, true)
    return sdk
}

suspend fun <T> Tracer.withSpan(name: String, attributes: Attributes = AttributesMap.create(0, 0), block: suspend () -> T): T {
    val context = currentCoroutineContext()
    val span = spanBuilder(name)
        .setParent(context.getOpenTelemetryContext())
        .setAllAttributes(attributes)
        .startSpan()
    return try {
        withContext(context + span.asContextElement()) {
            block()
        }
    } catch (e: Exception) {
        span.recordException(e)
        throw e
    } finally {
        span.end()
    }
}


fun <T> Tracer.withSyncSpan(name: String, block: () -> T): T {
    val span = spanBuilder(name)
        .setParent(Context.current())
        .startSpan()
    return try {
        block()
    } catch (e: Exception) {
        span.recordException(e)
        throw e
    } finally {
        span.end()
    }
}
