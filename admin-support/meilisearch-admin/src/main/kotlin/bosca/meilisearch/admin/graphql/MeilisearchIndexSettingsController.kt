package bosca.meilisearch.admin.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.meilisearch.admin.model.MeilisearchEmbedderEntry
import bosca.meilisearch.admin.model.MeilisearchFaceting
import bosca.meilisearch.admin.model.MeilisearchIndexSettings
import bosca.meilisearch.admin.model.MeilisearchPagination
import bosca.meilisearch.admin.model.MeilisearchSynonym
import bosca.meilisearch.admin.model.MeilisearchTypoTolerance

/**
 * Resolves fields on the MeilisearchIndexSettings GraphQL type.
 */
@TypeController
class MeilisearchIndexSettingsController : GraphQLController<MeilisearchIndexSettings> {

    @Field
    fun rankingRules(settings: MeilisearchIndexSettings) = settings.rankingRules

    @Field
    fun searchableAttributes(settings: MeilisearchIndexSettings) = settings.searchableAttributes

    @Field
    fun displayedAttributes(settings: MeilisearchIndexSettings) = settings.displayedAttributes

    @Field
    fun filterableAttributes(settings: MeilisearchIndexSettings) = settings.filterableAttributes

    @Field
    fun sortableAttributes(settings: MeilisearchIndexSettings) = settings.sortableAttributes

    @Field
    fun stopWords(settings: MeilisearchIndexSettings) = settings.stopWords

    @Field
    fun synonyms(settings: MeilisearchIndexSettings): List<MeilisearchSynonym> = settings.synonyms

    @Field
    fun distinctAttribute(settings: MeilisearchIndexSettings) = settings.distinctAttribute

    @Field
    fun typoTolerance(settings: MeilisearchIndexSettings): MeilisearchTypoTolerance? = settings.typoTolerance

    @Field
    fun pagination(settings: MeilisearchIndexSettings): MeilisearchPagination? = settings.pagination

    @Field
    fun faceting(settings: MeilisearchIndexSettings): MeilisearchFaceting? = settings.faceting

    @Field
    fun embedders(settings: MeilisearchIndexSettings): List<MeilisearchEmbedderEntry> = settings.embedders

    @Field
    fun proximityPrecision(settings: MeilisearchIndexSettings) = settings.proximityPrecision

    @Field
    fun searchCutoffMs(settings: MeilisearchIndexSettings) = settings.searchCutoffMs
}
