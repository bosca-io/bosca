package bosca.graphql.scalars

import bosca.graphql.language.Value
import bosca.graphql.server.Coercing
import bosca.graphql.server.CoercingException
import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import kotlinx.serialization.json.JsonElement

data class UploadedFile(
    val name: String?,
    val contentType: String?,
    val inputStream: InputStream,
) : Closeable {
    override fun close() {
        inputStream.close()
    }
}

object Upload {
    val Type: Coercing = object : Coercing {
        override fun serialize(value: Any?): JsonElement =
            throw CoercingException("'Upload' is an input-only type and cannot be returned")

        override fun parseValue(input: Any?): Any = when (input) {
            is UploadedFile -> input
            is CompletableFuture<*> -> {
                if (!input.isDone) throw CoercingException("UploadedFile CompletableFuture is not yet complete")
                try {
                    input.getNow(null) as? UploadedFile
                        ?: throw CoercingException("Expected UploadedFile but got: ${input.getNow(null)}")
                } catch (e: CoercingException) {
                    throw e
                } catch (e: Exception) {
                    throw CoercingException("Failed to get UploadedFile from CompletableFuture: ${e.message}")
                }
            }
            else -> throw CoercingException("Expected UploadedFile but got: $input")
        }

        override fun parseLiteral(literal: Value): Any =
            throw CoercingException("'Upload' cannot appear as a literal; pass it as a variable")
    }
}
