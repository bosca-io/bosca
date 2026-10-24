package bosca.design.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesignServerStartupTest {
    @Test
    fun `configured endpoint and failed bind do not indicate readiness`() {
        assertNull(designBoundPort("""
            23:30:03 INFO  bosca.bml.server.BmlServer - bml-server serving design-library-example@1 on http://localhost:0
            23:30:03 ERROR bosca.server.netty.NettyServerEngine - Bosca server could not bind port 0; stopping
            java.net.BindException: Address already in use
        """.trimIndent()))
    }

    @Test
    fun `only the successful engine bind supplies the actual endpoint`() {
        assertNull(designBoundPort("23:30:03 INFO  other.Server - Bosca server started on port 9092 with 8 worker threads"))
        assertNull(designBoundPort("23:30:03 ERROR bosca.server.netty.NettyServerEngine - Bosca server started on port 9092 with 8 worker threads"))
        assertEquals(61320, designBoundPort("""
            23:30:03 INFO  bosca.bml.server.BmlServer - bml-server serving design-library-example@1 on http://localhost:0
            23:30:03 INFO  bosca.server.netty.NettyServerEngine - Bosca server started on port 61320 with 8 worker threads
        """.trimIndent()))
    }

    @Test
    fun `incomplete and invalid bound ports do not indicate readiness`() {
        for (port in listOf("0", "65536", "999999999999999", "invalid")) {
            assertNull(designBoundPort("23:30:03 INFO  bosca.server.netty.NettyServerEngine - Bosca server started on port $port with 8 worker threads"))
        }
        assertNull(designBoundPort("23:30:03 INFO  bosca.server.netty.NettyServerEngine - Bosca server started on port 61320"))
    }
}
