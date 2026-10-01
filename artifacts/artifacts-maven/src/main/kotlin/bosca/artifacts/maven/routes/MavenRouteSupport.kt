package bosca.artifacts.maven.routes

import bosca.artifacts.model.ArtifactType
import bosca.artifacts.service.ArtifactRepositoryService
import bosca.artifacts.service.BlobStorageService
import bosca.server.HttpHeaders
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import java.io.File
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parsed Maven coordinates extracted from a standard Maven repository path.
 *
 * @param groupId the Maven group ID (e.g., `io.bosca`)
 * @param artifactId the Maven artifact ID
 * @param version the version string, or null for repository-level metadata
 * @param filename the filename being requested
 */
internal data class MavenCoordinates(
    val groupId: String,
    val artifactId: String,
    val version: String?,
    val filename: String?,
)

/**
 * Parses a standard Maven repository path into coordinates.
 *
 * The path must NOT include the repository prefix — that is extracted by
 * the route handler before calling this function.
 *
 * Maven paths follow the convention:
 * - `{group/...}/{artifactId}/{version}/{filename}` — versioned artifact
 * - `{group/...}/{artifactId}/maven-metadata.xml` — artifact-level metadata
 */
internal fun parseMavenPath(path: String): MavenCoordinates? {
    val segments = path.removePrefix("/").split("/").filter { it.isNotEmpty() }
    if (segments.size < 3) return null

    if (segments.any { it == ".." || it.startsWith(".") || it.contains('\u0000') }) return null

    val lastSegment = segments.last()
    val isMetadata = lastSegment.startsWith("maven-metadata") &&
        (lastSegment.endsWith(".xml") || lastSegment.endsWith(".sha1") || lastSegment.endsWith(".md5") || lastSegment.endsWith(".sha256") || lastSegment.endsWith(".sha512"))
    if (isMetadata) {
        val groupParts = segments.dropLast(2)
        if (groupParts.isEmpty()) return null
        val artifactId = segments[segments.size - 2]
        return MavenCoordinates(
            groupId = groupParts.joinToString("."),
            artifactId = artifactId,
            version = null,
            filename = segments.last(),
        )
    }

    if (segments.size < 4) return null
    val filename = segments.last()
    val version = segments[segments.size - 2]
    val artifactId = segments[segments.size - 3]
    val groupParts = segments.dropLast(3)
    if (groupParts.isEmpty()) return null

    return MavenCoordinates(
        groupId = groupParts.joinToString("."),
        artifactId = artifactId,
        version = version,
        filename = filename,
    )
}

/**
 * Parses Maven POM XML to extract the canonical `groupId`, `artifactId`,
 * and `version` declared inside the project element. Falls back to the
 * parent element for `groupId` and `version` when the project-level
 * elements are absent, matching Maven's own inheritance rules.
 *
 * @return coordinates extracted from the POM, or null if the XML is
 *         malformed or missing required elements
 */
internal fun parsePomCoordinates(input: InputStream): MavenCoordinates? {
    return try {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        val doc = factory.newDocumentBuilder().parse(input)
        val root = doc.documentElement

        fun directChild(parent: org.w3c.dom.Element, tag: String): String? {
            val nodes = parent.childNodes
            for (i in 0 until nodes.length) {
                val node = nodes.item(i)
                if (node is org.w3c.dom.Element && node.tagName == tag && node.parentNode == parent) {
                    return node.textContent?.trim()?.takeIf { it.isNotEmpty() }
                }
            }
            return null
        }

        val parentElement = run {
            val nodes = root.childNodes
            for (i in 0 until nodes.length) {
                val node = nodes.item(i)
                if (node is org.w3c.dom.Element && node.tagName == "parent" && node.parentNode == root) {
                    return@run node
                }
            }
            null
        }

        val artifactId = directChild(root, "artifactId") ?: return null
        val groupId = directChild(root, "groupId")
            ?: parentElement?.let { directChild(it, "groupId") }
            ?: return null
        val version = directChild(root, "version")
            ?: parentElement?.let { directChild(it, "version") }
            ?: return null

        MavenCoordinates(
            groupId = groupId,
            artifactId = artifactId,
            version = version,
            filename = null,
        )
    } catch (_: Exception) {
        null
    }
}

/**
 * Parses a POM file on disk and returns the declared coordinates.
 */
internal fun parsePomFile(file: File): MavenCoordinates? {
    return file.inputStream().use { parsePomCoordinates(it) }
}

/**
 * Determines the role of a Maven artifact file from its filename.
 */
internal fun mavenRole(filename: String): String {
    return when {
        filename.endsWith(".pom") -> "pom"
        filename.endsWith("-sources.jar") -> "sources"
        filename.endsWith("-javadoc.jar") -> "javadoc"
        filename.endsWith(".jar") -> "jar"
        filename.endsWith(".aar") -> "aar"
        filename.endsWith(".klib") -> "klib"
        filename.endsWith(".module") -> "module"
        filename.endsWith(".asc") -> "signature"
        else -> "artifact"
    }
}

internal fun guessMavenContentType(filename: String): String {
    return when {
        filename.endsWith(".pom") -> "application/xml"
        filename.endsWith(".jar") -> "application/java-archive"
        filename.endsWith(".aar") -> "application/octet-stream"
        filename.endsWith(".module") -> "application/json"
        filename.endsWith(".asc") -> "text/plain"
        else -> "application/octet-stream"
    }
}

internal fun isChecksumFile(filename: String?): Boolean {
    return filename?.let {
        it.endsWith(".sha1") || it.endsWith(".sha256") || it.endsWith(".md5")
    } ?: false
}

internal fun isMetadataChecksumFile(filename: String?): Boolean {
    if (filename == null) return false
    return filename.startsWith("maven-metadata") &&
        (filename.endsWith(".sha1") || filename.endsWith(".sha256") || filename.endsWith(".md5") || filename.endsWith(".sha512"))
}

/**
 * Builds the artifact-level maven-metadata.xml content for a given artifact
 * within the specified namespace.
 */
private suspend fun buildMavenMetadataXml(
    namespace: String,
    coords: MavenCoordinates,
    repoService: ArtifactRepositoryService,
): String? {
    val repo = repoService.findRepository(namespace, "${coords.groupId}.${coords.artifactId}", ArtifactType.MAVEN)
        ?: return null

    val allVersionStrings = mutableListOf<String>()
    var offset = 0L
    val pageSize = 500
    while (true) {
        val page = repoService.listVersions(repo.id, pageSize, offset)
        if (page.isEmpty()) break
        page.mapTo(allVersionStrings) { it.version }
        if (page.size < pageSize) break
        offset += pageSize
    }

    val latest = allVersionStrings.firstOrNull() ?: ""
    val release = allVersionStrings.firstOrNull { !it.contains("SNAPSHOT") } ?: latest

    return buildString {
        appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        appendLine("<metadata>")
        appendLine("  <groupId>${escapeXml(coords.groupId)}</groupId>")
        appendLine("  <artifactId>${escapeXml(coords.artifactId)}</artifactId>")
        appendLine("  <versioning>")
        appendLine("    <latest>${escapeXml(latest)}</latest>")
        appendLine("    <release>${escapeXml(release)}</release>")
        appendLine("    <versions>")
        allVersionStrings.forEach { appendLine("      <version>${escapeXml(it)}</version>") }
        appendLine("    </versions>")
        appendLine("  </versioning>")
        appendLine("</metadata>")
    }
}

internal suspend fun handleMavenMetadata(
    call: ServerCall,
    namespace: String,
    coords: MavenCoordinates,
    repoService: ArtifactRepositoryService,
) {
    val xml = buildMavenMetadataXml(namespace, coords, repoService)
    if (xml == null) {
        call.respond(HttpStatusCode.NotFound)
        return
    }
    call.response.header(HttpHeaders.ContentType, "application/xml")
    call.respond(HttpStatusCode.OK, xml)
}

internal suspend fun handleMavenMetadataChecksum(
    call: ServerCall,
    namespace: String,
    coords: MavenCoordinates,
    repoService: ArtifactRepositoryService,
) {
    val xml = buildMavenMetadataXml(namespace, coords, repoService)
    if (xml == null) {
        call.respond(HttpStatusCode.NotFound)
        return
    }
    val bytes = xml.toByteArray(Charsets.UTF_8)
    val checksumHex = when {
        coords.filename?.endsWith(".sha1") == true -> {
            val digest = java.security.MessageDigest.getInstance("SHA-1")
            digest.digest(bytes).joinToString("") { "%02x".format(it) }
        }
        coords.filename?.endsWith(".md5") == true -> {
            val digest = java.security.MessageDigest.getInstance("MD5")
            digest.digest(bytes).joinToString("") { "%02x".format(it) }
        }
        else -> {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            digest.digest(bytes).joinToString("") { "%02x".format(it) }
        }
    }
    call.response.header(HttpHeaders.ContentType, "text/plain")
    call.respond(HttpStatusCode.OK, checksumHex)
}

internal suspend fun handleMavenChecksum(
    call: ServerCall,
    namespace: String,
    coords: MavenCoordinates,
    repoService: ArtifactRepositoryService,
    @Suppress("UNUSED_PARAMETER") blobService: BlobStorageService,
) {
    val originalFilename = coords.filename?.let {
        when {
            it.endsWith(".sha1") -> it.removeSuffix(".sha1")
            it.endsWith(".sha256") -> it.removeSuffix(".sha256")
            it.endsWith(".md5") -> it.removeSuffix(".md5")
            else -> null
        }
    } ?: run {
        call.respond(HttpStatusCode.NotFound)
        return
    }

    val repo = repoService.findRepository(namespace, "${coords.groupId}.${coords.artifactId}", ArtifactType.MAVEN)
    if (repo == null) {
        call.respond(HttpStatusCode.NotFound)
        return
    }

    val version = coords.version?.let { repoService.findVersion(repo.id, it) }
    if (version == null) {
        call.respond(HttpStatusCode.NotFound)
        return
    }

    val blobs = repoService.getVersionBlobs(version.id)
    val versionBlob = blobs.find { it.filename == originalFilename }
    if (versionBlob == null) {
        call.respond(HttpStatusCode.NotFound)
        return
    }

    val filename = coords.filename
    val checksumAlgorithm = when {
        filename.endsWith(".sha1") -> "sha1"
        filename.endsWith(".md5") -> "md5"
        filename.endsWith(".sha256") -> "sha256"
        else -> "sha256"
    }

    val checksumBlob = blobs.find { it.role == "$checksumAlgorithm-${versionBlob.role}" }
    val checksumText = if (checksumBlob?.filename != null) {
        checksumBlob.filename
    } else if (checksumAlgorithm == "sha256") {
        val digest = versionBlob.digest
        if (digest.startsWith("sha256:")) digest.removePrefix("sha256:") else digest
    } else {
        call.respond(HttpStatusCode.NotFound)
        return
    }

    call.response.header(HttpHeaders.ContentType, "text/plain")
    call.respond(HttpStatusCode.OK, checksumText)
}

/**
 * Escapes XML special characters to prevent injection in generated XML documents.
 */
internal fun escapeXml(value: String): String {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}

internal fun sha256Hex(bytes: ByteArray): String = bosca.artifacts.sha256Hex(bytes)
