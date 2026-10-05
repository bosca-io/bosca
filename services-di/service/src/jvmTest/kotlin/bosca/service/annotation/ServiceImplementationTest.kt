package bosca.service.annotation

import bosca.service.Service
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Validates the [ServiceImplementation] annotation metadata and the [Service]
 * marker interface contract. Ensures the annotation retains its presence at
 * runtime for KSP and reflection-based discovery, targets only classes, and
 * that concrete classes can combine both the annotation and the marker interface
 * as expected by the framework's DI registration mechanism.
 */
class ServiceImplementationTest {

    @ServiceImplementation
    private class AnnotatedService : Service

    private class PlainService : Service

    // --- ServiceImplementation annotation ---

    @Test
    fun `ServiceImplementation annotation is present at runtime`() {
        val annotation = AnnotatedService::class.annotations
            .filterIsInstance<ServiceImplementation>()
            .firstOrNull()
        assertNotNull(annotation, "ServiceImplementation annotation should be retained at runtime")
    }

    @Test
    fun `ServiceImplementation annotation targets CLASS`() {
        val targets = ServiceImplementation::class.annotations
            .filterIsInstance<Target>()
            .firstOrNull()
        assertNotNull(targets, "ServiceImplementation should have a @Target annotation")
        assertTrue(
            AnnotationTarget.CLASS in targets.allowedTargets,
            "ServiceImplementation should target CLASS"
        )
    }

    @Test
    fun `ServiceImplementation annotation has RUNTIME retention`() {
        val retention = ServiceImplementation::class.annotations
            .filterIsInstance<Retention>()
            .firstOrNull()
        assertNotNull(retention, "ServiceImplementation should have a @Retention annotation")
        assertTrue(
            retention.value == AnnotationRetention.RUNTIME,
            "ServiceImplementation retention should be RUNTIME"
        )
    }

    // --- Service marker interface ---

    @Test
    fun `a class implementing Service is recognized as a Service instance`() {
        val service: Service = PlainService()
        assertIs<Service>(service, "PlainService should be an instance of Service")
    }

    @Test
    fun `an annotated class implementing Service is recognized as a Service instance`() {
        val service: Service = AnnotatedService()
        assertIs<Service>(service, "AnnotatedService should be an instance of Service")
    }
}
