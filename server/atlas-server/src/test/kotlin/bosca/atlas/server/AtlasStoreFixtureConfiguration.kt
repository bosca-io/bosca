package bosca.atlas.server

import bosca.analytics.service.AnalyticsQueryExecutionService
import bosca.di.annotation.InternalDI
import bosca.di.provideProvider
import bosca.di.provides
import bosca.store.pipelines.AndroidPublisherPlayPublisher
import bosca.store.pipelines.AppStoreConnectPublisher
import bosca.store.pipelines.AppStorePublisher
import bosca.store.pipelines.PlayPublisher
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.androidpublisher.AndroidPublisher

/** Uses the production vendor adapters against the disposable loopback vendor server. */
@OptIn(InternalDI::class)
internal fun configureAtlasStoreFixture(origin: String) {
    val actualAnalytics = provideProvider<AnalyticsQueryExecutionService>()
    provides<AnalyticsQueryExecutionService>(overrideExisting = true) { AtlasStoreHistoryFixture(actualAnalytics.get(), origin) }
    provides<PlayPublisher>(overrideExisting = true) { AndroidPublisherPlayPublisher(publisherFactory = { credential ->
        check(credential == "atlas-store-fixture-play")
        AndroidPublisher.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), null).setRootUrl("$origin/")
            .setApplicationName("Atlas store fixture").build()
    }) }
    provides<AppStorePublisher>(overrideExisting = true) { AppStoreConnectPublisher(baseUrl = origin) }
}
