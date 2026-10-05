package bosca.bml.server

import org.slf4j.LoggerFactory
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URL
import java.net.URLClassLoader

/**
 * Persistent JVM entry point used by the Gradle `bmlDev` task.
 *
 * Each successful build publishes an immutable application-output snapshot. The launcher loads
 * that snapshot in a fresh child-first classloader and invokes the site's real main function.
 * [BmlServer.start] publishes the resulting configuration into the already-running Netty server.
 */
object BmlDevLauncher {
    const val MARKER_PROPERTY: String = "bml.dev.marker"

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isNotEmpty()) { "bmlDev requires the application main class as its first argument" }
        val applicationMain = args.first()
        val applicationArgs = args.drop(1).toTypedArray()
        val marker = File(checkNotNull(System.getProperty(MARKER_PROPERTY)) {
            "Missing -D$MARKER_PROPERTY"
        })

        var observedDigest: String? = null
        var currentLoader: ReloadClassLoader? = null
        while (!Thread.currentThread().isInterrupted) {
            val generation = readGeneration(marker)
            if (generation == null || generation.digest == observedDigest) {
                Thread.sleep(POLL_MILLIS)
                continue
            }
            observedDigest = generation.digest
            val loader = ReloadClassLoader(arrayOf(generation.directory.toURI().toURL()), BmlDevLauncher::class.java.classLoader)
            BmlHotSwapTransaction.begin()
            try {
                invokeMain(loader, applicationMain, applicationArgs)
                BmlHotSwapTransaction.commit()
                // Do not explicitly close the old loader: in-flight requests may still be using
                // its generated renderers. It becomes collectible once those requests and the old
                // atomic server state release it.
                currentLoader = loader
                log.info("bmlDev loaded application generation {}", generation.digest.take(12))
            } catch (e: Throwable) {
                BmlHotSwapTransaction.rollback()
                loader.close()
                log.error(
                    "bmlDev rejected application generation {}; the previous generation remains active",
                    generation.digest.take(12),
                    e,
                )
            }
        }
        currentLoader?.close()
    }

    private fun invokeMain(loader: ClassLoader, mainClass: String, args: Array<String>) {
        val previous = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = loader
        try {
            val method = Class.forName(mainClass, true, loader).getMethod("main", Array<String>::class.java)
            try {
                method.invoke(null, args)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } finally {
            Thread.currentThread().contextClassLoader = previous
        }
    }

    private fun readGeneration(marker: File): Generation? = try {
        if (!marker.isFile) return null
        val lines = marker.readLines()
        if (lines.size < 2) return null
        val directory = File(lines[1])
        if (!directory.isDirectory) return null
        Generation(lines[0], directory)
    } catch (_: java.io.IOException) {
        // The marker is atomically replaced, but tolerate a concurrent filesystem hiccup.
        null
    }

    private data class Generation(val digest: String, val directory: File)

    private class ReloadClassLoader(urls: Array<URL>, parent: ClassLoader) : URLClassLoader(urls, parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
            var loaded = findLoadedClass(name)
            if (loaded == null) {
                loaded = try {
                    findClass(name)
                } catch (_: ClassNotFoundException) {
                    super.loadClass(name, false)
                }
            }
            if (resolve) resolveClass(loaded)
            loaded
        }

        override fun getResource(name: String): URL? = findResource(name) ?: parent.getResource(name)

        override fun getResources(name: String): java.util.Enumeration<URL> {
            val resources = buildList {
                addAll(findResources(name).toList())
                addAll(parent.getResources(name).toList())
            }
            return java.util.Collections.enumeration(resources)
        }
    }

    private const val POLL_MILLIS = 100L
    private val log = LoggerFactory.getLogger(BmlDevLauncher::class.java)
}
