package bosca.backup

import bosca.storage.service.ObjectNotFoundException
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.StringObjectPath
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Transfers one native database artifact in a caller-owned, exclusively written object prefix. */
internal class PostgresBackupArtifactStore(
    private val storage: ObjectStorageService,
    private val json: Json,
    private val prefix: String,
) {
    init {
        require(prefix.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
            "Invalid PostgreSQL backup prefix: $prefix"
        }
    }

    /** Checks the exact completed native artifact without creating local files or repairing storage. */
    suspend fun verify(manifest: PostgresBackupManifest) {
        require(read("manifest.json") == manifest) { "Retained PostgreSQL manifest differs from the local artifact" }
        for (payload in payloads(manifest)) storage.verifyBackupPayload(payload)
    }

    /** Receives an already verified local manifest; source bytes are checked again during transfer. */
    suspend fun upload(directory: Path, manifest: PostgresBackupManifest): PostgresBackupManifest {
        val completionText = try {
            readText("manifest.json")
        } catch (_: ObjectNotFoundException) {
            null
        }
        var partialCompletion: SerializationException? = null
        val completed = try {
            completionText?.let { decode(it) }
        } catch (failure: SerializationException) {
            partialCompletion = failure
            null
        }
        val intent = readOrNull("intent.json")
        require(completed == null || completed == manifest) { "Conflicting completed PostgreSQL backup: $prefix" }
        require(intent == null || intent == manifest) { "Conflicting PostgreSQL backup intent: $prefix" }
        if (intent == null && partialCompletion != null) throw partialCompletion
        val selected = payloads(manifest)
        if (completed == null && intent == null) {
            for (payload in selected) {
                val existing = try {
                    storage.getObjectInfo(StringObjectPath(payload.payloadPath))
                } catch (_: ObjectNotFoundException) {
                    null
                }
                require(existing == null) { "Unowned PostgreSQL backup payload already exists: ${payload.payloadPath}" }
            }
            write("intent.json", manifest)
        }

        for (payload in selected) {
            if (!storage.hasVerifiedBackupPayload(payload)) {
                BackupPayloadInputStream(
                    Files.newInputStream(directory.resolve(payload.key)), payload, currentCoroutineContext(),
                ).use { input ->
                    val written = storage.setInputStream(StringObjectPath(payload.payloadPath), input, payload.length)
                    input.verifyComplete()
                    check(written == payload.length) {
                        "Incomplete PostgreSQL payload write: expected ${payload.length}, wrote $written"
                    }
                }
                storage.verifyBackupPayload(payload)
            }
        }
        if (completed == null) write("manifest.json", manifest)
        return manifest
    }

    /** Requires completion metadata; retained intent alone cannot authorize a download. */
    suspend fun download(directory: Path): PostgresBackupManifest {
        currentCoroutineContext().ensureActive()
        Files.createDirectory(directory)
        var published = false
        var failure: Throwable? = null
        try {
            val manifest = read("manifest.json")
            for (selected in payloads(manifest)) {
                val temporary = directory.resolve("${selected.key}.tmp")
                BackupPayloadInputStream(
                    storage.getInputStream(StringObjectPath(selected.payloadPath)), selected, currentCoroutineContext(),
                ).use { input ->
                    Files.newOutputStream(temporary, CREATE_NEW, WRITE).use { input.transferTo(it) }
                    input.verifyComplete()
                }
                if (selected.key == manifest.payloadPath) requireNativePostgresDump(temporary)
                Files.move(temporary, directory.resolve(selected.key))
            }
            val metadata = directory.resolve("manifest.json.tmp")
            Files.writeString(metadata, json.encodeToString(PostgresBackupManifest.serializer(), manifest), CREATE_NEW, WRITE)
            currentCoroutineContext().ensureActive()
            Files.move(metadata, directory.resolve("manifest.json"))
            published = true
            return manifest
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (!published) withContext(NonCancellable) {
                try {
                    for (name in listOf(
                        "database.dump.tmp", "database.dump",
                        "manifest.json.tmp", "manifest.json",
                    )) {
                        Files.deleteIfExists(directory.resolve(name))
                    }
                    Files.delete(directory)
                } catch (cleanup: Throwable) {
                    failure?.addSuppressed(cleanup) ?: throw cleanup
                }
            }
        }
    }

    private fun payloads(manifest: PostgresBackupManifest): List<BackupObject> = buildList {
        add(
            BackupObject(
                store = "postgresql",
                key = manifest.payloadPath,
                revision = manifest.id,
                length = manifest.length,
                sha256 = manifest.sha256,
                payloadPath = "$prefix/${manifest.payloadPath}",
            ),
        )
    }

    private suspend fun readOrNull(name: String): PostgresBackupManifest? = try {
        read(name)
    } catch (_: ObjectNotFoundException) {
        null
    }

    private suspend fun read(name: String): PostgresBackupManifest = decode(readText(name))

    private suspend fun readText(name: String): String =
        storage.getInputStream(StringObjectPath("$prefix/$name")).bufferedReader().use {
            it.readText()
        }

    private fun decode(text: String): PostgresBackupManifest =
        json.decodeFromString(PostgresBackupManifest.serializer(), text).also { it.validate() }

    private suspend fun write(name: String, manifest: PostgresBackupManifest) {
        currentCoroutineContext().ensureActive()
        val bytes = json.encodeToString(PostgresBackupManifest.serializer(), manifest).toByteArray()
        val written = bytes.inputStream().use { storage.setInputStream(StringObjectPath("$prefix/$name"), it, bytes.size.toLong()) }
        check(written == bytes.size.toLong()) { "Incomplete PostgreSQL metadata write: $name" }
        check(read(name) == manifest) { "Persisted PostgreSQL metadata differs from the selected artifact: $name" }
    }
}
