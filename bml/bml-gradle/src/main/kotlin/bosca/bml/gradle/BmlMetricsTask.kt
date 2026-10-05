package bosca.bml.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * `bmlMetrics`: reports the compiled site's per-page first-load payload — the global
 * tier, per-component CSS chunks, and island JS bundles — in raw and gzip bytes, so size changes
 * are measurable build over build. Logs a table and writes `build/reports/bml/site-metrics.json`
 * for machine comparison (CI trend lines, framework A/B measurements).
 *
 * Page HTML is intentionally absent here: it depends on live data, so it is measured against a
 * running site with `bosca bml audit` instead.
 */
abstract class BmlMetricsTask : DefaultTask() {

    /** The compiler-written metrics directory (`manifest.tsv` + `css/<tag>.css` chunks). */
    @get:Internal
    abstract val metricsDir: DirectoryProperty

    /** The bundled island JS directory (`bmlBundleClient` output). */
    @get:Internal
    abstract val jsDir: DirectoryProperty

    /** The production bundles (`js-prod`). When present, the report prices the production shape. */
    @get:Internal
    abstract val prodJsDir: DirectoryProperty

    /** The site's every-page tier (e.g. the global stylesheet) — `bml { metricsGlobalAssets }`. */
    @get:Internal
    abstract val globalAssets: ConfigurableFileCollection

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun report() {
        val manifest = metricsDir.get().file(MANIFEST_FILE_NAME).asFile
        if (!manifest.isFile) {
            logger.lifecycle("No BML metrics manifest at $manifest — compile the site first (compileKotlin).")
            return
        }
        val entries = BmlSiteMetrics.parseManifest(manifest.readText())
        val report = BmlSiteMetrics.report(
            entries = entries,
            jsDir = jsDir.orNull?.asFile,
            cssDir = metricsDir.get().dir(CSS_DIR_NAME).asFile,
            globalAssets = globalAssets.files.sortedBy { it.name },
            prodJsDir = prodJsDir.orNull?.asFile,
        )
        logger.lifecycle(BmlSiteMetrics.renderTable(report))
        val out = reportFile.get().asFile
        out.parentFile?.mkdirs()
        out.writeText(BmlSiteMetrics.renderJson(report))
        logger.lifecycle("JSON report: $out")
    }
}

/** Mirrors `BmlMetricsManifest.FILE_NAME` in bml-compiler (not a compile dependency of this plugin). */
internal const val MANIFEST_FILE_NAME: String = "manifest.tsv"

/** Mirrors `BmlMetricsManifest.CSS_DIR` in bml-compiler. */
internal const val CSS_DIR_NAME: String = "css"
