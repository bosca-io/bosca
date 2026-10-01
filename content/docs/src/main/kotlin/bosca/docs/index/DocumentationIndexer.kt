package bosca.docs.index

import bosca.docs.model.DocumentationDocument
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class DocumentationIndexer {

    private val log = LoggerFactory.getLogger(DocumentationIndexer::class.java)
    private val json = Json { prettyPrint = true }

    data class IndexResult(
        val sourceDocs: List<SourceDocument>,
        val searchDocs: List<DocumentationDocument>,
    )

    fun parseSourceFromClasspath(): IndexResult {
        val stream = javaClass.classLoader.getResourceAsStream("docs/source-docs.json") ?: run {
            log.info("No pre-compiled source docs found on classpath at docs/source-docs.json")
            return IndexResult(emptyList(), emptyList())
        }
        val content = stream.use { it.bufferedReader().readText() }
        val sourceDocs = json.decodeFromString<List<SourceDocument>>(content)
        val searchDocs = sourceDocs.map { it.toSearchDocument() }
        log.info("Loaded {} source code documents from pre-compiled JSON", sourceDocs.size)
        return IndexResult(sourceDocs, searchDocs)
    }

    private fun SourceDocument.toSearchDocument(): DocumentationDocument {
        val id = "source-${qualifiedName}"
            .replace(".", "_")
            .replace("<", "_")
            .replace(">", "_")

        val signatureLines = methods.joinToString("\n") { it.signature }

        val descriptionText = buildString {
            if (kdoc.isNotEmpty()) append(kdoc)
            if (accessPattern.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append("Access: $accessPattern")
            }
        }.take(500)

        val contentText = buildString {
            if (supertypes.isNotEmpty()) {
                append("Supertypes: ${supertypes.joinToString(", ")}\n")
            }
            if (annotations.isNotEmpty()) {
                append("Annotations: ${annotations.joinToString(", ") { it.name }}\n")
            }
            if (enumValues.isNotEmpty()) {
                append("Enum values: ${enumValues.joinToString(", ")}\n")
            }
            if (properties.isNotEmpty()) {
                append("Properties:\n")
                for (prop in properties) {
                    append("  ${prop.name}: ${prop.type}")
                    if (prop.kdoc.isNotEmpty()) append(" - ${prop.kdoc}")
                    append("\n")
                }
            }
            if (methods.isNotEmpty()) {
                append("Methods:\n")
                for (method in methods) {
                    append("  ${method.name}")
                    if (method.returnType.isNotEmpty()) append(" -> ${method.returnType}")
                    append("\n")
                    if (method.parameters.isNotEmpty()) {
                        append("    Parameters: ${method.parameters.joinToString(", ") { "${it.name}: ${it.type}" }}\n")
                    }
                    if (method.kdoc.isNotEmpty()) {
                        append("    ${method.kdoc}\n")
                    }
                }
            }
        }

        return DocumentationDocument(
            id = id,
            name = simpleName,
            qualifiedName = qualifiedName,
            kind = kind,
            source = "source-code",
            module = module,
            pkg = pkg,
            category = category,
            signature = signatureLines,
            description = descriptionText,
            content = contentText,
        )
    }
}
