package bosca.git.dfs

import org.eclipse.jgit.internal.storage.dfs.DfsRefDatabase
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectIdRef
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.ReflogReader
import org.eclipse.jgit.lib.SymbolicRef
import org.eclipse.jgit.util.RefList

/**
 * [DfsRefDatabase] implementation backed by the `git.dfs_refs` PostgreSQL table.
 * Ref updates use compare-and-swap semantics via row-level locking, enabling
 * safe concurrent pushes from multiple git-server pods without POSIX file locks.
 */
class BoscaDfsRefDatabase(
    private val repo: BoscaDfsRepository,
    private val refAdapter: DfsRefAdapter
) : DfsRefDatabase(repo) {

    override fun scanAllRefs(): RefCache {
        val refs = refAdapter.scanRefs(repo.repositoryId)
        val idBuilder = RefList.Builder<Ref>()
        val symBuilder = RefList.Builder<Ref>()

        val refMap = refs.associateBy { it.name }

        for (info in refs.sortedBy { it.name }) {
            val ref = toRef(info, refMap)
            idBuilder.add(ref)
            if (ref.isSymbolic) {
                symBuilder.add(ref)
            }
        }

        return RefCache(idBuilder.toRefList(), symBuilder.toRefList())
    }

    override fun compareAndPut(oldRef: Ref, newRef: Ref): Boolean {
        val name = newRef.name
        val expectedOldId = if (oldRef.storage == Ref.Storage.NEW) null else oldRef.objectId?.name()
        val newId = newRef.objectId?.name() ?: ObjectId.zeroId().name()
        val peeledId = newRef.peeledObjectId?.name()
        val symbolicTarget = if (newRef.isSymbolic) newRef.target?.name else null

        return refAdapter.compareAndPut(
            repo.repositoryId, name, expectedOldId, newId, peeledId, symbolicTarget
        )
    }

    override fun getReflogReader(ref: Ref): ReflogReader? = null

    override fun compareAndRemove(oldRef: Ref): Boolean {
        val expectedOldId = oldRef.objectId?.name()
            ?: return false
        return refAdapter.compareAndRemove(repo.repositoryId, oldRef.name, expectedOldId)
    }

    private fun toRef(info: DfsRefInfo, refMap: Map<String, DfsRefInfo>): Ref {
        if (info.symbolicTarget != null) {
            val target = refMap[info.symbolicTarget]
            val targetRef = if (target != null) {
                ObjectIdRef.PeeledNonTag(Ref.Storage.PACKED, target.name, ObjectId.fromString(target.objectId))
            } else {
                ObjectIdRef.Unpeeled(Ref.Storage.NEW, info.symbolicTarget, null)
            }
            return SymbolicRef(info.name, targetRef)
        }
        val objectId = ObjectId.fromString(info.objectId)
        return if (info.peeledId != null) {
            ObjectIdRef.PeeledTag(Ref.Storage.PACKED, info.name, objectId, ObjectId.fromString(info.peeledId))
        } else {
            ObjectIdRef.PeeledNonTag(Ref.Storage.PACKED, info.name, objectId)
        }
    }
}
