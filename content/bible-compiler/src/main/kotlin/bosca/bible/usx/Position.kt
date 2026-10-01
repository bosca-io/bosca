package bosca.bible.usx

import kotlin.math.max
import kotlin.math.min

class Position(start: Int, end: Int = start) {

    var start = start
    var end = end

    fun expand(position: Position) {
        this.start = min(this.start, position.start)
        this.end = max(this.end, position.end)
    }
}