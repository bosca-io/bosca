package bosca.ecommerce.graphql

import bosca.ecommerce.model.FrequencyLimit
import bosca.ecommerce.model.Promotion
import bosca.ecommerce.model.PromotionType
import bosca.ecommerce.model.Rule
import bosca.ecommerce.model.Store
import bosca.ecommerce.service.StoreService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/** Field wiring for the `Promotion` GraphQL type. [rule] is the polymorphic `Rule` union. */
@TypeController
class PromotionController(
    private val storeService: StoreService,
) : GraphQLController<Promotion> {

    @Field
    fun id(source: Promotion): UUID = source.id

    @Field
    suspend fun store(source: Promotion): Store =
        storeService.get(source.storeId) ?: error("store ${source.storeId} not found")

    @Field
    fun code(source: Promotion): String = source.code

    @Field
    fun name(source: Promotion): String = source.name

    @Field
    fun type(source: Promotion): PromotionType = source.type

    @Field
    fun rule(source: Promotion): Rule = source.rule

    @Field
    fun starts(source: Promotion): OffsetDateTime = source.starts

    @Field
    fun ends(source: Promotion): OffsetDateTime = source.ends

    @Field
    fun perAccountLimit(source: Promotion): Long = source.perAccountLimit

    @Field
    fun frequencyLimit(source: Promotion): FrequencyLimit = source.frequencyLimit

    @Field
    fun created(source: Promotion): OffsetDateTime = source.created

    @Field
    fun modified(source: Promotion): OffsetDateTime = source.modified
}
