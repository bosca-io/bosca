package bosca.bible.processor

import kotlin.math.max
import kotlin.math.min

class Position(start: Int) {

    var start = start
    var end = start

    fun expand(position: Position) {
        this.start = min(this.start, position.start)
        this.end = max(this.end, position.end)
    }
}