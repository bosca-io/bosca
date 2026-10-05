package bosca.ksp

import org.junit.Test
import org.junit.Assert.assertEquals

/**
 * Validates that the [Types] object constants have the correct fully qualified names.
 * These constants are used throughout the KSP code generators to reference framework
 * types when emitting Kotlin source, so any mismatch would cause compilation failures
 * in generated code.
 */
class TypesTest {

    /** Verifies the bosca.serialization UUID type reference. */
    @Test
    fun `UUID has correct canonical name`() {
        assertEquals("bosca.serialization", Types.UUID.packageName)
        assertEquals("UUID", Types.UUID.simpleName)
        assertEquals("bosca.serialization.UUID", Types.UUID.canonicalName)
    }

    /** Verifies the DI ProviderRegistry type reference. */
    @Test
    fun `ProviderRegistry has correct canonical name`() {
        assertEquals("bosca.di", Types.ProviderRegistry.packageName)
        assertEquals("ProviderRegistry", Types.ProviderRegistry.simpleName)
        assertEquals("bosca.di.ProviderRegistry", Types.ProviderRegistry.canonicalName)
    }

    /** Verifies the DI ProviderRegistrar type reference. */
    @Test
    fun `ProviderRegistrar has correct canonical name`() {
        assertEquals("bosca.di", Types.ProviderRegistrar.packageName)
        assertEquals("ProviderRegistrar", Types.ProviderRegistrar.simpleName)
        assertEquals("bosca.di.ProviderRegistrar", Types.ProviderRegistrar.canonicalName)
    }

    /** Verifies the kotlinx serialization JsonElement type reference. */
    @Test
    fun `JsonElement has correct canonical name`() {
        assertEquals("kotlinx.serialization.json", Types.JsonElement.packageName)
        assertEquals("JsonElement", Types.JsonElement.simpleName)
        assertEquals("kotlinx.serialization.json.JsonElement", Types.JsonElement.canonicalName)
    }

    /** Verifies the DI ObjectProvider type reference. */
    @Test
    fun `ObjectProvider has correct canonical name`() {
        assertEquals("bosca.di", Types.ObjectProvider.packageName)
        assertEquals("ObjectProvider", Types.ObjectProvider.simpleName)
        assertEquals("bosca.di.ObjectProvider", Types.ObjectProvider.canonicalName)
    }

    /** Verifies the PubSubService type reference. */
    @Test
    fun `PubSubService has correct canonical name`() {
        assertEquals("bosca.pubsub", Types.PubSubService.packageName)
        assertEquals("PubSubService", Types.PubSubService.simpleName)
        assertEquals("bosca.pubsub.PubSubService", Types.PubSubService.canonicalName)
    }

    /** Verifies the GraphQL Batch type reference. */
    @Test
    fun `Batch has correct canonical name`() {
        assertEquals("bosca.graphql", Types.Batch.packageName)
        assertEquals("Batch", Types.Batch.simpleName)
        assertEquals("bosca.graphql.Batch", Types.Batch.canonicalName)
    }

    /** Verifies the GraphQL BatchKey type reference. */
    @Test
    fun `BatchKey has correct canonical name`() {
        assertEquals("bosca.graphql", Types.BatchKey.packageName)
        assertEquals("BatchKey", Types.BatchKey.simpleName)
        assertEquals("bosca.graphql.BatchKey", Types.BatchKey.canonicalName)
    }

    /** Verifies the GraphQL PropertyDataFetcher type reference. */
    @Test
    fun `PropertyDataFetcher has correct canonical name`() {
        assertEquals("bosca.graphql", Types.PropertyDataFetcher.packageName)
        assertEquals("PropertyDataFetcher", Types.PropertyDataFetcher.simpleName)
        assertEquals("bosca.graphql.PropertyDataFetcher", Types.PropertyDataFetcher.canonicalName)
    }

    /** Verifies the GraphQL SuspendDataFetcher type reference. */
    @Test
    fun `SuspendDataFetcher has correct canonical name`() {
        assertEquals("bosca.graphql", Types.SuspendDataFetcher.packageName)
        assertEquals("SuspendDataFetcher", Types.SuspendDataFetcher.simpleName)
        assertEquals("bosca.graphql.SuspendDataFetcher", Types.SuspendDataFetcher.canonicalName)
    }

    /** Verifies the GraphQL FlowDataFetcher type reference. */
    @Test
    fun `FlowDataFetcher has correct canonical name`() {
        assertEquals("bosca.graphql", Types.FlowDataFetcher.packageName)
        assertEquals("FlowDataFetcher", Types.FlowDataFetcher.simpleName)
        assertEquals("bosca.graphql.FlowDataFetcher", Types.FlowDataFetcher.canonicalName)
    }

    /** Verifies the kotlinx ListSerializer type reference. */
    @Test
    fun `ListSerializer has correct canonical name`() {
        assertEquals("kotlinx.serialization.builtins", Types.ListSerializer.packageName)
        assertEquals("ListSerializer", Types.ListSerializer.simpleName)
        assertEquals("kotlinx.serialization.builtins.ListSerializer", Types.ListSerializer.canonicalName)
    }

    /** Verifies the OpenTelemetry Tracer type reference. */
    @Test
    fun `Tracer has correct canonical name`() {
        assertEquals("io.opentelemetry.api.trace", Types.Tracer.packageName)
        assertEquals("Tracer", Types.Tracer.simpleName)
        assertEquals("io.opentelemetry.api.trace.Tracer", Types.Tracer.canonicalName)
    }

    /** Verifies the GraphQL Dispatcher type reference. */
    @Test
    fun `Dispatcher has correct canonical name`() {
        assertEquals("bosca.graphql.dispatcher", Types.Dispatcher.packageName)
        assertEquals("Dispatcher", Types.Dispatcher.simpleName)
        assertEquals("bosca.graphql.dispatcher.Dispatcher", Types.Dispatcher.canonicalName)
    }

    /** Verifies the kotlinx Json type reference. */
    @Test
    fun `Json has correct canonical name`() {
        assertEquals("kotlinx.serialization.json", Types.Json.packageName)
        assertEquals("Json", Types.Json.simpleName)
        assertEquals("kotlinx.serialization.json.Json", Types.Json.canonicalName)
    }

    /** Verifies the security AuthenticationContext type reference. */
    @Test
    fun `AuthenticationContext has correct canonical name`() {
        assertEquals("bosca.security.service", Types.AuthenticationContext.packageName)
        assertEquals("AuthenticationContext", Types.AuthenticationContext.simpleName)
        assertEquals("bosca.security.service.AuthenticationContext", Types.AuthenticationContext.canonicalName)
    }

    /** Verifies the native BatchLoaderEnvironment type reference. */
    @Test
    fun `BatchLoaderEnvironment has correct canonical name`() {
        assertEquals("bosca.graphql", Types.BatchLoaderEnvironment.packageName)
        assertEquals("BatchLoaderEnvironment", Types.BatchLoaderEnvironment.simpleName)
        assertEquals("bosca.graphql.BatchLoaderEnvironment", Types.BatchLoaderEnvironment.canonicalName)
    }

    /** Verifies the GraphQL DispatchersRegistry type reference. */
    @Test
    fun `DispatchersRegistry has correct canonical name`() {
        assertEquals("bosca.graphql.dispatcher", Types.DispatchersRegistry.packageName)
        assertEquals("DispatchersRegistry", Types.DispatchersRegistry.simpleName)
        assertEquals("bosca.graphql.dispatcher.DispatchersRegistry", Types.DispatchersRegistry.canonicalName)
    }

    /** Verifies the GraphQL CompositeDispatcher type reference. */
    @Test
    fun `CompositeDispatcher has correct canonical name`() {
        assertEquals("bosca.graphql.dispatcher", Types.CompositeDispatcher.packageName)
        assertEquals("CompositeDispatcher", Types.CompositeDispatcher.simpleName)
        assertEquals("bosca.graphql.dispatcher.CompositeDispatcher", Types.CompositeDispatcher.canonicalName)
    }

    /** Verifies the GraphQL DispatchersRegistrar type reference. */
    @Test
    fun `DispatchersRegistrar has correct canonical name`() {
        assertEquals("bosca.graphql.dispatcher", Types.DispatchersRegistrar.packageName)
        assertEquals("DispatchersRegistrar", Types.DispatchersRegistrar.simpleName)
        assertEquals("bosca.graphql.dispatcher.DispatchersRegistrar", Types.DispatchersRegistrar.canonicalName)
    }

    /** Verifies the native RuntimeWiring type reference. */
    @Test
    fun `RuntimeWiring has correct canonical name`() {
        assertEquals("bosca.graphql.server", Types.RuntimeWiring.packageName)
        assertEquals("RuntimeWiring", Types.RuntimeWiring.simpleName)
        assertEquals("bosca.graphql.server.RuntimeWiring", Types.RuntimeWiring.canonicalName)
    }

    /** Verifies the GraphQL SchemaRegistrar type reference. */
    @Test
    fun `SchemaRegistrar has correct canonical name`() {
        assertEquals("bosca.graphql", Types.SchemaRegistrar.packageName)
        assertEquals("SchemaRegistrar", Types.SchemaRegistrar.simpleName)
        assertEquals("bosca.graphql.SchemaRegistrar", Types.SchemaRegistrar.canonicalName)
    }

    /** Verifies the GraphQL GraphQLController type reference. */
    @Test
    fun `GraphQLController has correct canonical name`() {
        assertEquals("bosca.graphql", Types.GraphQLController.packageName)
        assertEquals("GraphQLController", Types.GraphQLController.simpleName)
        assertEquals("bosca.graphql.GraphQLController", Types.GraphQLController.canonicalName)
    }

    /** Verifies the DB DefaultMappers type reference. */
    @Test
    fun `DefaultMappers has correct canonical name`() {
        assertEquals("bosca.db.mapper", Types.DefaultMappers.packageName)
        assertEquals("DefaultMappers", Types.DefaultMappers.simpleName)
        assertEquals("bosca.db.mapper.DefaultMappers", Types.DefaultMappers.canonicalName)
    }

    /** Verifies the DB SerializableMapper type reference. */
    @Test
    fun `SerializableMapper has correct canonical name`() {
        assertEquals("bosca.db.mapper", Types.SerializableMapper.packageName)
        assertEquals("SerializableMapper", Types.SerializableMapper.simpleName)
        assertEquals("bosca.db.mapper.SerializableMapper", Types.SerializableMapper.canonicalName)
    }

    /** Verifies the ServerCall type reference used for GraphQL controller call injection. */
    @Test
    fun `ServerCall has correct canonical name`() {
        assertEquals("bosca.server", Types.ServerCall.packageName)
        assertEquals("ServerCall", Types.ServerCall.simpleName)
        assertEquals("bosca.server.ServerCall", Types.ServerCall.canonicalName)
    }

    /** Verifies the job system Job type reference. */
    @Test
    fun `Job has correct canonical name`() {
        assertEquals("bosca.sharedqueue.jobs", Types.Job.packageName)
        assertEquals("Job", Types.Job.simpleName)
        assertEquals("bosca.sharedqueue.jobs.Job", Types.Job.canonicalName)
    }

    /** Verifies the job system JobConfigurationEnqueuer type reference. */
    @Test
    fun `JobConfigurationEnqueuer has correct canonical name`() {
        assertEquals("bosca.sharedqueue.jobs", Types.JobConfigurationEnqueuer.packageName)
        assertEquals("JobConfigurationEnqueuer", Types.JobConfigurationEnqueuer.simpleName)
        assertEquals("bosca.sharedqueue.jobs.JobConfigurationEnqueuer", Types.JobConfigurationEnqueuer.canonicalName)
    }

    /** Verifies the job system JobQueue type reference. */
    @Test
    fun `JobQueue has correct canonical name`() {
        assertEquals("bosca.sharedqueue.jobs", Types.JobQueue.packageName)
        assertEquals("JobQueue", Types.JobQueue.simpleName)
        assertEquals("bosca.sharedqueue.jobs.JobQueue", Types.JobQueue.canonicalName)
    }

    /** Verifies the scheduler SchedulableJobRegistrar type reference. */
    @Test
    fun `SchedulableJobRegistrar has correct canonical name`() {
        assertEquals("bosca.scheduler", Types.SchedulableJobRegistrar.packageName)
        assertEquals("SchedulableJobRegistrar", Types.SchedulableJobRegistrar.simpleName)
        assertEquals("bosca.scheduler.SchedulableJobRegistrar", Types.SchedulableJobRegistrar.canonicalName)
    }

    /** Verifies the scheduler SchedulableJob type reference. */
    @Test
    fun `SchedulableJob has correct canonical name`() {
        assertEquals("bosca.scheduler", Types.SchedulableJob.packageName)
        assertEquals("SchedulableJob", Types.SchedulableJob.simpleName)
        assertEquals("bosca.scheduler.SchedulableJob", Types.SchedulableJob.canonicalName)
    }

    /** Verifies the pipelines PipelineEventDispatcher type reference. */
    @Test
    fun `PipelineEventDispatcher has correct canonical name`() {
        assertEquals("bosca.pipelines", Types.PipelineEventDispatcher.packageName)
        assertEquals("PipelineEventDispatcher", Types.PipelineEventDispatcher.simpleName)
        assertEquals("bosca.pipelines.PipelineEventDispatcher", Types.PipelineEventDispatcher.canonicalName)
    }

    /** Verifies the native RuntimeWiringBuilder type reference. */
    @Test
    fun `RuntimeWiringBuilder has correct package and simple name`() {
        assertEquals("bosca.graphql.server", Types.RuntimeWiringBuilder.packageName)
        assertEquals("RuntimeWiringBuilder", Types.RuntimeWiringBuilder.simpleName)
        assertEquals("bosca.graphql.server.RuntimeWiringBuilder", Types.RuntimeWiringBuilder.canonicalName)
    }
}
