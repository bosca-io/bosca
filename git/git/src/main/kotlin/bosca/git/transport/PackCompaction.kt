package bosca.git.transport

import bosca.git.dfs.BoscaDfsObjDatabase
import org.eclipse.jgit.internal.storage.dfs.DfsPackCompactor
import org.eclipse.jgit.internal.storage.dfs.DfsRepository
import org.eclipse.jgit.lib.NullProgressMonitor
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("bosca.git.transport.PackCompaction")

/**
 * Replaces any thin packs newly written by `ReceivePack.receive(...)` with
 * self-contained equivalents via [DfsPackCompactor].
 *
 * [namesBefore] should be a snapshot of pack names taken before the receive
 * started. Any pack present after the receive but not in the snapshot was
 * written by this receive and is a candidate for compaction.
 *
 * A pack is only compacted when its `.pack` header object count disagrees
 * with its `.idx` count — the signature JGit's [DfsPackParser] leaves behind
 * when it thickens a thin pack but skips the header rewrite (see its
 * `onEndThinPack`). Already-self-contained packs are left untouched so we
 * don't do unnecessary work for clients that send thick packs.
 *
 * The compactor reads each thin pack and writes a replacement through JGit's
 * standard [PackWriter][org.eclipse.jgit.internal.storage.pack.PackWriter] —
 * which always emits a self-contained pack with a header that matches the
 * actual object count.
 *
 * Exceptions are logged, not thrown: this runs after the push's own outcome is
 * decided (and from a finally), and a failed compaction only leaves the thin
 * pack in place for a scheduled GC to pick up.
 */
internal fun compactNewlyReceivedPacks(repo: DfsRepository, namesBefore: Set<String>) {
    val repoName = repo.description.repositoryName
    // Everything, listing the packs included (a storage call once a commit cleared the cache), is
    // inside the try: this runs in a finally, where a throw would replace the push's own outcome.
    try {
        val newPacks = repo.objectDatabase.packs
            .filter { it.packDescription.packName !in namesBefore }
        if (newPacks.isEmpty()) return

        val objdb = repo.objectDatabase as? BoscaDfsObjDatabase ?: return
        val thinPacks = newPacks.filter { objdb.isThinPack(it.packDescription.packName) }
        if (thinPacks.isEmpty()) return

        val compactor = DfsPackCompactor(repo)
        for (pack in thinPacks) {
            compactor.add(pack)
            compactor.prune(pack)
        }
        compactor.compact(NullProgressMonitor.INSTANCE)
        log.info(
            "Compacted {} thin pack(s) for repository {} (of {} newly received)",
            thinPacks.size, repoName, newPacks.size
        )
    } catch (e: Exception) {
        log.warn(
            "Failed to compact newly-received thin packs for repository {}: {}",
            repoName, e.message, e
        )
    }
}
