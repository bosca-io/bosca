package bosca.core.preferences.impl

import bosca.core.preferences.Preferences
import bosca.core.preferences.PreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.uuid.Uuid

class PreferencesRepositoryImpl(private val preferences: Preferences) : PreferencesRepository {
    private val LAST_COMMUNITY_ID = "last_community_id"
    private val LAST_CHANNEL_ID_PREFIX = "last_channel_id_"

    override val lastCommunityId: Flow<Uuid?> = preferences.getString(LAST_COMMUNITY_ID).map {
        Uuid.parse(it ?: return@map null)
    }

    override suspend fun setLastCommunityId(id: Uuid) {
        preferences.setString(LAST_COMMUNITY_ID, id.toString())
    }

    override fun lastChannelId(communityId: Uuid): Flow<Uuid?> = preferences.getString(LAST_CHANNEL_ID_PREFIX + communityId.toString()).map {
        Uuid.parse(it ?: return@map null)
    }

    override suspend fun setLastChannelId(communityId: Uuid, channelId: Uuid) {
        preferences.setString(LAST_CHANNEL_ID_PREFIX + communityId.toString(), channelId.toString())
    }
}
