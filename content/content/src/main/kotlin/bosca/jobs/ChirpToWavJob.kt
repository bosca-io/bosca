package bosca.jobs

import bosca.ai.models.service.ModelService
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
import com.google.api.gax.core.FixedCredentialsProvider
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.texttospeech.v1.AudioConfig
import com.google.cloud.texttospeech.v1.AudioEncoding
import com.google.cloud.texttospeech.v1.SynthesisInput
import com.google.cloud.texttospeech.v1.SynthesizeSpeechRequest
import com.google.cloud.texttospeech.v1.TextToSpeechClient
import com.google.cloud.texttospeech.v1.TextToSpeechSettings
import com.google.cloud.texttospeech.v1.VoiceSelectionParams
import bosca.server.BoscaApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.*

@Serializable
data class ChirpToWavJob(
    val id: UUID,
    val version: Int,
    val modelKey: String,
    val configuration: DocumentToTextConfiguration,
    val supplementaryId: UUID? = null
) : IJobDefinition

@JobDefinition(ChirpToWavJob::class, JobQueueNames.contentJobQueue, "chirp-to-wav")
class ChirpToWavJobExecutor(
    private val metadataService: MetadataService,
    private val documentService: DocumentService,
    private val bibleService: BibleService,
    private val modelService: ModelService,
    private val application: BoscaApplication,
    private val objectStorageService: ObjectStorageService,
    private val json: Json
) : AbstractJobExecutor<ChirpToWavJob>(ChirpToWavJob.serializer()) {

    private fun newSpeechClient(credentials: GoogleCredentials) = TextToSpeechClient.create(
        TextToSpeechSettings.newBuilder()
            .setCredentialsProvider(
                FixedCredentialsProvider.create(credentials)
            ).build()
    )

    override suspend fun execute() {
        val job = getJobDefinition()
        val files = mutableListOf<File>()
        try {
            val metadata = metadataService.getById(job.id, job.version) ?: error("Missing metadata")
            val document = documentService.getDocument(job.id, job.version) ?: error("Missing document")
            val transform = DocumentToTextTransformation(
                metadataService = metadataService,
                bibleService = bibleService,
                json = json,
                configuration = job.configuration
            )
            val model = modelService.getByKey(job.modelKey) ?: error("Missing model")
            val credentials = application.environment.config.property("google.tts.account").getString().toByteArray().inputStream().use {
                GoogleCredentials.fromStream(it)
            }
            val outputFile = withContext(Dispatchers.IO) {
                File.createTempFile("chirp-${job.id}-${UUID.random()}", ".wav")
            }
            files.add(outputFile)
            val fileChunks = mutableListOf<File>()
            val text = transform.transform(Unit, LocaleAwareDocument(Locale.forLanguageTag(metadata.languageTag), document))
            newSpeechClient(credentials).use { client ->
                for (paragraph in text.split("[split]")) {
                    if (paragraph.isBlank()) continue
                    val inputBuilder = SynthesisInput.newBuilder()
                    inputBuilder.markup = paragraph.trim()
                    val input = inputBuilder.build()
                    val voice = VoiceSelectionParams.newBuilder()
                        .setLanguageCode(model.configuration?.jsonObject?.get("languageTag")?.jsonPrimitive?.content ?: metadata.languageTag)
                        .setName(model.type)
                        .build()
                    val audioConfig = AudioConfig.newBuilder()
                        .setAudioEncoding(AudioEncoding.LINEAR16)
                        .build()
                    val request = SynthesizeSpeechRequest.newBuilder()
                        .setInput(input)
                        .setVoice(voice)
                        .setAudioConfig(audioConfig)
                        .build()
                    val response = withContext(Dispatchers.IO) { client.synthesizeSpeech(request) }
                    val file = File.createTempFile("chirp-${job.id}-${UUID.random()}", ".wav")
                    file.outputStream().use {
                        it.write(response.audioContent.toByteArray())
                    }
                    files.add(file)
                    fileChunks += file
                }
            }
            withContext(Dispatchers.IO) {
                val listFile = File.createTempFile("chirp-${job.id}-${UUID.random()}", ".txt")
                files.add(listFile)
                listFile.outputStream().use { stream ->
                    fileChunks.forEach { chunk ->
                        stream.write("file '${chunk.absolutePath}'\n".toByteArray())
                    }
                }
                // Use ffmpeg to concatenate the files
                val ffmpegCmd = listOf(
                    "ffmpeg",
                    "-hide_banner",
                    "-loglevel", "error",
                    "-y",
                    "-f", "concat",
                    "-safe", "0",
                    "-i", listFile.absolutePath,
                    "-c", "copy",
                    outputFile.absolutePath
                )
                val process = ProcessBuilder(ffmpegCmd)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                    .start()
                val exitCode = process.waitFor()
                if (exitCode != 0) {
                    throw RuntimeException("ffmpeg process failed with exit code $exitCode")
                }
            }
            val supplementary = metadataService.addSupplementary(
                MetadataSupplementaryInput(
                    metadataId = job.id,
                    jobId = job.id,
                    key = "chirp-wav-${job.id}",
                    name = "Chirp WAV File",
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
        } finally {
            files.forEach { it.delete() }
        }
    }
}