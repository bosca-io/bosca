package bosca.jobs

import bosca.content.configuration.JobQueueNames
import bosca.content.metadata.model.MetadataSupplementaryInput
import bosca.content.metadata.service.MetadataService
import bosca.queue.annotations.IJobDefinition
import bosca.queue.annotations.JobDefinition
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.sharedqueue.jobs.job
import bosca.sharedqueue.jobs.jobQueue
import bosca.sharedqueue.jobs.prepare
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.download
import bosca.storage.service.upload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

@Serializable
data class WavToMp3Job(
    val id: UUID,
    val version: Int,
    val supplementaryId: UUID? = null
) : IJobDefinition

@JobDefinition(WavToMp3Job::class, JobQueueNames.contentJobQueue, "wav-to-mp3")
class WavToMp3JobExecutor(
    private val metadataService: MetadataService,
    private val objectStorageService: ObjectStorageService,
) : AbstractJobExecutor<WavToMp3Job>(WavToMp3Job.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        val files = mutableListOf<File>()
        val metadata = metadataService.getById(job.id, job.version) ?: error("Missing metadata")
        try {
            val file = withContext(Dispatchers.IO) { File.createTempFile("job-${job.id}-${UUID.random()}", ".wav") }
            files.add(file)
            val thisJob = job()
            val queue = jobQueue()
            val parentSupplementaryId = thisJob.getParentId()?.let {
                queue.getJob(it) {
                    it?.getContext()?.jsonObject["supplementaryId"]?.jsonPrimitive?.contentOrNull?.let { UUID.parse(it) }
                }
            }
            objectStorageService.download(metadata, job.supplementaryId ?: parentSupplementaryId).use { wav ->
                file.outputStream().use {
                    wav.copyTo(it)
                }
                Unit
            }
            val outputFile = withContext(Dispatchers.IO) { File.createTempFile("job-${job.id}-${UUID.random()}", ".mp3") }
            files.add(outputFile)
            withContext(Dispatchers.IO) {
                val ffmpegCmd = listOf(
                    "ffmpeg",
                    "-hide_banner",
                    "-loglevel", "error",
                    "-y",
                    "-i", file.absolutePath,
                    "-vn",
                    "-ar",
                    "44100",
                    "-ac",
                    "2",
                    "-b:a",
                    "96k",
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
                    key = "mp3-${job.id}",
                    name = "MP3 File",
                    contentLength = outputFile.length(),
                    contentType = "audio/mp3",
                )
            )
            objectStorageService.upload(metadata, supplementary.id, outputFile)
            metadataService.setSupplementaryUploaded(supplementary.id, "audio/mp3", outputFile.length())
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