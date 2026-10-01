package bosca.core.notifications

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.security.MessageDigest

/** Grants System UI read access to bounded image files downloaded for conversation messages. */
class NotificationImageProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "Notification images are read-only" }
        val file = imageFile(checkNotNull(context), uri.lastPathSegment.orEmpty())
        require(file.isFile) { "Unknown notification image" }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? =
        URLConnection.guessContentTypeFromName(uri.lastPathSegment)

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}

internal object NotificationImageStore {
    private const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
    private const val MAX_CACHE_BYTES = 25L * 1024 * 1024
    private const val MAX_SOURCE_PIXELS = 12_000_000L
    private const val MAX_BITMAP_PIXELS = 4_000_000L
    private const val MAX_BITMAP_DIMENSION = 2_048
    private const val CONNECT_TIMEOUT_MILLIS = 2_000
    private const val READ_TIMEOUT_MILLIS = 4_000
    private const val MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000

    fun fetch(
        context: android.content.Context,
        attachment: PushAttachment,
        accessToken: String?,
        trustedUrl: String?,
    ): Uri? {
        if (attachment.type != PushAttachmentType.IMAGE) return null
        if (accessToken.isNullOrBlank()) return null
        val source = runCatching { URL(attachment.url) }.getOrNull() ?: return null
        val trusted = trustedUrl?.let { runCatching { URL(it) }.getOrNull() } ?: return null
        if (source.protocol != "https") return null
        if (source.protocol != trusted.protocol || source.host != trusted.host || source.port != trusted.port) return null
        if (!source.path.startsWith("/content/image/")) return null
        val suffix = imageSuffix(attachment.mediaType, source.path)
        val name = "${attachment.url}\n$accessToken".sha256() + suffix
        val destination = imageFile(context, name)
        prune(destination.parentFile)
        if (!destination.isFile) {
            val connection = runCatching { source.openConnection() as HttpURLConnection }.getOrNull()
                ?: return null
            try {
                connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
                connection.readTimeout = READ_TIMEOUT_MILLIS
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Authorization", "Bearer $accessToken")
                connection.setRequestProperty("Accept", "image/*")
                connection.connect()
                if (connection.responseCode !in 200..299) return null
                if (!connection.contentType.orEmpty().substringBefore(';').startsWith("image/")) return null
                val announcedLength = connection.contentLengthLong
                if (announcedLength > MAX_IMAGE_BYTES) return null
                val temporary = File(destination.parentFile, "$name.partial")
                try {
                    connection.inputStream.use { input ->
                        temporary.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var total = 0
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > MAX_IMAGE_BYTES) return null
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    if (!hasSafeDimensions(temporary)) return null
                    if (!temporary.renameTo(destination)) return null
                } finally {
                    temporary.delete()
                }
            } catch (_: Exception) {
                return null
            } finally {
                connection.disconnect()
            }
        }
        return Uri.Builder()
            .scheme("content")
            .authority("${context.packageName}.bosca.notification.images")
            .appendPath(name)
            .build()
    }

    /** Decodes a previously fetched notification image for BigPicture-style presentation. */
    fun bitmap(context: android.content.Context, uri: Uri): Bitmap? {
        val file = runCatching { imageFile(context, uri.lastPathSegment.orEmpty()) }.getOrNull() ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > MAX_BITMAP_DIMENSION ||
            bounds.outHeight / sampleSize > MAX_BITMAP_DIMENSION ||
            bounds.outWidth.toLong() / sampleSize * (bounds.outHeight / sampleSize) > MAX_BITMAP_PIXELS
        ) {
            sampleSize *= 2
        }
        return runCatching {
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sampleSize })
        }.getOrNull()
    }

    private fun hasSafeDimensions(file: File): Boolean {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        return options.outWidth > 0 && options.outHeight > 0 &&
            options.outWidth.toLong() * options.outHeight <= MAX_SOURCE_PIXELS
    }

    private fun prune(directory: File?) {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MILLIS
        val files = directory?.listFiles()?.toMutableList() ?: return
        files.filter { it.lastModified() < cutoff || it.name.endsWith(".partial") }.forEach {
            if (it.delete()) files.remove(it)
        }
        var bytes = files.sumOf(File::length)
        for (file in files.sortedBy(File::lastModified)) {
            if (bytes <= MAX_CACHE_BYTES) break
            val length = file.length()
            if (file.delete()) bytes -= length
        }
    }

    private fun imageSuffix(mediaType: String?, path: String): String = when {
        mediaType.equals("image/png", true) -> ".png"
        mediaType.equals("image/gif", true) -> ".gif"
        mediaType.equals("image/webp", true) -> ".webp"
        mediaType.equals("image/heic", true) || mediaType.equals("image/heif", true) -> ".heic"
        path.substringAfterLast('/', "").substringAfterLast('.', "")
            .lowercase() in setOf("png", "gif", "webp", "heic", "heif", "jpg", "jpeg") ->
            "." + path.substringAfterLast('.').lowercase()
        else -> ".jpg"
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }
}

private fun imageFile(context: android.content.Context, name: String): File {
    require(name.matches(Regex("[a-f0-9]{64}\\.(png|gif|webp|heic|heif|jpg|jpeg)(\\.partial)?"))) {
        "Invalid notification image name"
    }
    val directory = File(context.cacheDir, "bosca-notification-images").apply { mkdirs() }
    return File(directory, name)
}
