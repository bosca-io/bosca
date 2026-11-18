package bosca.notifications.web

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.*

/** Token-scoped, isolated notification API with explicit rejection and pending-response controls. */
internal class NotificationApiFixture : AutoCloseable {
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val reject = AtomicBoolean(false)
    private val hold = AtomicReference<CountDownLatch?>()
    private val failure = AtomicReference<Throwable?>()
    private val email = AtomicBoolean(false)
    private val push = AtomicBoolean(false)
    val origin: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                val result = when (exchange.requestURI.path) {
                    "/fixture/reject" -> { reject.set(true); "{}" }
                    "/fixture/hold" -> { check(hold.compareAndSet(null, CountDownLatch(1))); "{}" }
                    "/fixture/release" -> { hold.getAndSet(null)?.countDown(); "{}" }
                    "/fixture/state" -> """{"email":${email.get()},"push":${push.get()}}"""
                    "/graphql" -> {
                        val input = Json.parseToJsonElement(exchange.requestBody.readAllBytes().decodeToString()).jsonObject
                        val variables = input.getValue("variables").jsonObject
                        val operation = input.getValue("operationName").jsonPrimitive.content
                        val token = variables.getValue("token").jsonPrimitive.content
                        check(token in setOf("fixture-token", "invalid"))
                        if (operation in setOf("UpdateTokenPreference", "UnsubscribeAll")) {
                            check(token == "fixture-token")
                            hold.get()?.let { check(it.await(20, TimeUnit.SECONDS)) { "Held fixture response was not released" } }
                            if (reject.getAndSet(false)) """{"errors":[{"message":"Fixture rejected change"}]}"""
                            else {
                                when (operation) {
                                    "UpdateTokenPreference" -> {
                                        check(variables.getValue("type").jsonPrimitive.content == "digest")
                                        val channel = variables.getValue("channel").jsonPrimitive.content
                                        check(channel in setOf("EMAIL", "PUSH"))
                                        (if (channel == "EMAIL") email else push).set(variables.getValue("optedOut").jsonPrimitive.boolean)
                                        """{"data":{"communications":{"updateTokenNotificationPreference":true}}}"""
                                    }
                                    else -> { email.set(true); """{"data":{"communications":{"unsubscribe":true}}}""" }
                                }
                            }
                        } else {
                            check(operation in setOf("TokenPreferences", "TokenPreferenceLink"))
                            val preferences = if (token == "invalid") "null" else """[
                              {"channel":"EMAIL","type":"digest","optedOut":${email.get()}},
                              {"channel":"PUSH","type":"digest","optedOut":${push.get()}}
                            ]"""
                            """{"data":{"communications":{"notificationTypes":[
                              {"key":"security","name":"Security notices","description":"Essential account messages","optional":false,"system":true,"displayOrder":0},
                              {"key":"digest","name":"Product updates and a deliberately long notification name","description":"Occasional product news with enough text to wrap on a phone.","optional":true,"system":false,"displayOrder":1}
                            ],"tokenNotificationPreferences":$preferences}}}"""
                        }
                    }
                    else -> error("Unexpected fixture path ${exchange.requestURI}")
                }
                val bytes = result.toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
                exchange.sendResponseHeaders(500, -1)
            } finally {
                exchange.close()
            }
        }
        server.start()
    }

    fun verify() { failure.get()?.let { throw AssertionError("Notification API fixture failed", it) } }

    override fun close() {
        hold.getAndSet(null)?.countDown()
        server.stop(0)
        executor.close()
    }
}
