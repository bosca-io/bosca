package bosca.git.dfs

import bosca.serialization.UUID
import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase
import org.eclipse.jgit.internal.storage.dfs.DfsReaderOptions
import org.eclipse.jgit.internal.storage.dfs.DfsRepository
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryBuilder
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.lib.RefDatabase

/**
 * A [DfsRepository] backed by Bosca's ObjectStorage for pack data and PostgreSQL
 * for refs and pack metadata. Stateless — multiple instances across pods can serve
 * the same repository concurrently, with Postgres providing consistency.
 */
class BoscaDfsRepository(
    builder: BoscaDfsRepositoryBuilder
) : DfsRepository(builder) {

    internal val repositoryId: UUID = builder.repositoryId
    private val objDatabase = BoscaDfsObjDatabase(
        this, builder.storageAdapter, builder.readerOptions ?: DfsReaderOptions()
    )
    private val refDatabase = BoscaDfsRefDatabase(this, builder.refAdapter)

    override fun getObjectDatabase(): DfsObjDatabase = objDatabase

    override fun getRefDatabase(): RefDatabase = refDatabase
}

/**
 * Builder for [BoscaDfsRepository]. Requires adapter implementations that bridge
 * JGit's synchronous DFS callbacks to Bosca's suspend-based ObjectStorage and
 * database layers.
 */
class BoscaDfsRepositoryBuilder : DfsRepositoryBuilder<BoscaDfsRepositoryBuilder, BoscaDfsRepository>() {

    lateinit var repositoryId: UUID
    lateinit var storageAdapter: DfsStorageAdapter
    lateinit var refAdapter: DfsRefAdapter

    override fun build(): BoscaDfsRepository {
        return BoscaDfsRepository(this)
    }
}
