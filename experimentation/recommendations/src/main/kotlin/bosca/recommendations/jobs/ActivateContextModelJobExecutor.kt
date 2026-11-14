package bosca.recommendations.jobs

import bosca.di.provide
import bosca.lock.DistributedLockFactory
import bosca.kubernetes.service.withServingModelLock
import bosca.kubernetes.service.ServingModelLease
import bosca.queue.annotations.JobDefinition
import bosca.recommendations.configuration.JobQueueNames
import bosca.recommendations.ml.TfServingConfiguration
import bosca.recommendations.model.RecommendationTrainingStatus
import bosca.recommendations.service.RecommendationContextService
import bosca.server.http.await
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.sharedqueue.jobs.DelayException
import bosca.sharedqueue.jobs.FailException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/** Durably waits for the exact content and optional personalized exports; keeps the active model meanwhile. */
@JobDefinition(ActivateContextModelJob::class, JobQueueNames.recommendationsJobQueue, "activate-context-model")
class ActivateContextModelJobExecutor : AbstractJobExecutor<ActivateContextModelJob>(ActivateContextModelJob.serializer()) {
    override suspend fun getLockId(): String = "context-model-activation-${getJobDefinition().version}"

    override suspend fun execute() {
        withServingModelLock(provide<DistributedLockFactory>()) { lease -> executeLocked(lease); true }
            ?: throw DelayException(15.seconds)
    }

    private suspend fun executeLocked(lease: ServingModelLease) {
        val definition = getJobDefinition()
        val service = provide<RecommendationContextService>()
        val model = service.getModel(definition.version) ?: return
        if (model.status == RecommendationTrainingStatus.FAILED) return
        val context = service.getById(model.contextId) ?: return
        if (model.status == RecommendationTrainingStatus.COMPLETED) {
            if (context.selectionRevision != definition.selectionRevision) return
            if (context.activeModelVersion == model.version) {
                if (context.requestedModelVersion == model.version) {
                    lease.ensureHeld()
                    service.activateLoadedModel(model.version, definition.selectionRevision)
                }
                return
            }
            if (context.requestedModelVersion != model.version) return
        }
        val config = provide<TfServingConfiguration>()
        val endpoint = config.contextModelUrl(model.version)
        // Provisioned endpoints must first be claimed by the controller in Kubernetes.
        if (!config.contextModelUrlTemplate.isNullOrBlank() && definition.servingEndpoint?.trimEnd('/') != endpoint.trimEnd('/')) return
        try {
            if (!available(endpoint, model.contentModelName, model.version) ||
                (model.personalized && !available(endpoint, model.personalizedModelName, model.version))) {
                throw DelayException(15.seconds)
            }
            lease.ensureHeld()
            if (model.status == RecommendationTrainingStatus.RUNNING) service.completeModel(model.version)
            if (context.selectionRevision == definition.selectionRevision) {
                lease.ensureHeld()
                service.activateLoadedModel(model.version, definition.selectionRevision)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: IOException) {
            log.warn("Context model {} is awaiting serving availability", model.version, exception)
            throw DelayException(15.seconds)
        } catch (exception: FailException) {
            lease.ensureHeld()
            service.failServingModel(model.version, definition.selectionRevision, exception.message ?: "Model load failed")
            throw exception
        } catch (exception: DelayException) {
            throw exception
        } catch (exception: Exception) {
            lease.ensureHeld()
            service.failServingModel(model.version, definition.selectionRevision, exception.message ?: "Model load validation failed")
            throw exception
        }
    }

    private suspend fun available(baseUrl: String, name: String, version: Long): Boolean {
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/v1/models/$name/versions/$version").get().build()
        httpClient.newCall(request).await().use { response ->
            if (response.code == 404 || response.code == 503) return false
            if (!response.isSuccessful) throw IOException("Model status returned HTTP ${response.code}")
            val body = Json.parseToJsonElement(response.body.string()).jsonObject
            val statuses = body["model_version_status"]?.jsonArray ?: return false
            val status = statuses.firstOrNull { it.jsonObject["version"]?.jsonPrimitive?.content == version.toString() }
                ?.jsonObject ?: return false
            val error = status["status"]?.jsonObject
            val errorCode = error?.get("error_code")?.jsonPrimitive?.content
            if (errorCode != null && errorCode != "OK" && errorCode != "0") {
                throw FailException(error["error_message"]?.jsonPrimitive?.content ?: "Model load failed: $errorCode")
            }
            return (errorCode == "OK" || errorCode == "0") &&
                status["state"]?.jsonPrimitive?.content == "AVAILABLE"
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(ActivateContextModelJobExecutor::class.java)
        val httpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    }
}
