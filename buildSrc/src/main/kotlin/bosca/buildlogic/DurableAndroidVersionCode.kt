package bosca.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction

private const val MAX_ANDROID_VERSION_CODE = 2_100_000_000

fun isAndroidReleaseTask(taskName: String): Boolean {
    val normalized = taskName.lowercase()
    val task = normalized.substringAfterLast(':')
    if ("release" !in task) return false

    val projectPath = normalized.substringBeforeLast(':', missingDelimiterValue = "")
    return projectPath.isEmpty() || projectPath.endsWith(":android")
}

fun resolveDurableAndroidVersionCode(raw: String?, requestedTasks: List<String>): Int {
    val durable = raw?.let { value ->
        value.toIntOrNull()?.takeIf { it in 1..MAX_ANDROID_VERSION_CODE }
            ?: error(
                "ANDROID_VERSION_CODE must be an integer between 1 and $MAX_ANDROID_VERSION_CODE, got '$value'",
            )
    }
    val requestedRelease = requestedTasks.any(::isAndroidReleaseTask)
    if (requestedRelease && durable == null) {
        error("Android release builds require ANDROID_VERSION_CODE from uses: allocate-build-number")
    }
    return durable ?: 1
}

abstract class ValidateDurableAndroidVersionCode : DefaultTask() {
    @get:Input
    @get:Optional
    abstract val versionCode: Property<String>

    @TaskAction
    fun validate() {
        resolveDurableAndroidVersionCode(versionCode.orNull, listOf("release"))
    }
}
