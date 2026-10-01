package bosca.community.graphql

import bosca.community.model.Prayer
import bosca.community.model.PrayerAnniversary
import bosca.community.service.PrayerService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController
class PrayerAnniversaryController(
    private val prayerService: PrayerService,
) : GraphQLController<PrayerAnniversary> {

    @Field fun prayerId(anniversary: PrayerAnniversary) = anniversary.prayerId
    @Field fun milestone(anniversary: PrayerAnniversary) = anniversary.milestone
    @Field fun postedAt(anniversary: PrayerAnniversary) = anniversary.postedAt

    @Field
    suspend fun prayer(anniversary: PrayerAnniversary): Prayer? {
        return prayerService.getRequest(anniversary.prayerId)
    }
}
