package bosca.bml.message.server

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The on-disk jar cache: `<dir>/<project>/<version>/<jar>`, digest-verified on fetch, immutable
 * once landed (a published version's bytes never change). Old versions stay cached AND
 * re-fetchable forever — delivered messages reference their version's assets indefinitely, so any
 * published version must remain servable, not just the active one.
 */
class MessageJarCache(
    private val dir: File,
    private val client: MessageArtifactClient,
    private val bundled: BundledMessageProjects = BundledMessageProjects.Empty,
) {
    private val resolved = ConcurrentHashMap<String, File>()
    private val fetchLock = Mutex()

    /**
     * The local jar for `(project, version)` — from cache or the service's bundled fallback, else
     * fetched from the registry (digest-verified). Throws when no source knows the version.
     */
    suspend fun jarFor(project: String, version: String): File {
        val key = "$project/$version"
        resolved[key]?.takeIf { it.isFile }?.let { return it }

        // Serialize fetches: concurrent asset/render requests for the same missing version must
        // not download it twice (publishes are rare; a single lock is plenty).
        fetchLock.withLock {
            resolved[key]?.takeIf { it.isFile }?.let { return it }
            bundled.jarFor(project, version)?.takeIf { it.isFile }?.let {
                resolved[key] = it
                return it
            }
            val info = client.versions(project).firstOrNull { it.version == version }
                ?: error("artifacts registry does not know $project@$version")
            val jar = info.jar ?: error("$project@$version carries no jar file")
            val target = File(dir, "$project/$version/${jar.filename}")
            if (!target.isFile) {
                client.download(project, version, jar.filename, jar.digest, target)
            }
            resolved[key] = target
            return target
        }
    }
}
