package bosca.content.metadata.graphql

import bosca.bible.BibleAudio
import bosca.bible.providers.model.AddProviderAudioInput
import bosca.bible.providers.model.AddProviderBibleInput
import bosca.bible.providers.model.BibleProviderConfigurationInput
import bosca.bible.providers.model.BibleProviderConfiguration
import bosca.bible.providers.service.BibleProviderService
import bosca.content.metadata.model.Bible
import bosca.content.metadata.service.BibleService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

object BibleProvidersMutation

@TypeController
class BibleProvidersMutationController(
    private val providers: BibleProviderService,
    private val bibles: BibleService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<BibleProvidersMutation> {

    @Field
    suspend fun saveConfiguration(authentication: AuthenticationContext, input: BibleProviderConfigurationInput): BibleProviderConfiguration {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return providers.saveConfiguration(input)
    }

    @Field
    suspend fun test(authentication: AuthenticationContext, configurationId: UUID): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        providers.test(configurationId)
        return true
    }

    @Field
    suspend fun addBible(authentication: AuthenticationContext, input: AddProviderBibleInput): Bible {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return providers.addBible(input)
    }

    @Field
    suspend fun addAudio(
        authentication: AuthenticationContext,
        bibleId: UUID,
        version: Int,
        variant: String,
        input: AddProviderAudioInput,
    ): BibleAudio {
        groupEvaluator.verifyHasAdminGroup(authentication)
        val bible = bibles.getVariants(bibleId, version).singleOrNull { it.variant == variant }
            ?: error("Bible variant was not found")
        return providers.addAudio(bible, input)
    }

    @Field
    suspend fun refresh(authentication: AuthenticationContext, sourceId: UUID): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        providers.refresh(sourceId)
        return true
    }

    @Field
    suspend fun removeSource(authentication: AuthenticationContext, sourceId: UUID, expectedVersion: Long): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        providers.removeSource(sourceId, expectedVersion)
        return true
    }

    @Field
    suspend fun removeConfiguration(authentication: AuthenticationContext, configurationId: UUID, purgeSources: Boolean): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        providers.removeConfiguration(configurationId, purgeSources)
        return true
    }
}
