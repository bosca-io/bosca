package bosca.bml.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.deployment.internal.Deployment
import org.gradle.deployment.internal.DeploymentHandle
import org.gradle.deployment.internal.DeploymentRegistry
import org.gradle.process.internal.ExecHandle
import org.gradle.process.internal.ExecHandleState
import org.gradle.process.internal.JavaExecHandleBuilder
import org.gradle.process.internal.JavaExecHandleFactory
import org.gradle.work.DisableCachingByDefault
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.inject.Inject

/**
 * Runs a BML application as a Gradle reloadable deployment.
 *
 * The task returns after registering the forked JVM with Gradle's deployment registry. Gradle then
 * enters continuous-build mode itself and watches the inputs of this task and its dependencies.
 * Each successful rebuild publishes an immutable application-output snapshot to a persistent JVM;
 * that JVM swaps the generated site state without restarting Netty. This is deliberately a Gradle task
 * rather than a shell watcher so it works identically from the command line and IntelliJ's Gradle
 * runner and watches the real task graph instead of a hard-coded list of source directories.
 */
@DisableCachingByDefault(because = "Starts a reloadable application process")
abstract class BmlDevTask : DefaultTask() {

    /** Runtime classpath of the application being launched. */
    @get:Classpath
    abstract val classpath: ConfigurableFileCollection

    /** Application class/resource directories copied into each isolated reload generation. */
    @get:Classpath
    abstract val reloadClasspath: ConfigurableFileCollection

    /** Application main class, normally copied from the Gradle application plugin's `run` task. */
    @get:Input
    abstract val mainClass: Property<String>

    /** Optional Java module containing [mainClass]. */
    @get:Input
    @get:Optional
    abstract val mainModule: Property<String>

    /** Whether Gradle should infer a module path when [mainModule] is present. */
    @get:Input
    abstract val inferModulePath: Property<Boolean>

    /** Arguments passed to the application's main function. */
    @get:Input
    abstract val arguments: ListProperty<String>

    /** Fully resolved JVM arguments copied from the application plugin's `run` task. */
    @get:Input
    abstract val allJvmArguments: ListProperty<String>

    /** Environment inherited from the application plugin's `run` task. It may contain secrets. */
    @get:Internal
    abstract val environmentVariables: MapProperty<String, String>

    /** Java executable selected by the application's toolchain. */
    @get:Internal
    abstract val javaExecutable: RegularFileProperty

    /** Working directory inherited from the application plugin's `run` task. */
    @get:Internal
    abstract val workingDirectory: DirectoryProperty

    /** Generated development client bundles served by `BmlServer`. */
    @get:Internal
    abstract val clientDirectory: DirectoryProperty

    /** Immutable reload generations, retained until `clean` so in-flight requests remain safe. */
    @get:Internal
    abstract val generationDirectory: DirectoryProperty

    /** Atomically replaced pointer read by the persistent launcher. */
    @get:Internal
    abstract val generationMarker: RegularFileProperty

    init {
        inferModulePath.convention(false)
        arguments.convention(emptyList())
        allJvmArguments.convention(emptyList())
        environmentVariables.convention(emptyMap())
    }

    @TaskAction
    fun startApplication() {
        val generation = publishGeneration()
        // An included build's root task path is just `:bmlDev`; prefix it with the build name so
        // one composite invocation can run several BML applications without registry collisions.
        val deploymentId = "${project.rootProject.name}$path"
        if (deploymentRegistry.get(deploymentId, BmlApplicationHandle::class.java) != null) return

        // Included builds transition to Finished before the build-session deployment registry
        // performs its initial start. Materialize task-backed values now so the later launch does
        // not try to query the already-finished included-build model.
        val reloadRoots = reloadClasspath.files.map { it.canonicalFile }.toSet()
        val resolvedClasspath = project.files(classpath.files.filter { it.canonicalFile !in reloadRoots })
        val builder = javaExecHandleFactory.newJavaExec().apply {
            executable = javaExecutable.get().asFile.absolutePath
            workingDir = workingDirectory.get().asFile
            setEnvironment(environmentVariables.get())
            setClasspath(resolvedClasspath)
            mainClass.set("bosca.bml.server.BmlDevLauncher")
            modularity.inferModulePath.set(false)
            setArgs(listOf(this@BmlDevTask.mainClass.get()) + arguments.get())
            setJvmArgs(allJvmArguments.get())
            systemProperty("bml.dev", "true")
            systemProperty("bml.dev.hotswap", "true")
            systemProperty("bml.dev.marker", generationMarker.get().asFile.absolutePath)
            systemProperty("bml.clientDir", clientDirectory.get().asFile.absolutePath)
            // The default task-operation streams close when one continuous-build cycle ends.
            // System streams belong to the whole Gradle session and remain valid across rebuilds.
            setStandardOutput(System.out)
            setErrorOutput(System.err)
            setDisplayName("BML development server ($deploymentId)")
        }
        deploymentRegistry.start(
            deploymentId,
            DeploymentRegistry.ChangeBehavior.NONE,
            BmlApplicationHandle::class.java,
            builder,
        )
        logger.lifecycle("BML application generation {} is ready", generation.take(12))
    }

    private fun publishGeneration(): String {
        val roots = reloadClasspath.files.filter { it.isDirectory }.map { it.toPath() }.sortedBy { it.toString() }
        val clientRoot = clientDirectory.get().asFile.takeIf { it.isDirectory }?.toPath()
        val digestRoots = (roots + listOfNotNull(clientRoot)).distinct()
        val digest = MessageDigest.getInstance("SHA-256")
        digestRoots.forEachIndexed { index, root ->
            Files.walk(root).use { paths ->
                paths.filter(Files::isRegularFile).sorted().forEach { file ->
                    digest.update(index.toString().toByteArray(StandardCharsets.UTF_8))
                    digest.update(0.toByte())
                    digest.update(root.relativize(file).toString().toByteArray(StandardCharsets.UTF_8))
                    digest.update(0.toByte())
                    Files.newInputStream(file).use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                    }
                }
            }
        }
        val id = digest.digest().joinToString("") { "%02x".format(it) }
        val generations = generationDirectory.get().asFile.toPath()
        val target = generations.resolve(id)
        if (!Files.isDirectory(target)) {
            Files.createDirectories(generations)
            val temporary = generations.resolve(".$id-${System.nanoTime()}")
            Files.createDirectories(temporary)
            try {
                roots.forEach { root ->
                    Files.walk(root).use { paths ->
                        paths.forEach { source ->
                            val destination = temporary.resolve(root.relativize(source).toString())
                            if (Files.isDirectory(source)) Files.createDirectories(destination)
                            else {
                                Files.createDirectories(destination.parent)
                                Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
                            }
                        }
                    }
                }
                moveAtomically(temporary, target, replace = false)
            } finally {
                if (Files.exists(temporary)) temporary.toFile().deleteRecursively()
            }
        }

        val marker = generationMarker.get().asFile.toPath()
        val markerContents = "$id\n${target.toAbsolutePath()}\n"
        if (!Files.isRegularFile(marker) || Files.readString(marker) != markerContents) {
            Files.createDirectories(marker.parent)
            val temporaryMarker = marker.resolveSibling("${marker.fileName}.tmp")
            Files.writeString(temporaryMarker, markerContents, StandardCharsets.UTF_8)
            moveAtomically(temporaryMarker, marker, replace = true)
        }
        return id
    }

    private fun moveAtomically(source: java.nio.file.Path, target: java.nio.file.Path, replace: Boolean) {
        val options = buildList {
            add(StandardCopyOption.ATOMIC_MOVE)
            if (replace) add(StandardCopyOption.REPLACE_EXISTING)
        }.toTypedArray()
        try {
            Files.move(source, target, *options)
        } catch (_: AtomicMoveNotSupportedException) {
            if (replace) Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
            else Files.move(source, target)
        }
    }

    @get:Inject
    protected abstract val deploymentRegistry: DeploymentRegistry

    @get:Inject
    protected abstract val javaExecHandleFactory: JavaExecHandleFactory
}

/** Gradle deployment handle that owns the forked BML server JVM. */
open class BmlApplicationHandle @Inject constructor(
    private val builder: JavaExecHandleBuilder,
) : DeploymentHandle {
    private var process: ExecHandle? = null

    @Synchronized
    override fun isRunning(): Boolean = process?.state == ExecHandleState.STARTED

    @Synchronized
    override fun start(deployment: Deployment) {
        val status = deployment.status()
        if (status.failure != null || isRunning()) return
        process = builder.build().start()
    }

    @Synchronized
    override fun stop() {
        process?.abort()
        process = null
    }
}
