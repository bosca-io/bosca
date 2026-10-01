package bosca.ksp

import com.squareup.kotlinpoet.ClassName

object Types {
    val UUID = ClassName("bosca.serialization", "UUID")
    val ProviderRegistry = ClassName("bosca.di", "ProviderRegistry")
    val ProviderRegistrar = ClassName("bosca.di", "ProviderRegistrar")
    val JsonElement = ClassName("kotlinx.serialization.json", "JsonElement")
    val ObjectProvider = ClassName("bosca.di", "ObjectProvider")
    val PubSubService = ClassName("bosca.pubsub", "PubSubService")
    val Batch = ClassName("bosca.graphql", "Batch")
    val BatchKey = ClassName("bosca.graphql", "BatchKey")
    val PropertyDataFetcher = ClassName("bosca.graphql", "PropertyDataFetcher")
    val SuspendDataFetcher = ClassName("bosca.graphql", "SuspendDataFetcher")
    val FlowDataFetcher = ClassName("bosca.graphql", "FlowDataFetcher")
    val ListSerializer = ClassName("kotlinx.serialization.builtins", "ListSerializer")
    val Tracer = ClassName("io.opentelemetry.api.trace", "Tracer")
    val Dispatcher = ClassName("bosca.graphql.dispatcher", "Dispatcher")
    val Json = ClassName("kotlinx.serialization.json", "Json")
    val AuthenticationContext = ClassName("bosca.security.service", "AuthenticationContext")
    val DispatchersRegistry = ClassName("bosca.graphql.dispatcher", "DispatchersRegistry")
    val DispatchersRegistrar = ClassName("bosca.graphql.dispatcher", "DispatchersRegistrar")
    val RuntimeWiring = ClassName("bosca.graphql.server", "RuntimeWiring")
    val RuntimeWiringBuilder = ClassName("bosca.graphql.server", "RuntimeWiringBuilder")
    val SchemaRegistrar = ClassName("bosca.graphql", "SchemaRegistrar")
    val GraphQLController = ClassName("bosca.graphql", "GraphQLController")
    val DefaultMappers = ClassName("bosca.db.mapper", "DefaultMappers")
    val ServerCall = ClassName("bosca.server", "ServerCall")
    val Job = ClassName("bosca.sharedqueue.jobs", "Job")
    val JobConfigurationEnqueuer = ClassName("bosca.sharedqueue.jobs", "JobConfigurationEnqueuer")
    val JobQueue = ClassName("bosca.sharedqueue.jobs", "JobQueue")
}
