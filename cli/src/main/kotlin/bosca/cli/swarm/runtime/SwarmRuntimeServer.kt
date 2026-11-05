package bosca.cli.swarm.runtime

import bosca.ai.kit.harness.KitSwarmExecution
import bosca.ai.kit.harness.KitSwarmRequest
import bosca.serialization.UUID
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.security.MessageDigest

internal fun swarmRuntimeServer(service: SwarmRuntimeService, json: Json, token: String, host: String, port: Int) = embeddedServer(Netty, host = host, port = port) {
    val sweep = launch {
        while (isActive) {
            try { service.sweep() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { log.warn("Swarm runtime cleanup will retry", error) }
            delay(5000)
        }
    }
    monitor.subscribe(ApplicationStopped) { sweep.cancel() }
    routing {
        intercept(ApplicationCallPipeline.Call) {
            if (call.request.path() != "/healthz") {
                val supplied = call.request.headers["Authorization"]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ") ?: ""
                if (!MessageDigest.isEqual(supplied.toByteArray(), token.toByteArray())) {
                    call.respond(HttpStatusCode.Unauthorized); finish()
                }
            }
        }
        get("/healthz") { call.respond(HttpStatusCode.OK) }
        put("/v1/runtimes/{id}") { call.swarmOperation {
            val request = json.decodeFromString(KitSwarmRequest.serializer(), call.receiveText())
            call.respondText(json.encodeToString(KitSwarmExecution.serializer(), service.ensure(UUID.parse(requireNotNull(call.parameters["id"])), request)), io.ktor.http.ContentType.Application.Json)
        } }
        get("/v1/runtimes/{id}") { call.swarmOperation {
            val observation = service.status(UUID.parse(requireNotNull(call.parameters["id"])))
            if (observation == null) call.respond(HttpStatusCode.NotFound)
            else call.respondText(json.encodeToString(KitSwarmExecution.serializer(), observation), io.ktor.http.ContentType.Application.Json)
        } }
        post("/v1/runtimes/{id}/stop") { call.swarmOperation {
            val request = json.decodeFromString(KitSwarmRequest.serializer(), call.receiveText())
            call.respondText(service.stop(UUID.parse(requireNotNull(call.parameters["id"])), request).toString(), io.ktor.http.ContentType.Application.Json)
        } }
    }
}

private suspend fun ApplicationCall.swarmOperation(action: suspend () -> Unit) {
    try {
        if (withTimeoutOrNull(30_000) { action(); true } != true) respondText("Swarm controller operation exceeded its deadline", status = HttpStatusCode.GatewayTimeout)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: SerializationException) { respondText("Invalid runtime request", status = HttpStatusCode.BadRequest) }
    catch (error: IllegalArgumentException) { respondText(error.message ?: "Invalid Swarm profile", status = HttpStatusCode.UnprocessableEntity) }
    catch (error: IllegalStateException) { respondText(error.message ?: "Swarm controller operation failed", status = HttpStatusCode.BadGateway) }
}
