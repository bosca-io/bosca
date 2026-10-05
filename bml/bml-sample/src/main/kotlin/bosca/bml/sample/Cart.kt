package bosca.bml.sample

import kotlinx.serialization.Serializable

/**
 * A live-island state model held in the server session (`scope="server-session"`): the `Cart` lives in the
 * server-side session and never reaches the client — only an opaque session id does. Same `@Serializable`
 * + `@click`-method shape as a client-scoped model (see [CounterModel]); only where it's stored differs.
 */
@Serializable
class Cart {

    var itemCount: Int = 0
        private set

    fun addItem() {
        itemCount++
    }

    fun clear() {
        itemCount = 0
    }
}
