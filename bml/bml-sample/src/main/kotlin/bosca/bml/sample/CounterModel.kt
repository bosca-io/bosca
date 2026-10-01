package bosca.bml.sample

import kotlinx.serialization.Serializable

@Serializable
class CounterModel {

    var count: Int = 0
        private set

    fun increment() {
        count++
    }
}