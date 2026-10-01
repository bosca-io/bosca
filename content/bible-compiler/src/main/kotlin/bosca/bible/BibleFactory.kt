package bosca.bible

import java.io.InputStream

interface BibleFactory {

    suspend fun getBibles(filename: String): List<IBible>

    suspend fun getBibles(inputStream: InputStream): List<IBible>
}