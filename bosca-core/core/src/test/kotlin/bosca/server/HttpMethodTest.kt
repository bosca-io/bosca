package bosca.server

import kotlin.test.Test
import kotlin.test.assertEquals

class HttpMethodTest {

    @Test
    fun `parse known methods returns singleton instances`() {
        assertEquals(HttpMethod.Get, HttpMethod.parse("GET"))
        assertEquals(HttpMethod.Post, HttpMethod.parse("POST"))
        assertEquals(HttpMethod.Put, HttpMethod.parse("PUT"))
        assertEquals(HttpMethod.Delete, HttpMethod.parse("DELETE"))
        assertEquals(HttpMethod.Patch, HttpMethod.parse("PATCH"))
        assertEquals(HttpMethod.Head, HttpMethod.parse("HEAD"))
        assertEquals(HttpMethod.Options, HttpMethod.parse("OPTIONS"))
    }

    @Test
    fun `parse is case insensitive`() {
        assertEquals(HttpMethod.Get, HttpMethod.parse("get"))
        assertEquals(HttpMethod.Post, HttpMethod.parse("post"))
        assertEquals(HttpMethod.Put, HttpMethod.parse("put"))
    }

    @Test
    fun `parse unknown method returns new HttpMethod`() {
        val method = HttpMethod.parse("PROPFIND")
        assertEquals("PROPFIND", method.value)
    }

    @Test
    fun `parse unknown method uppercases value`() {
        val method = HttpMethod.parse("custom")
        assertEquals("CUSTOM", method.value)
    }
}
