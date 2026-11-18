package bosca.atlas.server.graphql

import bosca.di.provide
import bosca.graphql.SchemaRegistry
import bosca.graphql.dispatcher.Dispatcher
import bosca.graphql.dispatcher.DispatchersRegistrar
import bosca.graphql.server.TypeRuntimeWiring
import bosca.profile.profile.graphql.ProfileControllerDispatcher
import bosca.profile.profile.graphql.ProfilesControllerDispatcher
import bosca.profile.profile.graphql.ProfilesMutationControllerDispatcher
import bosca.search.graphql.SearchDocumentControllerDispatcher
import bosca.search.graphql.SearchResultControllerDispatcher
import bosca.search.graphql.SearchResultFacetControllerDispatcher

/** Reuses domain resolvers for the identity fields exposed by this composition's SDL. */
internal class IdentityDispatchersRegistrar : DispatchersRegistrar {
    override suspend fun dispatchers(): Map<String, Dispatcher> =
        listOf(
                bosca.content.graphql.ContentControllerDispatcher(provide()),
                bosca.content.graphql.ContentMutationControllerDispatcher(provide()),
                bosca.content.metadata.graphql.BibleProvidersControllerDispatcher(provide()),
                bosca.content.metadata.graphql.BibleProvidersMutationControllerDispatcher(provide()),
                bosca.content.metadata.graphql.BibleProviderConfigurationControllerDispatcher(provide()),
                bosca.content.metadata.graphql.BibleCatalogControllerDispatcher(provide()),
                bosca.content.metadata.graphql.BibleSourceControllerDispatcher(provide()),
                bosca.content.metadata.graphql.BibleAttributionControllerDispatcher(provide()),
                bosca.content.metadata.graphql.BibleLanguageControllerDispatcher(provide()),
                bosca.content.metadata.graphql.BibleControllerDispatcher(provide()),
                bosca.content.metadata.graphql.BibleAudioControllerDispatcher(provide()),
                bosca.collaboration.graphql.CollaborationControllerDispatcher(provide()),
                bosca.collaboration.graphql.CollaborationMutationControllerDispatcher(provide()),
                bosca.collaboration.graphql.FederationControllerDispatcher(provide()),
                bosca.collaboration.graphql.FederationMutationControllerDispatcher(provide()),
                bosca.collaboration.graphql.FederationPeerControllerDispatcher(provide()),
                bosca.collaboration.graphql.FederatedChannelControllerDispatcher(provide()),
                bosca.installer.graphql.PackageInstallationControllerDispatcher(provide()),
                bosca.installer.graphql.PackageInstallationVersionControllerDispatcher(provide()),
                bosca.installer.graphql.PackageInstallationHistoryControllerDispatcher(provide()),
                bosca.installer.graphql.InstalledPackageControllerDispatcher(provide()),
                bosca.installer.graphql.PackagesControllerDispatcher(provide()),
                bosca.installer.graphql.PackagesMutationControllerDispatcher(provide()),
                bosca.ai.graphql.AiControllerDispatcher(provide()),
                bosca.ai.models.graphql.ModelsControllerDispatcher(provide()),
                bosca.ai.models.graphql.ModelsMutationControllerDispatcher(provide()),
                bosca.ai.models.graphql.ModelControllerDispatcher(provide()),
                bosca.search.graphql.SearchConfigurationControllerDispatcher(provide()),
                bosca.storage.graphql.StorageSystemModelControllerDispatcher(provide()),
                bosca.storage.graphql.StorageSystemsMutationControllerDispatcher(provide()),
                bosca.storage.graphql.StorageSystemsControllerDispatcher(provide()),
                bosca.storage.graphql.StorageSystemControllerDispatcher(provide()),
                bosca.profile.persona.graphql.StudioPersonasControllerDispatcher(provide()),
                bosca.profile.persona.graphql.StudioPersonaControllerDispatcher(provide()),
                bosca.profile.persona.graphql.StudioPersonasMutationControllerDispatcher(provide()),
                bosca.profile.profile.graphql.ProfileMutationControllerDispatcher(provide()),
                bosca.profile.profile.graphql.ThirdPartyExtensionControllerDispatcher(provide()),
                bosca.profile.profile.graphql.ThirdPartyExtensionMutationControllerDispatcher(provide()),
                ProfilesControllerDispatcher(provide()),
                ProfilesMutationControllerDispatcher(provide()),
                ProfileControllerDispatcher(provide()),
                SearchDocumentControllerDispatcher(provide()),
                SearchResultControllerDispatcher(provide()),
                SearchResultFacetControllerDispatcher(provide()),
            )
            .associate { dispatcher ->
                val source = dispatcher.type
                val fields =
                    SchemaRegistry.registry.fields(source.typeName).orEmpty().mapTo(
                        mutableSetOf()
                    ) {
                        it.name
                    }
                source.typeName to
                    object : Dispatcher {
                        override val type =
                            TypeRuntimeWiring.newTypeWiring(source.typeName)
                                .apply {
                                    source.fieldResolvers
                                        .filterKeys { it in fields }
                                        .forEach { (name, resolver) -> field(name, resolver) }
                                }
                                .build()
                    }
            }
}
