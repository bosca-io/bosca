package bosca.bible.usx

import kotlin.test.Test
import kotlin.test.assertEquals

class PositionTest {

    @Test
    fun constructorWithStartAndEnd() {
        val pos = Position(10, 20)
        assertEquals(10, pos.start)
        assertEquals(20, pos.end)
    }

    @Test
    fun constructorWithStartOnlyDefaultsEndToStart() {
        val pos = Position(5)
        assertEquals(5, pos.start)
        assertEquals(5, pos.end)
    }

    @Test
    fun expandWithLargerRange() {
        val pos = Position(10, 20)
        pos.expand(Position(5, 30))
        assertEquals(5, pos.start)
        assertEquals(30, pos.end)
    }

    @Test
    fun expandWithSmallerRangeNoChange() {
        val pos = Position(5, 30)
        pos.expand(Position(10, 20))
        assertEquals(5, pos.start)
        assertEquals(30, pos.end)
    }

    @Test
    fun expandWithOverlappingRange() {
        val pos = Position(10, 20)
        pos.expand(Position(5, 15))
        assertEquals(5, pos.start)
        assertEquals(20, pos.end)
    }

    @Test
    fun expandOnlyLowersStart() {
        val pos = Position(10, 20)
        pos.expand(Position(5, 10))
        assertEquals(5, pos.start)
        assertEquals(20, pos.end)
    }

    @Test
    fun expandOnlyRaisesEnd() {
        val pos = Position(10, 20)
        pos.expand(Position(15, 30))
        assertEquals(10, pos.start)
        assertEquals(30, pos.end)
    }

    @Test
    fun expandWithSameRange() {
        val pos = Position(10, 20)
        pos.expand(Position(10, 20))
        assertEquals(10, pos.start)
        assertEquals(20, pos.end)
    }

    @Test
    fun startAndEndAreMutable() {
        val pos = Position(0, 0)
        pos.start = 100
        pos.end = 200
        assertEquals(100, pos.start)
        assertEquals(200, pos.end)
    }
}
