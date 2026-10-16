package bosca.content.metadata.graphql

import bosca.bible.providers.model.BibleProviderConfiguration
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController("BibleProviderConfiguration")
class BibleProviderConfigurationController : GraphQLController<BibleProviderConfiguration> {

    @Field
    fun id(value: BibleProviderConfiguration) = value.id

    @Field
    fun name(value: BibleProviderConfiguration) = value.name

    @Field
    fun provider(value: BibleProviderConfiguration) = value.provider

    @Field
    fun enabled(value: BibleProviderConfiguration) = value.enabled

    @Field
    fun hasApiKey(value: BibleProviderConfiguration) = value.hasApiKey
}
