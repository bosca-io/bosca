package bosca.server.graphql

import bosca.ai.configuration.AISchemaRegistrar
import bosca.analytics.configuration.AnalyticsSchemaRegistrar
import bosca.backup.configuration.BackupSchemaRegistrar
import bosca.calendar.configuration.CalendarSchemaRegistrar
import bosca.chat.configuration.ChatSchemaRegistrar
import bosca.collaboration.configuration.CollaborationSchemaRegistrar
import bosca.community.configuration.CommunitySchemaRegistrar
import bosca.comments.configuration.CommentsSchemaRegistrar
import bosca.configuration.ContentSchemaRegistrar
import bosca.configuration.CoreSchemaRegistrar
import bosca.devices.graphql.DevicesSchemaRegistrar
import bosca.meilisearch.admin.graphql.MeilisearchAdminSchemaRegistrar
import bosca.nats.admin.graphql.NatsAdminSchemaRegistrar
import bosca.postgres.admin.graphql.PostgresAdminSchemaRegistrar
import bosca.artifacts.admin.graphql.ArtifactsAdminSchemaRegistrar
import bosca.configuration.configuration.ConfigurationSchemaRegistrar
import bosca.communications.configuration.CommunicationsSchemaRegistrar
import bosca.features.Features
import bosca.forms.configuration.FormsSchemaRegistrar
import bosca.localization.configuration.LocalizationSchemaRegistrar
import bosca.graphql.DispatcherRuntimeType
import bosca.graphql.GraphQLService
import bosca.graphql.SchemaRegistry
import bosca.graphql.dispatcher.AIDispatchersRegistrar
import bosca.graphql.dispatcher.AnalyticsDispatchersRegistrar
import bosca.graphql.dispatcher.BackupDispatchersRegistrar
import bosca.graphql.dispatcher.BoscaDispatchersRegistrar
import bosca.graphql.dispatcher.CalendarDispatchersRegistrar
import bosca.graphql.dispatcher.ChatDispatchersRegistrar
import bosca.graphql.dispatcher.CollaborationDispatchersRegistrar
import bosca.graphql.dispatcher.CommunityDispatchersRegistrar
import bosca.graphql.dispatcher.ConfigurationDispatchersRegistrar
import bosca.graphql.dispatcher.CommentsDispatchersRegistrar
import bosca.graphql.dispatcher.ContentDispatchersRegistrar
import bosca.graphql.dispatcher.DevicesDispatchersRegistrar
import bosca.graphql.dispatcher.MeilisearchAdminDispatchersRegistrar
import bosca.graphql.dispatcher.NatsAdminDispatchersRegistrar
import bosca.graphql.dispatcher.PostgresAdminDispatchersRegistrar
import bosca.graphql.dispatcher.ArtifactsAdminDispatchersRegistrar
import bosca.graphql.dispatcher.DispatchersRegistry
import bosca.graphql.dispatcher.CommunicationsDispatchersRegistrar
import bosca.graphql.dispatcher.FormsDispatchersRegistrar
import bosca.graphql.dispatcher.LocalizationDispatchersRegistrar
import bosca.graphql.dispatcher.LanguagesDispatchersRegistrar
import bosca.graphql.dispatcher.ProfileDispatchersRegistrar
import bosca.graphql.dispatcher.SearchDispatchersRegistrar
import bosca.graphql.dispatcher.SchedulerDispatchersRegistrar
import bosca.graphql.dispatcher.EventsDispatchersRegistrar
import bosca.graphql.dispatcher.PipelinesDispatchersRegistrar
import bosca.graphql.dispatcher.EcommerceDispatchersRegistrar
import bosca.pipelines.configuration.PipelinesSchemaRegistrar
import bosca.ecommerce.configuration.EcommerceSchemaRegistrar
import bosca.ecommerce.graphql.Ecom
import bosca.ecommerce.graphql.EcomMutation
import bosca.ecommerce.graphql.scalars.MoneyScalar
import bosca.graphql.dispatcher.SecurityDispatchersRegistrar
import bosca.graphql.dispatcher.ExperimentationDispatchersRegistrar
import bosca.graphql.dispatcher.RecommendationsDispatchersRegistrar
import bosca.graphql.dispatcher.FeedsDispatchersRegistrar
import bosca.graphql.dispatcher.SegmentationDispatchersRegistrar
import bosca.graphql.dispatcher.ScriptingDispatchersRegistrar
import bosca.graphql.dispatcher.StorageDispatchersRegistrar
import bosca.graphql.dispatcher.GatewayDispatchersRegistrar
import bosca.graphql.dispatcher.KubernetesDispatchersRegistrar
import bosca.graphql.dispatcher.WorkOpsDispatchersRegistrar
import bosca.kubernetes.configuration.KubernetesSchemaRegistrar
import bosca.languages.configuration.LanguagesSchemaRegistrar
import bosca.profile.configuration.ProfileSchemaRegistrar
import bosca.scheduler.configuration.SchedulerSchemaRegistrar
import bosca.events.configuration.EventsSchemaRegistrar
import bosca.search.configuration.SearchSchemaRegistrar
import bosca.security.configuration.SecuritySchemaRegistrar
import bosca.experimentation.configuration.ExperimentationSchemaRegistrar
import bosca.recommendations.configuration.RecommendationsSchemaRegistrar
import bosca.feeds.configuration.FeedsSchemaRegistrar
import bosca.git.configuration.GitSchemaRegistrar
import bosca.graphql.dispatcher.GitCiDispatchersRegistrar
import bosca.graphql.dispatcher.GitDispatchersRegistrar
import bosca.segmentation.configuration.SegmentationSchemaRegistrar
import bosca.server.configuration.BoscaSchemaRegistrar
import bosca.scripting.configuration.ScriptingSchemaRegistrar
import bosca.storage.configuration.StorageSchemaRegistrar
import bosca.gateway.configuration.GatewaySchemaRegistrar
import bosca.workops.configuration.WorkOpsSchemaRegistrar
import bosca.graphql.server.RuntimeWiringBuilder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class BoscaGraphQLService(introspectionEnabled: Boolean) : GraphQLService(Schema, introspectionEnabled) {

    private var schemaRegistered: Boolean = false
    private val mutex = Mutex()

    override suspend fun initialize(builder: RuntimeWiringBuilder) {
        mutex.withLock {
            if (!schemaRegistered) {
                schemaRegistered = true
                // Register GraphQL Schemas
                val registrars = mutableListOf(
                    CoreSchemaRegistrar(),
                    ProfileSchemaRegistrar(),
                    ContentSchemaRegistrar(),
                    CommentsSchemaRegistrar(),
                    SecuritySchemaRegistrar(),
                    BoscaSchemaRegistrar(),
                    SearchSchemaRegistrar(),
                    LanguagesSchemaRegistrar(),
                    AnalyticsSchemaRegistrar(),
                    ConfigurationSchemaRegistrar(),
                    StorageSchemaRegistrar(),
                    SchedulerSchemaRegistrar(),
                    EventsSchemaRegistrar(),
                    PipelinesSchemaRegistrar(),
                    AISchemaRegistrar(),
                    ScriptingSchemaRegistrar(),
                    BackupSchemaRegistrar(),
                    FormsSchemaRegistrar(),
                    LocalizationSchemaRegistrar(),
                    SegmentationSchemaRegistrar(),
                    ExperimentationSchemaRegistrar(),
                    RecommendationsSchemaRegistrar(),
                    FeedsSchemaRegistrar(),
                    DevicesSchemaRegistrar(),
                    CalendarSchemaRegistrar(),
                    MeilisearchAdminSchemaRegistrar(),
                    NatsAdminSchemaRegistrar(),
                    PostgresAdminSchemaRegistrar(),
                    ArtifactsAdminSchemaRegistrar(),
                    CommunicationsSchemaRegistrar(),
                )
                if (Features.community) {
                    registrars.add(CommunitySchemaRegistrar())
                }
                registrars.add(GitSchemaRegistrar())
                if (Features.chat) {
                    registrars.add(ChatSchemaRegistrar())
                    registrars.add(CollaborationSchemaRegistrar())
                }
                if (Features.workops) {
                    registrars.add(WorkOpsSchemaRegistrar())
                }
                if (Features.ecommerce) {
                    registrars.add(EcommerceSchemaRegistrar())
                }
                if (Features.gateway) {
                    registrars.add(GatewaySchemaRegistrar())
                }
                if (Features.kubernetes) {
                    registrars.add(KubernetesSchemaRegistrar())
                }
                SchemaRegistry.initialize(*registrars.toTypedArray())
            }
        }

        // Register the ecommerce module's Money scalar (gated with the ecom schema).
        if (Features.ecommerce) {
            builder.scalar("Money", MoneyScalar.Type)
        }

        // Register GraphQL Union Dispatchers
        builder
            .type(DispatcherRuntimeType("CollectionItem"))
            .type(DispatcherRuntimeType("ContentItem"))
            .type(DispatcherRuntimeType("ContentRelationship"))

        // Ecommerce polymorphic/union type resolvers — gated with the ecom module
        // so we don't wire resolvers for SDL types that aren't registered.
        if (Features.ecommerce) {
            builder
                .type(DispatcherRuntimeType("Rule"))
                .type(DispatcherRuntimeType("ProductConfiguration"))
                .type(DispatcherRuntimeType("CartItemConfiguration"))
                .type(DispatcherRuntimeType("CatalogProductExtras"))
                .type(DispatcherRuntimeType("ManufacturerExtras"))
                .type(DispatcherRuntimeType("AccountExtras"))
                .type(DispatcherRuntimeType("CustomerExtras"))
                .type(DispatcherRuntimeType("ProviderConfiguration"))
                .type(DispatcherRuntimeType("PlanConfiguration"))
        }

        // Register GraphQL Dispatchers
        val dispatchers = mutableListOf(
            BoscaDispatchersRegistrar(),
            ContentDispatchersRegistrar(),
            CommentsDispatchersRegistrar(),
            SecurityDispatchersRegistrar(),
            ProfileDispatchersRegistrar(),
            SearchDispatchersRegistrar(),
            LanguagesDispatchersRegistrar(),
            AnalyticsDispatchersRegistrar(),
            ConfigurationDispatchersRegistrar(),
            StorageDispatchersRegistrar(),
            SchedulerDispatchersRegistrar(),
            EventsDispatchersRegistrar(),
            PipelinesDispatchersRegistrar(),
            AIDispatchersRegistrar(),
            ScriptingDispatchersRegistrar(),
            BackupDispatchersRegistrar(),
            FormsDispatchersRegistrar(),
            LocalizationDispatchersRegistrar(),
            SegmentationDispatchersRegistrar(),
            ExperimentationDispatchersRegistrar(),
            RecommendationsDispatchersRegistrar(),
            FeedsDispatchersRegistrar(),
            DevicesDispatchersRegistrar(),
            CalendarDispatchersRegistrar(),
            MeilisearchAdminDispatchersRegistrar(),
            NatsAdminDispatchersRegistrar(),
            PostgresAdminDispatchersRegistrar(),
            ArtifactsAdminDispatchersRegistrar(),
            CommunicationsDispatchersRegistrar(),
        )
        if (Features.community) {
            dispatchers.add(CommunityDispatchersRegistrar())
        }
        if (Features.chat) {
            dispatchers.add(ChatDispatchersRegistrar())
            dispatchers.add(CollaborationDispatchersRegistrar())
        }
        dispatchers.add(GitDispatchersRegistrar())
        dispatchers.add(GitCiDispatchersRegistrar())
        if (Features.workops) {
            dispatchers.add(WorkOpsDispatchersRegistrar())
        }
        if (Features.ecommerce) {
            dispatchers.add(EcommerceDispatchersRegistrar())
        }
        builder.registerEcommerceRootFields(Features.ecommerce)
        if (Features.gateway) {
            dispatchers.add(GatewayDispatchersRegistrar())
        }
        if (Features.kubernetes) {
            dispatchers.add(KubernetesDispatchersRegistrar())
            builder
                .type("Query") {
                    field("kubernetes") { bosca.kubernetes.controller.KubernetesQueries }
                }
                .type("Mutation") {
                    field("kubernetes") { bosca.kubernetes.controller.KubernetesMutations }
                }
        }
        DispatchersRegistry.register(builder, *dispatchers.toTypedArray())
    }
}

/**
 * Registers the ecommerce namespace roots only when its SDL and domain dispatchers are present.
 *
 * These fields cannot live on the always-loaded server Query/Mutation controllers: their generated
 * dispatcher would otherwise try to wire `ecom` when the feature-gated schema is absent.
 */
internal fun RuntimeWiringBuilder.registerEcommerceRootFields(enabled: Boolean) {
    if (!enabled) return

    type("Query") {
        field("ecom") { Ecom }
    }
    type("Mutation") {
        field("ecom") { EcomMutation }
    }
}
