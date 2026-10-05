package bosca.scripting.engine

import java.io.File

/**
 * Test helper that uses java.io.File internally.
 * Used to verify that scripts can call services that depend on denied classes,
 * even though scripts cannot use those classes directly.
 */
class FileUsingService {

    fun getTempDirPath(): String {
        return File(System.getProperty("java.io.tmpdir")).absolutePath
    }
}
