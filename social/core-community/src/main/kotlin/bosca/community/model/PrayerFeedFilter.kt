package bosca.community.model

import bosca.serialization.UUID

data class PrayerFeedFilter(
    val communityGroupIds: List<UUID>,
    val statuses: String? = null,
    val limit: Int,
    val offset: Int
)
