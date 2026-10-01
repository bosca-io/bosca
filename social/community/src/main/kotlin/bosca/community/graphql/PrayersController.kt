package bosca.community.graphql

import bosca.community.model.Prayer
import bosca.community.model.Prayers
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController
class PrayersController : GraphQLController<Prayers> {

    @Field
    fun prayers(prayers: Prayers): List<Prayer> = prayers.prayers

    @Field
    fun total(prayers: Prayers): Long = prayers.total
}
