package bosca.git.service

import org.eclipse.jgit.errors.MissingObjectException
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository
import org.eclipse.jgit.lib.AbbreviatedObjectId
import org.eclipse.jgit.lib.AnyObjectId
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectInserter
import org.eclipse.jgit.lib.ObjectLoader
import org.eclipse.jgit.lib.ObjectReader
import org.eclipse.jgit.lib.Repository

/**
 * Keeps preview trees, merged blobs and recursive merge bases in a disposable repository.
 * Readers can see both temporary objects and the original repository without writing to it.
 */
internal class MergePreviewInserter(private val source: Repository) : ObjectInserter.Filter() {
    private val temporary = InMemoryRepository(DfsRepositoryDescription())
    private val inserter = temporary.newObjectInserter()

    override fun delegate(): ObjectInserter = inserter

    override fun newReader(): ObjectReader {
        val preview = inserter.newReader()
        val original = source.newObjectReader()
        return object : ObjectReader() {
            override fun newReader(): ObjectReader = this@MergePreviewInserter.newReader()

            override fun resolve(id: AbbreviatedObjectId): Collection<ObjectId> =
                (preview.resolve(id) + original.resolve(id)).toSet()

            override fun open(objectId: AnyObjectId, typeHint: Int): ObjectLoader =
                try {
                    preview.open(objectId, typeHint)
                } catch (_: MissingObjectException) {
                    original.open(objectId, typeHint)
                }

            override fun getShallowCommits() = original.shallowCommits

            override fun getCreatedFromInserter(): ObjectInserter = this@MergePreviewInserter

            override fun close() {
                try {
                    preview.close()
                } finally {
                    original.close()
                }
            }
        }
    }

    override fun close() {
        try {
            inserter.close()
        } finally {
            temporary.close()
        }
    }
}
