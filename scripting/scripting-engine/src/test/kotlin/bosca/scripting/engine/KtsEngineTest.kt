@file:OptIn(ExperimentalUuidApi::class)

package bosca.scripting.engine

import bosca.cache.CacheManager
import bosca.cache.CacheValue
import bosca.cache.Cache
import bosca.cache.RequestCacheSerializer
import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.scripting.context.BoscaScriptContext
import bosca.scripting.context.DefaultScriptContext
import bosca.security.service.AuthenticationContext
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

@OptIn(InternalDI::class)
class KtsEngineTest {

    private val engine = KtsEngine()
    private val authentication = AuthenticationContext(null, null)
    private val json = Json

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        val connectionPool = mockk<ConnectionPool>(relaxed = true)
        val cacheManager = mockk<CacheManager>(relaxed = true)
        val requestCacheSerializer = mockk<RequestCacheSerializer>(relaxed = true)
        val remoteCache = mockk<Cache<Any>>(relaxed = true)
        coEvery { cacheManager.maybeAddCache<Any>(any(), any()) } returns remoteCache
        coEvery { cacheManager.getCache<Any>(any()) } returns remoteCache
        every { remoteCache.keySerializer } returns mockk(relaxed = true)
        coEvery { remoteCache.get(any()) } returns mockk<CacheValue> {
            every { exists } returns false
            every { value } returns null
        }
        provides { connectionPool }
        provides { cacheManager }
        provides { requestCacheSerializer }
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    private fun TestScope.context() = DefaultScriptContext(authentication, this, json = json)

    @Test
    fun `compile and execute simple script returning string`() = runTest {
        val compiled = engine.compile<String>("test-string", 1, """main { "hello" }""")
        val result = compiled.execute(context())
        assertEquals("hello", result)
    }

    @Test
    fun `compile and execute script returning int`() = runTest {
        val compiled = engine.compile<Int>("test-int", 1, """main { 1 + 2 }""")
        val result = compiled.execute(context())
        assertEquals(3, result)
    }

    @Test
    fun `compile and execute script returning null`() = runTest {
        val compiled = engine.compile<Any>("test-null", 1, """main<Any?> { val x = 1; null }""")
        val result = compiled.execute(context())
        assertNull(result)
    }

    @Test
    fun `compile and execute script accessing context`() = runTest {
        val compiled = engine.compile<String>("test-ctx", 1, """main { context.get<String>("a") }""")
        val ctx = DefaultScriptContext(
            authentication,
            this,
            kotlinx.serialization.json.JsonObject(mapOf("a" to kotlinx.serialization.json.JsonPrimitive("b"))),
            json
        )
        val result = compiled.execute(ctx)
        assertEquals("b", result)
    }

    @Test
    fun `compile and execute script accessing authentication`() = runTest {
        val compiled = engine.compile<Boolean>("test-auth", 1, """main { authentication != null }""")
        val result = compiled.execute(context())
        assertEquals(true, result)
    }

    @Test
    fun `cache returns same compiled script for same key and version`() = runTest {
        val first = engine.compile<Int>("cache-test", 1, """main { 1 }""")
        val second = engine.compile<Int>("cache-test", 1, """main { 1 }""")
        assertSame(first, second)
    }

    @Test
    fun `different versions produce different compiled scripts`() = runTest {
        val v1 = engine.compile<Int>("version-test", 1, """main { 1 }""")
        val v2 = engine.compile<Int>("version-test", 2, """main { 2 }""")
        val r1 = v1.execute(context())
        val r2 = v2.execute(context())
        assertEquals(1, r1)
        assertEquals(2, r2)
    }

    @Test
    fun `invalidate removes cached script`() = runTest {
        val first = engine.compile<String>("invalidate-test", 1, """main { "v1" }""")
        engine.invalidate("invalidate-test", 1)
        val result1 = first.execute(context())
        assertEquals("v1", result1)
        val second = engine.compile<String>("invalidate-test", 1, """main { "v2" }""")
        // After invalidation, recompile should use new source
        val result = second.execute(context())
        assertEquals("v2", result)
    }

    @Test
    fun `invalidateAll clears cache`() = runTest {
        engine.compile<String>("clear-a", 1, """main { "a" }""")
        engine.compile<String>("clear-b", 1, """main { "b" }""")
        engine.invalidateAll()
        // Recompile with different source to prove cache was cleared
        val recompiled = engine.compile<String>("clear-a", 1, """main { "a2" }""")
        val result = recompiled.execute(context())
        assertEquals("a2", result)
    }

    @Test
    fun `script compilation error throws`() = runTest {
        assertFailsWith<Exception> {
            engine.compile<Any>("bad-script", 1, "this is not valid kotlin {{{")
        }
    }

    @Test
    fun `script runtime error throws`() = runTest {
        val compiled = engine.compile<Any>("runtime-err", 1, """main { error("boom") }""")
        val isolatedScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val ctx = DefaultScriptContext(authentication, isolatedScope, json = json)
        assertFailsWith<IllegalStateException> {
            compiled.execute(ctx)
        }
    }

    private fun isolatedContext() = DefaultScriptContext(
        authentication,
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
        json = json
    )

    @Test
    fun `script cannot access Runtime`() = runTest {
        assertFailsWith<SecurityException> {
            engine.compile<Any>("security-runtime", 1, """
                main { java.lang.Runtime.getRuntime().availableProcessors() }
            """.trimIndent())
        }
    }

    @Test
    fun `script cannot access ProcessBuilder`() = runTest {
        val compiled = engine.compile<Any>("security-process", 1, """
            main { ProcessBuilder("echo", "hello").start() }
        """.trimIndent())
        assertFailsWith<SecurityException> {
            withContext(Dispatchers.IO) {
                compiled.execute(isolatedContext())
            }
        }
    }

    @Test
    fun `script cannot access File`() = runTest {
        val compiled = engine.compile<Any>("security-file", 1, """
            main { java.io.File("/etc/passwd").readText() }
        """.trimIndent())
        assertFailsWith<SecurityException> {
            withContext(Dispatchers.IO) {
                compiled.execute(isolatedContext())
            }
        }
    }

    @Test
    fun `RestrictedClassLoader blocks denied classes`() {
        val restricted = RestrictedClassLoader(this::class.java.classLoader)
        assertFailsWith<SecurityException> {
            restricted.loadClass("java.lang.Runtime")
        }
        assertFailsWith<SecurityException> {
            restricted.loadClass("java.lang.ProcessBuilder")
        }
        assertFailsWith<SecurityException> {
            restricted.loadClass("java.io.File")
        }
    }

    @Test
    fun `RestrictedClassLoader allows non-denied classes`() {
        val restricted = RestrictedClassLoader(this::class.java.classLoader)
        // These should load fine through the restricted loader
        assertNotNull(restricted.loadClass("java.lang.String"))
        assertNotNull(restricted.loadClass("java.util.ArrayList"))
        assertNotNull(restricted.loadClass("kotlinx.serialization.json.JsonObject"))
    }

    @Test
    fun `parent-loaded services using denied classes are not affected by RestrictedClassLoader`() {
        // Simulate what happens when a script calls provide<SomeService>():
        // The service class is loaded by the parent (app) class loader,
        // so its internal use of denied classes goes through the parent loader, not the restricted one.
        val appClassLoader = this::class.java.classLoader
        val restricted = RestrictedClassLoader(appClassLoader)

        // Parent loads File directly - works fine
        val fileClass = appClassLoader.loadClass("java.io.File")
        assertNotNull(fileClass)

        // Restricted loader blocks File when script code asks for it
        assertFailsWith<SecurityException> {
            restricted.loadClass("java.io.File")
        }

        // A class loaded by the parent that internally uses File still works,
        // because class resolution uses the defining loader, not the restricted one.
        // BoscaScriptContext was loaded by appClassLoader, so it can resolve its dependencies
        // through appClassLoader regardless of RestrictedClassLoader.
        val contextClass = appClassLoader.loadClass("bosca.scripting.context.BoscaScriptContext")
        assertNotNull(contextClass)
        assertEquals(appClassLoader, contextClass.classLoader)
    }

    @Test
    fun `script can call service that internally uses denied classes`() = runTest {
        // FileUsingService uses java.io.File internally — it was loaded by the app class loader.
        // A script should be able to call it even though direct File access is blocked.
        val compiled = engine.compile<String>("security-service-bypass", 1, """
            main {
                val svc = bosca.scripting.engine.FileUsingService()
                svc.getTempDirPath()
            }
        """.trimIndent())
        val result = compiled.execute(context())
        assertNotNull(result)
    }

    @Test
    fun `script cannot use File directly but service can`() = runTest {
        // Direct File access is blocked
        val directAccess = engine.compile<Any>("security-file-direct", 1, """
            main { java.io.File.separator }
        """.trimIndent())
        assertFailsWith<SecurityException> {
            withContext(Dispatchers.IO) {
                directAccess.execute(isolatedContext())
            }
        }

        // But calling a service that uses File internally works fine
        val viaService = engine.compile<String>("security-file-via-service", 1, """
            main {
                bosca.scripting.engine.FileUsingService().getTempDirPath()
            }
        """.trimIndent())
        val result = viaService.execute(context())
        assertNotNull(result)
    }

    @Test
    fun `script can build JsonObject`() = runTest {
        val compiled = engine.compile<Any>("json-test", 1, """
            main {
                buildJsonObject {
                    put("key", "value")
                    put("num", 42)
                }
            }
        """.trimIndent())
        val result = compiled.execute(context())
        assertNotNull(result)
        val jsonObj = result as kotlinx.serialization.json.JsonObject
        assertEquals(kotlinx.serialization.json.JsonPrimitive("value"), jsonObj["key"])
        assertEquals(kotlinx.serialization.json.JsonPrimitive(42), jsonObj["num"])
    }

    @Test
    fun `RestrictedClassLoader blocks classes not in allowlist`() {
        val restricted = RestrictedClassLoader(this::class.java.classLoader)
        // com.sun.* is not in the default allowlist
        assertFailsWith<SecurityException> {
            restricted.loadClass("com.sun.net.httpserver.HttpServer")
        }
        // third-party packages not in the default allowlist
        assertFailsWith<SecurityException> {
            restricted.loadClass("okhttp3.OkHttpClient")
        }
    }

    @Test
    fun `RestrictedClassLoader always denies dangerous classes even if prefix allowed`() {
        // java.lang.* is in the default allowlist, but Runtime is always denied
        val restricted = RestrictedClassLoader(this::class.java.classLoader)
        assertNotNull(restricted.loadClass("java.lang.String"))
        assertFailsWith<SecurityException> {
            restricted.loadClass("java.lang.Runtime")
        }
        assertFailsWith<SecurityException> {
            restricted.loadClass("java.lang.ProcessBuilder")
        }
        assertFailsWith<SecurityException> {
            restricted.loadClass("java.lang.Thread")
        }
        assertFailsWith<SecurityException> {
            restricted.loadClass("java.lang.reflect.Method")
        }
    }

    @Test
    fun `RestrictedClassLoader supports config-based prefix additions`() {
        val prefixes = ScriptingSecurityConfiguration.DEFAULT_ALLOWED_PREFIXES + "org.slf4j."
        val restricted = RestrictedClassLoader(this::class.java.classLoader, prefixes)
        assertNotNull(restricted.loadClass("org.slf4j.LoggerFactory"))
        assertTrue(restricted.getAllowedPrefixes().contains("org.slf4j."))
    }

    @Test
    fun `script blocked from java net despite java lang allowed`() = runTest {
        val compiled = engine.compile<Any>("security-net", 1, """
            main { java.net.URL("http://example.com").toString() }
        """.trimIndent())
        assertFailsWith<SecurityException> {
            withContext(Dispatchers.IO) {
                compiled.execute(isolatedContext())
            }
        }
    }

    @Test
    fun `RestrictedClassLoader blocks kotlin reflect full`() {
        val restricted = RestrictedClassLoader(this::class.java.classLoader)
        assertFailsWith<SecurityException> {
            restricted.loadClass("kotlin.reflect.full.KClasses")
        }
    }

    @Test
    fun `RestrictedClassLoader blocks kotlin reflect jvm`() {
        val restricted = RestrictedClassLoader(this::class.java.classLoader)
        assertFailsWith<SecurityException> {
            restricted.loadClass("kotlin.reflect.jvm.ReflectJvmMapping")
        }
    }

    @Test
    fun `RestrictedClassLoader allows basic kotlin reflect KClass`() {
        val restricted = RestrictedClassLoader(this::class.java.classLoader)
        // Basic KClass interface is part of kotlin stdlib and should be allowed
        assertNotNull(restricted.loadClass("kotlin.reflect.KClass"))
    }

    @Test
    fun `source validator blocks System exit`() {
        assertFailsWith<SecurityException> {
            ScriptSourceValidatorImpl().validate("""main { System.exit(0) }""")
        }
    }

    @Test
    fun `source validator blocks System getenv`() {
        assertFailsWith<SecurityException> {
            ScriptSourceValidatorImpl().validate("""main { System.getenv("SECRET") }""")
        }
    }

    @Test
    fun `source validator blocks System load`() {
        assertFailsWith<SecurityException> {
            ScriptSourceValidatorImpl().validate("""main { System.load("/tmp/evil.so") }""")
        }
    }

    @Test
    fun `source validator blocks System loadLibrary`() {
        assertFailsWith<SecurityException> {
            ScriptSourceValidatorImpl().validate("""main { System.loadLibrary("evil") }""")
        }
    }

    @Test
    fun `source validator blocks System setProperty`() {
        assertFailsWith<SecurityException> {
            ScriptSourceValidatorImpl().validate("""main { System.setProperty("key", "val") }""")
        }
    }

    @Test
    fun `source validator blocks System setIn`() {
        assertFailsWith<SecurityException> {
            ScriptSourceValidatorImpl().validate("""main { System.setIn(null) }""")
        }
    }

    @Test
    fun `source validator allows safe System methods`() {
        // currentTimeMillis, nanoTime, lineSeparator, identityHashCode are safe
        ScriptSourceValidatorImpl().validate("""main { System.currentTimeMillis() }""")
        ScriptSourceValidatorImpl().validate("""main { System.nanoTime() }""")
    }

    @Test
    fun `engine rejects script with System exit before compilation`() = runTest {
        assertFailsWith<SecurityException> {
            engine.compile<Any>("security-system-exit", 1, """
                main { System.exit(0) }
            """.trimIndent())
        }
    }

    @Test
    fun `engine rejects script with System getenv before compilation`() = runTest {
        assertFailsWith<SecurityException> {
            engine.compile<Any>("security-system-getenv", 1, """
                main { System.getenv("SECRET_KEY") }
            """.trimIndent())
        }
    }

    // --- Allowlist model: bypass prevention ---

    @Test
    fun `source validator blocks System aliased to variable`() {
        assertFailsWith<SecurityException> {
            ScriptSourceValidatorImpl().validate("""main { val sys = System; sys.exit(0) }""")
        }
    }

    @Test
    fun `source validator blocks System assigned and used later`() {
        assertFailsWith<SecurityException> {
            ScriptSourceValidatorImpl().validate("""
                main {
                    val s = System
                    s.exit(0)
                }
            """.trimIndent())
        }
    }

    @Test
    fun `source validator blocks backtick-wrapped dangerous method`() {
        assertFailsWith<SecurityException> {
            ScriptSourceValidatorImpl().validate("""main { System.`exit`(0) }""")
        }
    }

    @Test
    fun `source validator blocks backtick-wrapped getenv`() {
        assertFailsWith<SecurityException> {
            ScriptSourceValidatorImpl().validate("""main { System.`getenv`("SECRET") }""")
        }
    }

    @Test
    fun `source validator allows backtick-wrapped safe method`() {
        ScriptSourceValidatorImpl().validate("""main { System.`currentTimeMillis`() }""")
    }

    @Test
    fun `source validator blocks System method reference`() {
        assertFailsWith<SecurityException> {
            ScriptSourceValidatorImpl().validate("""main { System::exit }""")
        }
    }

    @Test
    fun `source validator blocks System method reference with spaces`() {
        assertFailsWith<SecurityException> {
            ScriptSourceValidatorImpl().validate("""main { System :: getenv }""")
        }
    }

    @Test
    fun `source validator blocks import aliasing of System`() {
        assertFailsWith<SecurityException> {
            ScriptSourceValidatorImpl().validate("""
                import java.lang.System as Sys
                main { Sys.exit(0) }
            """.trimIndent())
        }
    }

    @Test
    fun `source validator blocks Runtime getRuntime`() {
        assertFailsWith<SecurityException> {
            ScriptSourceValidatorImpl().validate("""main { Runtime.getRuntime().exec("ls") }""")
        }
    }

    @Test
    fun `source validator allows System lineSeparator`() {
        ScriptSourceValidatorImpl().validate("""main { System.lineSeparator() }""")
    }

    @Test
    fun `source validator allows System identityHashCode`() {
        ScriptSourceValidatorImpl().validate("""main { System.identityHashCode("test") }""")
    }

    @Test
    fun `source validator allows System arraycopy`() {
        ScriptSourceValidatorImpl().validate("""main { System.arraycopy(intArrayOf(1), 0, intArrayOf(0), 0, 1) }""")
    }

    @Test
    fun `source validator blocks System getProperty`() {
        assertFailsWith<SecurityException> {
            ScriptSourceValidatorImpl().validate("""main { System.getProperty("user.dir") }""")
        }
    }

    @Test
    fun `source validator blocks bare System reference`() {
        assertFailsWith<SecurityException> {
            ScriptSourceValidatorImpl().validate("""main { println(System) }""")
        }
    }

    @Test
    fun `source validator allows multiple safe System calls`() {
        ScriptSourceValidatorImpl().validate("""
            main {
                val t1 = System.currentTimeMillis()
                val t2 = System.nanoTime()
                val sep = System.lineSeparator()
                t1
            }
        """.trimIndent())
    }

    @Test
    fun `source validator blocks if one of many System calls is dangerous`() {
        assertFailsWith<SecurityException> {
            ScriptSourceValidatorImpl().validate("""
                main {
                    val t = System.currentTimeMillis()
                    System.exit(0)
                }
            """.trimIndent())
        }
    }

}
