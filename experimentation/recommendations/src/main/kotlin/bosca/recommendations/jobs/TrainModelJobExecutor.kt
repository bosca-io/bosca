package bosca.recommendations.jobs

import bosca.di.ObjectProvider
import bosca.di.provide
import bosca.di.provideProvider
import bosca.kubernetes.jobs.KubernetesJobRequest
import bosca.kubernetes.model.KubernetesJobResultStatus
import bosca.kubernetes.service.KubernetesJobDispatchService
import bosca.queue.annotations.JobDefinition
import bosca.recommendations.configuration.JobQueueNames
import bosca.recommendations.model.RecommendationContext
import bosca.recommendations.model.RecommendationTrainingStatus
import bosca.recommendations.service.RecommendationContextService
import bosca.server.http.await
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.sharedqueue.jobs.DelayException
import bosca.sharedqueue.jobs.FailException
import bosca.sharedqueue.jobs.job
import bosca.sharedqueue.jobs.jobQueue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Runs TFRS model training as an ephemeral Kubernetes Job and durably polls its terminal result.
 *
 * The dispatch ID is written back to [TrainModelJob] before this executor delays itself. Redelivery
 * therefore releases the recommendations runner while training is active and safely resumes polling after
 * process restarts. Without a Kubernetes provider, training runs on a long-lived trainer service over HTTP:
 * the run is named after the model version, started in the background and polled the same way, so a long
 * run never holds a request open and a restart on either side resumes or restarts that version's run.
 * A trainer without background runs is called synchronously, as before.
 */
@JobDefinition(TrainModelJob::class, JobQueueNames.recommendationsJobQueue, "train-model")
open class TrainModelJobExecutor : AbstractJobExecutor<TrainModelJob>(
    TrainModelJob.serializer()
) {
    /** Generic dispatch provider; absent in local non-Kubernetes application roots. */
    protected open val kubernetesDispatchService: ObjectProvider<KubernetesJobDispatchService>
        get() = provideProvider()

    /** JobProfile name and poll interval, open so focused tests do not wait or depend on process env. */
    protected open val kubernetesJobProfile: String
        get() = System.getenv("ML_TRAINER_JOB_PROFILE") ?: DEFAULT_KUBERNETES_JOB_PROFILE
    protected open val kubernetesResultPollInterval: Duration get() = DEFAULT_RESULT_POLL_INTERVAL

    /** Local-development HTTP fallback, open so tests can point it at a mock server. */
    protected open val trainerUrl: String get() = System.getenv("ML_TRAINER_URL") ?: DEFAULT_TRAINER_URL
    protected open val httpClient: OkHttpClient get() = sharedHttpClient

    override suspend fun getLockId(): String = "$TRAINING_LOCK_ID-${getJobDefinition().contextModelVersion ?: "all"}"

    override val skipExecutionIfLocked: Boolean = false

    override suspend fun execute() {
        val definition = getJobDefinition()
        val service = provide<RecommendationContextService>()
        val version = definition.contextModelVersion
        if (version == null) {
            service.trainAll()
            return
        }
        val model = service.getModel(version) ?: return
        if (model.status == RecommendationTrainingStatus.COMPLETED || model.status == RecommendationTrainingStatus.FAILED) return
        service.startModel(version)
        val json = provide<Json>()
        val previousModel = model.context.activeModelVersion?.let { service.getModel(it) }
        val configured = definition.copy(configuration = buildJsonObject {
            (definition.configuration as? JsonObject)?.forEach { (key, value) ->
                if (key != "previous_personalized_model_version") put(key, value)
            }
            put("context", json.encodeToJsonElement(RecommendationContext.serializer(), model.context))
            put("model_version", version)
            put("content_model_name", model.contentModelName)
            put("personalized_model_name", model.personalizedModelName)
            if (previousModel?.status == RecommendationTrainingStatus.COMPLETED && previousModel.personalized) {
                put("previous_personalized_model_version", previousModel.version)
            }
        })
        try {
            if (kubernetesDispatchService.exists) {
                executeKubernetes(configured, json)
            } else {
                executeHttpRun(configured, "$TRAINER_RUN_PREFIX$version")
            }
            val updated = service.getModel(version) ?: return
            if (!updated.exported) throw FailException("Trainer completed without registering its context exports")
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: DelayException) {
            throw exception
        } catch (exception: Exception) {
            service.failModel(version, exception.message ?: "Context training failed")
            throw exception
        }
    }

    private suspend fun executeKubernetes(definition: TrainModelJob, json: Json) {
        val currentJob = job()
        val currentJobId = currentJob.getId()
        val dispatcher = kubernetesDispatchService.get()
        val dispatchId = definition.kubernetesDispatchId ?: dispatcher.dispatch(
            KubernetesJobRequest(
                profile = kubernetesJobProfile,
                idempotencyKey = "recommendation-training-$currentJobId",
                environment = trainingEnvironment(definition.configuration),
                labels = mapOf(RECOMMENDATION_JOB_ID_LABEL to currentJobId.toString()),
            )
        ).also {
            val persisted = json.encodeToJsonElement(
                TrainModelJob.serializer(),
                definition.copy(kubernetesDispatchId = it),
            )
            jobQueue().setDefinition(currentJob, persisted)
            log.info(
                "Dispatched recommendation training job {} to Kubernetes profile {} as {}",
                currentJobId,
                kubernetesJobProfile,
                it,
            )
        }

        val result = dispatcher.getResult(dispatchId)
            ?: throw DelayException(kubernetesResultPollInterval)
        when (result.status) {
            KubernetesJobResultStatus.SUCCEEDED ->
                log.info("Recommendation training Kubernetes Job {} completed", dispatchId)
            KubernetesJobResultStatus.FAILED ->
                throw FailException(result.message ?: "Recommendation training Kubernetes Job failed")
            KubernetesJobResultStatus.CANCELLED ->
                throw FailException(result.message ?: "Recommendation training Kubernetes Job was cancelled")
        }
    }

    private fun trainingEnvironment(configuration: JsonElement?): Map<String, String> {
        if (configuration == null) return emptyMap()
        return mapOf(
            CONFIGURATION_JSON_ENVIRONMENT to Json.encodeToString(
                JsonElement.serializer(),
                configuration,
            ),
        )
    }

    /**
     * Drives one named background run on the trainer service: collects its result when finished, keeps
     * polling while it runs, and starts it when the trainer does not know it (first attempt, or the trainer
     * restarted). A trainer that answers 404 to starting a run predates background runs and is called
     * synchronously instead.
     */
    private suspend fun executeHttpRun(job: TrainModelJob, runId: String) {
        val trainerUrl = trainerUrl
        val runUrl = "$trainerUrl/runs/$runId"
        val (pollCode, pollBody) = call(Request.Builder().url(runUrl).get().build())
        when (pollCode) {
            200 -> {
                val run = Json.decodeFromString(JsonElement.serializer(), pollBody).jsonObject
                when ((run["status"] as? JsonPrimitive)?.content) {
                    "running" -> throw DelayException(kubernetesResultPollInterval)
                    "finished" -> {
                        val result = run["result"] as? JsonObject
                            ?: throw FailException("Trainer run $runId finished without a result")
                        log.info("Trainer run {} finished", runId)
                        handleTrainingResult(result)
                        return
                    }
                    else -> throw RuntimeException("Unexpected trainer run state for $runId: $pollBody")
                }
            }
            404 -> Unit
            else -> throw RuntimeException("Trainer run $runId lookup failed with status $pollCode: $pollBody")
        }

        log.info("Kubernetes dispatch is disabled; starting trainer run {} at {}", runId, trainerUrl)
        val (startCode, startBody) = call(
            Request.Builder()
                .url(runUrl)
                .header("Content-Type", "application/json")
                .post(trainingRequestBody(job).toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )
        when (startCode) {
            // Started or already running (a concurrent start), or another run holds the trainer: poll later.
            200, 202, 409 -> throw DelayException(kubernetesResultPollInterval)
            404 -> executeHttp(job)
            else -> throw RuntimeException("Starting trainer run $runId failed with status $startCode: $startBody")
        }
    }

    private suspend fun call(request: Request): Pair<Int, String> {
        val response = httpClient.newCall(request).await()
        return response.use { it.code to it.body.string() }
    }

    private fun trainingRequestBody(job: TrainModelJob): String =
        if (job.configuration != null) Json.encodeToString(JsonElement.serializer(), job.configuration) else "{}"

    /** Synchronous training for trainers without background runs; the request stays open for the whole run. */
    private suspend fun executeHttp(job: TrainModelJob) {
        val trainerUrl = trainerUrl

        log.info("Kubernetes dispatch is disabled; triggering local model training at {}", trainerUrl)

        val request = Request.Builder()
            .url("$trainerUrl/train")
            .header("Content-Type", "application/json")
            .post(trainingRequestBody(job).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = httpClient.newCall(request).await()
        val body = response.body.string()
        response.close()

        if (!response.isSuccessful) {
            log.error("Training request failed ({}): {}", response.code, body)
            throw RuntimeException("Training failed with status ${response.code}: $body")
        }

        handleTrainingResult(Json.decodeFromString(JsonElement.serializer(), body).jsonObject)
    }

    private fun handleTrainingResult(result: JsonObject) {
        val status = (result["status"] as? JsonPrimitive)?.content ?: "unknown"

        when (status) {
            // The trainer now trains TWO models per run: the content (cold) base — always — and the
            // personalized two-tower — only when interactions suffice. Each reports its own outcome under
            // `models`, so log them independently.
            "completed" -> {
                val interactions = (result["interactions"] as? JsonPrimitive)?.content
                log.info("Training run completed over {} interactions", interactions)
                val models = result["models"]?.jsonObject
                logContentModel(models?.get("content")?.jsonObject)
                logPersonalizedModel(models?.get("personalized")?.jsonObject)
            }
            "no_content" -> {
                val message = (result["message"] as? JsonPrimitive)?.content
                log.info("Training skipped: {}", message)
            }
            "already_training" -> {
                throw DelayException(kubernetesResultPollInterval)
            }
            else -> {
                log.warn("Unexpected training status: {}", status)
            }
        }
    }

    /** Logs the content (cold) model outcome — trained every run, so a missing block is unexpected. */
    private fun logContentModel(content: JsonObject?) {
        if (content == null) {
            log.warn("Training result missing the content model outcome")
            return
        }
        when ((content["status"] as? JsonPrimitive)?.content) {
            "completed_content_only" -> {
                val version = (content["model_version"] as? JsonPrimitive)?.content
                val items = (content["content_items"] as? JsonPrimitive)?.content
                val validation = ((content["validation"] as? JsonObject)?.get("status") as? JsonPrimitive)?.content
                val uploadError = (content["bosca_upload_error"] as? JsonPrimitive)?.content
                if (uploadError != null) {
                    log.warn("Content model trained (version {}, {} items) but upload failed: {}", version, items, uploadError)
                } else {
                    log.info("Content model completed: version {}, {} items, validation {}", version, items, validation)
                }
            }
            "rejected" -> {
                val reason = (content["reason"] as? JsonPrimitive)?.content
                log.warn("Content model was not promoted: {}. Current model stays in service.", reason)
            }
            else -> log.warn("Unexpected content model status: {}", content["status"])
        }
    }

    /** Logs the personalized two-tower outcome: validated and promoted / invalid / skipped. */
    private fun logPersonalizedModel(personalized: JsonObject?) {
        if (personalized == null) {
            log.warn("Training result missing the personalized model outcome")
            return
        }
        when (val pStatus = (personalized["status"] as? JsonPrimitive)?.content) {
            "completed" -> {
                val version = (personalized["model_version"] as? JsonPrimitive)?.content
                val validation =
                    ((personalized["validation"] as? JsonObject)?.get("status") as? JsonPrimitive)?.content
                val trainingUsers = (personalized["training_users"] as? JsonPrimitive)?.content
                val indexedUsers = (personalized["indexed_users"] as? JsonPrimitive)?.content
                log.info(
                    "Personalized model completed: version {}, validation {}, trained users {}, indexed users {}",
                    version, validation, trainingUsers, indexedUsers,
                )
            }
            "rejected" -> {
                val reason = (personalized["reason"] as? JsonPrimitive)?.content
                log.warn("Personalized model was not promoted: {}. Current model stays in service.", reason)
            }
            "skipped" -> {
                val reason = (personalized["reason"] as? JsonPrimitive)?.content
                log.info("Personalized model skipped: {} (content base still refreshed)", reason)
            }
            else -> {
                log.warn("Unexpected personalized model status: {}", pStatus)
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(TrainModelJobExecutor::class.java)
        private const val DEFAULT_KUBERNETES_JOB_PROFILE = "recommendation-trainer"
        private const val TRAINING_LOCK_ID = "recommendation-model-training"
        private const val CONFIGURATION_JSON_ENVIRONMENT = "ML_TRAINER_CONFIGURATION_JSON"
        private const val RECOMMENDATION_JOB_ID_LABEL = "recommendations.bosca.io/job-id"
        private const val DEFAULT_TRAINER_URL = "http://recommendation-trainer:8090"
        /** Trainer run IDs name the model version, so each training snapshot maps to exactly one run. */
        private const val TRAINER_RUN_PREFIX = "recommendation-model-"
        private val DEFAULT_RESULT_POLL_INTERVAL = 15.seconds
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val sharedHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.MINUTES)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
