package bosca.server.content

import bosca.graphql.scalars.UploadedFile
import io.netty.handler.codec.http.multipart.FileUpload
import java.io.InputStream

/**
 * Represents a single part of a multipart form data request.
 */
sealed class PartData {
    abstract val name: String?
    abstract fun dispose()

    /**
     * Represents a form field in multipart data.
     */
    class FormItem(override val name: String?, val value: String) : PartData() {
        override fun dispose() {}
    }

    interface FileItem {
        val name: String?
        val originalFileName: String?
        val contentType: String?
        val streamProvider: InputStream
        fun dispose()
    }

    /**
     * Represents a file upload in multipart data.
     */
    class FileUploadItem(
        private val data: FileUpload
    ) : PartData(), FileItem {

        override val name: String?
            get() = data.name

        override val originalFileName: String?
            get() = data.filename

        override val contentType: String?
            get() = data.contentType

        override val streamProvider: InputStream
            get() = if (data.isInMemory) {
                data.get().inputStream()
            } else {
                data.file.inputStream()
            }

        override fun dispose() {
            if (data.refCnt() > 0) data.release()
        }
    }

    class UploadedFileItem(
        private val data: UploadedFile
    ) : PartData(), FileItem {

        override val name: String?
            get() = data.name

        override val originalFileName: String?
            get() = data.name

        override val contentType: String?
            get() = data.contentType

        override val streamProvider: InputStream
            get() = data.inputStream

        override fun dispose() {
            data.close()
        }
    }
}

/**
 * Represents a complete multipart form data submission.
 */
class MultiPartData(val parts: List<PartData>) : Iterable<PartData>, AutoCloseable {
    override fun iterator(): Iterator<PartData> = parts.iterator()

    fun readAllParts(): List<PartData> = parts

    /** Executes the given [action] for each part in this multipart data. */
    inline fun forEachPart(action: (PartData) -> Unit) = parts.forEach(action)

    /** Disposes all parts, releasing any underlying Netty resources. */
    override fun close() {
        parts.forEach { it.dispose() }
    }
}
