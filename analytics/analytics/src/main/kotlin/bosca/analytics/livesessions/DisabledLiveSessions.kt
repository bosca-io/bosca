package bosca.analytics.livesessions

import bosca.analytics.model.LiveSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Explicitly disables the optional live-session map when no backend is configured for it. */
class DisabledLiveSessions : LiveSessionsService {
    override suspend fun publish(appId: String, session: LiveSession) { }
    override fun subscribe(appId: String?, appVersion: String?): Flow<LiveSession> = emptyFlow()
}
