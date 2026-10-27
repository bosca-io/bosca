package bosca.bml.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.time.Instant

/** Keeps a local application's identity until clean; fresh builds receive their own identity. */
@DisableCachingByDefault(because = "An application build identity must not be restored from another build")
abstract class BmlBuildIdTask : DefaultTask() {
    @get:OutputFile
    abstract val identityFile: RegularFileProperty

    @TaskAction
    fun writeIdentity() {
        val file = identityFile.get().asFile
        if (!file.exists()) {
            file.parentFile.mkdirs()
            file.writeText(Instant.now().toString())
        }
    }
}
