package bosca.jobs

import bosca.ai.models.service.ModelService
import bosca.ai.prompts.service.PromptService
import bosca.content.configuration.JobQueueNames
import bosca.content.metadata.model.LocaleAwareDocument
import bosca.content.metadata.model.MetadataSupplementaryInput
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.MetadataService
import bosca.content.transformations.DocumentToTextConfiguration
import bosca.content.transformations.DocumentToTextTransformation
import bosca.queue.annotations.IJobDefinition
import bosca.queue.annotations.JobDefinition
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.sharedqueue.jobs.FailException
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.upload
import com.google.auth.oauth2.GoogleCredentials
import com.google.genai.types.FinishReason
import com.google.genai.types.GenerateContentConfig
import bosca.server.BoscaApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.util.*
import java.util.UUID as JUUID

@Serializable
class GoogleGenAIImageJob(
    val id: UUID,
    val version: Int,
    val modelKey: String,
    val promptKey: String,
    val configuration: DocumentToTextConfiguration = DocumentToTextConfiguration(),
) : IJobDefinition

@JobDefinition(GoogleGenAIImageJob::class, JobQueueNames.contentJobQueue, "google-genai-generate-image")
class GoogleGenAIImageExecutor(
    private val metadataService: MetadataService,
    private val modelService: ModelService,
    private val promptService: PromptService,
    private val application: BoscaApplication,
    private val objectStorageService: ObjectStorageService,
    private val documentService: DocumentService,
    private val bibleService: BibleService,
    private val json: kotlinx.serialization.json.Json
) : AbstractJobExecutor<GoogleGenAIImageJob>(GoogleGenAIImageJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        val files = mutableListOf<File>()
        try {
            val model = modelService.getByKey(job.modelKey) ?: error("Missing model")

            val credentials = application.environment.config.property("google.genai.account").getString().toByteArray().inputStream().use {
                GoogleCredentials.fromStream(it)
            }

            val prompt = promptService.getByKey(job.promptKey) ?: error("Missing prompt")
            val metadata = metadataService.getById(job.id, job.version) ?: error("Missing metadata")

            val client = com.google.genai.Client.builder()
                .credentials(credentials)
                .vertexAI(application.environment.config.property("google.genai.vertexai.enabled").getAs())
                .build()

            val document = documentService.getDocument(job.id, job.version) ?: error("Missing document")
            val transform = DocumentToTextTransformation(
                metadataService = metadataService,
                bibleService = bibleService,
                json = json,
                configuration = job.configuration
            )
            val text = transform.transform(Unit, LocaleAwareDocument(Locale.forLanguageTag(metadata.languageTag), document))

            val systemPrompt = prompt.systemPrompt.takeIf { it.isNotEmpty() }?.let { "$it\r\n" } ?: ""
            val userPrompt = prompt.userPrompt.takeIf { it.isNotEmpty() }?.let { "$it\r\n" }?.replace("{document}", text) ?: ""
            val promptText = "$systemPrompt$userPrompt".ifBlank { "Generate an image" }

            val response = client.models.generateContent(
                model.type,
                promptText,
                GenerateContentConfig
                    .builder()
                    .responseModalities("TEXT", "IMAGE")
                    .build()
            )

            if (response.finishReason().knownEnum() == FinishReason.Known.NO_IMAGE) {
                throw FailException("No image found in response")
            }

            var mimeType = "unknown"
            var outputFile: File? = null
            for (part in Objects.requireNonNull(response.parts())!!) {
                if (part.inlineData().isPresent) {
                    val blob = part.inlineData().get()
                    if (blob.data().isPresent) {
                        try {
                            blob.mimeType().ifPresent { mimeType = it }
                            outputFile = withContext(Dispatchers.IO) {
                                File.createTempFile(
                                    "img-${job.id}-${JUUID.randomUUID()}",
                                    if (mimeType.startsWith("image/")) ".${mimeType.substringAfterLast('/')}" else ".unknown"
                                )
                            }
                            files.add(outputFile)
                            outputFile.writeBytes(blob.data().get())
                            break
                        } catch (e: IOException) {
                            throw RuntimeException(e)
                        }
                    }
                }
            }

            if (outputFile == null) {
                throw FailException("No image found in response")
            }

            val supplementary = metadataService.addSupplementary(
                MetadataSupplementaryInput(
                    metadataId = job.id,
                    jobId = job.id,
                    key = "google-genai-image-${job.id}",
                    name = "Generated Image",
                    contentLength = outputFile.length(),
                    contentType = mimeType,
                )
            )
            objectStorageService.upload(metadata, supplementary.id, outputFile)
            metadataService.setSupplementaryUploaded(supplementary.id, mimeType, outputFile.length())
            setContext(
                JsonObject(
                    mapOf(
                        "supplementaryId" to JsonPrimitive(supplementary.id.toString())
                    )
                )
            )
        } finally {
            files.forEach { it.delete() }
        }
    }
}
