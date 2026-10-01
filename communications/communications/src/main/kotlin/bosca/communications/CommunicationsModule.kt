package bosca.communications

import bosca.communications.service.EmailEngagementListener
import bosca.di.provide
import bosca.server.BoscaApplication
import bosca.server.BoscaApplicationModule

/**
 * Runner-only communications listener wiring. Currently starts the
 * [EmailEngagementListener] that folds the BML Message Server's first-party click/open events
 * into the delivery-tracking ledger. This module belongs only in the runner composition root
 * because engagement channels are broadcasts and must have a single application consumer.
 */
class CommunicationsModule : BoscaApplicationModule {

    override suspend fun install(application: BoscaApplication) {
        val listener = EmailEngagementListener(
            pubSubService = provide(),
            deliveryTracking = provide(),
        )
        application.onShutdown { listener.shutdown() }
    }
}
