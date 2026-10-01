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
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.upload
import com.fasterxml.jackson.core.StreamReadConstraints
import com.google.auth.oauth2.GoogleCredentials
import com.google.genai.types.Content
import com.google.genai.types.CountTokensConfig
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.PrebuiltVoiceConfig
import com.google.genai.types.SpeechConfig
import com.google.genai.types.VoiceConfig
import bosca.server.BoscaApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.*
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.jvm.optionals.getOrNull

@Serializable
class GoogleGenAITTSJob(
    val id: UUID,
    val version: Int,
    val modelKey: String,
    val promptKey: String,
    val configuration: DocumentToTextConfiguration,
    val supplementaryId: UUID? = null
) : IJobDefinition

@JobDefinition(GoogleGenAITTSJob::class, JobQueueNames.contentJobQueue, "google-genai-ai-tts")
class GoogleGenAITTSExecutor(
    private val metadataService: MetadataService,
    private val documentService: DocumentService,
    private val bibleService: BibleService,
    private val modelService: ModelService,
    private val promptService: PromptService,
    private val application: BoscaApplication,
    private val objectStorageService: ObjectStorageService,
    private val json: Json
) : AbstractJobExecutor<GoogleGenAITTSJob>(GoogleGenAITTSJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        val files = mutableListOf<File>()
        try {
            val model = modelService.getByKey(job.modelKey) ?: error("Missing model")
            val prompt = promptService.getByKey(job.promptKey) ?: error("Missing prompt")
            val metadata = metadataService.getById(job.id, job.version) ?: error("Missing metadata")
            val document = documentService.getDocument(job.id, job.version) ?: error("Missing document")
            val transform = DocumentToTextTransformation(
                metadataService = metadataService,
                bibleService = bibleService,
                json = json,
                configuration = job.configuration
            )
            val text = transform.transform(Unit, LocaleAwareDocument(Locale.forLanguageTag(metadata.languageTag), document))
            val outputFile = withContext(Dispatchers.IO) {
                File.createTempFile("chirp-${job.id}-${UUID.random()}", ".wav")
            }
            files.add(outputFile)

            val credentials = application.environment.config.property("google.genai.account").getString().toByteArray().inputStream().use {
                GoogleCredentials.fromStream(it)
            }
            val client = com.google.genai.Client.builder()
                .credentials(credentials)
                .vertexAI(application.environment.config.property("google.genai.vertexai.enabled").getAs())
                .build()

            val systemPrompt = prompt.systemPrompt.takeIf { it.isNotEmpty() }?.let { "$it\r\n" } ?: ""
            val userPrompt = prompt.userPrompt.takeIf { it.isNotEmpty() }?.let { "$it\r\n" }?.replace("{document}", text) ?: ""
            val promptText = "$systemPrompt$userPrompt"

            val countResponse = client.models.countTokens(model.type, promptText, CountTokensConfig.builder().build())
            val tokens = countResponse.totalTokens().get()
            if (tokens > 32_000) {
                throw Exception("prompt text exceeds maximum token count")
            }
            val response = client.models.generateContent(
                model.type,
                Content
                    .builder()
                    .role("user")
                    .parts(
                        Part.fromText(promptText)
                    )
                    .build(),
                GenerateContentConfig
                    .builder()
                    .temperature(model.configuration?.jsonObject["temperature"]?.jsonPrimitive?.floatOrNull ?: 0.7f)
                    .responseModalities("audio")
                    .speechConfig(
                        SpeechConfig
                            .builder()
                            .languageCode(model.configuration?.jsonObject["languageTag"]?.jsonPrimitive?.content ?: metadata.languageTag)
                            .voiceConfig(
                                VoiceConfig
                                    .builder()
                                    .prebuiltVoiceConfig(
                                        PrebuiltVoiceConfig
                                            .builder()
                                            .voiceName(model.configuration?.jsonObject["voiceName"]?.jsonPrimitive?.content ?: error("Missing voiceName"))
                                            .build()
                                    )
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )

            val candidates = response.candidates().getOrNull() ?: emptyList()
            if (candidates.isEmpty()) error("no audio candidates")
            for (candidate in candidates) {
                val content = candidate.content().getOrNull() ?: continue
                val parts = content.parts().getOrNull() ?: error("missing parts")
                if (parts.isEmpty()) error("no parts")
                for (part in parts) {
                    val inlineData = part.inlineData().getOrNull() ?: error("missing inline data")
                    val mimeTypeParts = inlineData.mimeType().getOrNull()?.split(";") ?: error("missing mime type")
                    val mimeType = mimeTypeParts[0]
                    val attributes = mimeTypeParts.subList(1, mimeTypeParts.size).associate {
                        it.split("=").let { (k, v) -> k to v }
                    }
                    val bitsPerSampleParts = mimeType.split("/")[1]
                    val sampleRate = attributes["rate"]?.toIntOrNull() ?: error("missing sample rate")
                    val data = inlineData.data().getOrNull() ?: error("missing data")
                    val wav = convertPcmToWav(
                        data,
                        sampleRate,
                        bitsPerSampleParts.startsWith("L"),
                        bitsPerSampleParts.substring(1).toInt()
                    )
                    outputFile.writeBytes(wav)
                    val supplementary = metadataService.addSupplementary(
                        MetadataSupplementaryInput(
                            metadataId = job.id,
                            jobId = job.id,
                            key = "audio-wav-${job.id}",
                            name = "Audio File",
                            contentLength = outputFile.length(),
                            contentType = "audio/wav",
                        )
                    )
                    objectStorageService.upload(metadata, supplementary.id, outputFile)
                    metadataService.setSupplementaryUploaded(supplementary.id, "audio/wav", outputFile.length())
                    setContext(
                        JsonObject(
                            mapOf(
                                "supplementaryId" to JsonPrimitive(supplementary.id.toString())
                            )
                        )
                    )
                    return
                }
            }
            error("failed to generate audio")
        } finally {
            files.forEach { it.delete() }
        }
    }

    private fun convertPcmToWav(pcmData: ByteArray, sampleRate: Int, littleEndian: Boolean, bits: Int): ByteArray {
        val audioFormat = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            sampleRate.toFloat(),
            bits,
            1,
            bits / 8,
            sampleRate.toFloat(),
            !littleEndian
        )
        val output = ByteArrayOutputStream()
        AudioInputStream(
            ByteArrayInputStream(pcmData),
            audioFormat,
            pcmData.size / audioFormat.frameSize.toLong()
        ).use {
            AudioSystem.write(it, AudioFileFormat.Type.WAVE, output)
        }
        return output.toByteArray()
    }

    companion object {

        init {
            StreamReadConstraints.overrideDefaultStreamReadConstraints(
                StreamReadConstraints.builder().maxStringLength(Int.MAX_VALUE).build()
            )
        }
    }
}