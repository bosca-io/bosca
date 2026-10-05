package bosca.analytics.transform

import bosca.analytics.model.Events

interface EventsTransform<T> {

    suspend fun transform(item: Events): T
}