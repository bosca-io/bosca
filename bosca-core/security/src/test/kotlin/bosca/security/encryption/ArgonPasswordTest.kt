package bosca.security.encryption

import kotlin.test.Test
import kotlin.test.assertEquals

class ArgonPasswordTest {

    @Test
    fun `ArgonPassword stores hash`() {
        val password = ArgonPassword(hash = "\$argon2id\$v=19\$m=65536,t=3,p=1\$salt\$hash")
        assertEquals("\$argon2id\$v=19\$m=65536,t=3,p=1\$salt\$hash", password.hash)
    }

    @Test
    fun `ArgonPassword toString returns hash`() {
        val hashStr = "\$argon2id\$v=19\$m=65536,t=3,p=1\$salt\$hash"
        val password = ArgonPassword(hash = hashStr)
        assertEquals(hashStr, password.toString())
    }

    @Test
    fun `ArgonPassword with empty hash`() {
        val password = ArgonPassword(hash = "")
        assertEquals("", password.hash)
        assertEquals("", password.toString())
    }
}
