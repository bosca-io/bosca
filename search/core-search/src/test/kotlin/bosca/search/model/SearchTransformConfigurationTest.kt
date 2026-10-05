package bosca.search.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SearchTransformConfigurationTest {

    @Test
    fun defaultExpressions() {
        val config = SearchTransformConfiguration()
        assertEquals(SearchTransformExpressions(), config.expressions)
    }

    @Test
    fun customExpressions() {
        val exprs = SearchTransformExpressions(metadata = "expr1", collection = "expr2", profile = "expr3")
        val config = SearchTransformConfiguration(expressions = exprs)
        assertEquals(exprs, config.expressions)
    }
}

class SearchTransformExpressionsTest {

    @Test
    fun defaultsAreNull() {
        val exprs = SearchTransformExpressions()
        assertNull(exprs.metadata)
        assertNull(exprs.collection)
        assertNull(exprs.profile)
    }

    @Test
    fun fieldsArePreserved() {
        val exprs = SearchTransformExpressions(metadata = "m", collection = "c", profile = "p")
        assertEquals("m", exprs.metadata)
        assertEquals("c", exprs.collection)
        assertEquals("p", exprs.profile)
    }

    @Test
    fun dataClassEquality() {
        val a = SearchTransformExpressions(metadata = "m")
        val b = SearchTransformExpressions(metadata = "m")
        assertEquals(a, b)
    }
}
