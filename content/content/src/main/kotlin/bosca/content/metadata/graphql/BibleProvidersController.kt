package bosca.content.metadata.graphql

import bosca.bible.providers.model.BibleSourceKind
import bosca.bible.providers.model.BibleCatalog
import bosca.bible.providers.model.BibleProviderConfiguration
import bosca.bible.providers.model.BibleSource
import bosca.bible.providers.service.BibleProviderService
import bosca.content.metadata.model.Bible
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

object BibleProviders

@TypeController
class BibleProvidersController(
    private val providers: BibleProviderService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<BibleProviders> {

    @Field
    fun providers(authentication: AuthenticationContext): List<String> {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return providers.getProviders()
    }

    @Field
    suspend fun configurations(authentication: AuthenticationContext): List<BibleProviderConfiguration> {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return providers.getConfigurations()
    }

    @Field
    suspend fun catalog(
        authentication: AuthenticationContext,
        configurationId: UUID,
        kind: BibleSourceKind,
        query: String?,
        offset: Int,
        limit: Int,
    ): List<BibleCatalog> {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return providers.getCatalog(configurationId, kind, query, offset, limit)
    }

    @Field
    suspend fun sources(authentication: AuthenticationContext, configurationId: UUID): List<BibleSource> {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return providers.getSources(configurationId)
    }

    @Field
    suspend fun audioTargets(authentication: AuthenticationContext, configurationId: UUID, providerBibleId: String): List<Bible> {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return providers.getAudioTargets(configurationId, providerBibleId)
    }
}
