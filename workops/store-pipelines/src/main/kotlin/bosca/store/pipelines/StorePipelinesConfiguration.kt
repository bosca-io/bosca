package bosca.store.pipelines

import bosca.di.annotation.Provider
import bosca.di.annotation.Providers

/**
 * Wires the store publisher clients used by native release actions and telemetry pipelines.
 */
@Providers
class StorePipelinesConfiguration {

    /** The concrete Google Play publisher (real Android Publisher API). */
    @Provider(singleton = true)
    fun playPublisher(): PlayPublisher = AndroidPublisherPlayPublisher()

    /** The concrete App Store publisher (real App Store Connect API). */
    @Provider(singleton = true)
    fun appStorePublisher(): AppStorePublisher = AppStoreConnectPublisher()
}
