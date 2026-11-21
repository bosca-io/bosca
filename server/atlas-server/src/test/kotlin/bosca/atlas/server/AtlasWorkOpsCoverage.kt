package bosca.atlas.server

import java.io.File
import java.util.UUID

/** Records the imported WorkOps UI's JVM execution in each isolated application process. */
internal fun atlasWorkOpsCoverageArguments(directory: File, name: String): List<String> {
    val agent = System.getProperty("atlas.workops.coverage.agent").orEmpty()
    if (agent.isBlank()) return emptyList()
    require(File(agent).isFile) { "The WorkOps JVM coverage agent is unavailable: $agent" }
    val destination = System.getProperty("atlas.workops.coverage.directory").orEmpty()
    require(destination.isNotBlank()) { "Set atlas.workops.coverage.directory when requesting JVM coverage." }
    val reports = File(destination).apply { check(mkdirs() || isDirectory) }
    val identity = "${directory.name}-$name-${UUID.randomUUID()}"
    val settings = File(directory, "$identity.args")
    settings.writeText("report.file=${File(reports, "$identity.ic").absolutePath}\n" +
        "include=bosca.workops.ui.*\nexclude=bosca.workops.ui.generated.*\nexclude=bosca.workops.ui.graphql.*\n")
    return listOf("-javaagent:${File(agent).absolutePath}=file:${settings.absolutePath}")
}

/** A completed agent save removes its lock; a killed save must not become partial evidence. */
internal fun atlasValidateWorkOpsCoverage(directory: File) {
    if (System.getProperty("atlas.workops.coverage.agent").isNullOrBlank()) return
    for (settings in directory.listFiles().orEmpty().filter { it.extension == "args" }) {
        val report = File(settings.useLines { it.first().removePrefix("report.file=") })
        check(report.isFile && report.length() > 0 && !File(report.absolutePath + ".lck").exists()) {
            "WorkOps process coverage did not finish writing: $report"
        }
    }
}
