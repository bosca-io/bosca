package bosca.git.transport

import org.eclipse.jgit.internal.storage.dfs.DfsRepository
import org.eclipse.jgit.storage.pack.PackConfig
import org.eclipse.jgit.transport.UploadPack

/**
 * Creates pre-configured [UploadPack] instances with partial clone support
 * and pack generation settings tuned for repositories containing large binary
 * files (images, assets).
 */
object UploadPackFactory {

    private val packConfig = PackConfig().apply {
        setBigFileThreshold(BIG_FILE_THRESHOLD)
        setThreads(PACK_THREADS)
    }

    fun create(repo: DfsRepository): UploadPack {
        val uploadPack = UploadPack(repo)
        uploadPack.setBiDirectionalPipe(false)
        uploadPack.setExtraParameters(listOf("version=2"))
        uploadPack.setPackConfig(packConfig)
        uploadPack.setRequestPolicy(UploadPack.RequestPolicy.ANY)
        return uploadPack
    }

    private const val BIG_FILE_THRESHOLD = 2 * 1024 * 1024
    private const val PACK_THREADS = 4
}
