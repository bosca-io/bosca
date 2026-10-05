@file:OptIn(ExperimentalUuidApi::class)

package bosca.storage.service

import bosca.content.collection.model.Collection
import bosca.content.metadata.model.Metadata
import bosca.security.model.AuthenticatedPrincipal
import bosca.serialization.UUID
import java.io.InputStream
import kotlin.uuid.ExperimentalUuidApi

class NoOpObjectStorageService : ObjectStorageService {

    override suspend fun getPath(metadata: Metadata, supplementaryId: UUID?): ObjectPath {
        TODO("Not yet implemented")
    }

    override suspend fun getPath(collection: Collection, supplementaryId: UUID?): ObjectPath {
        TODO("Not yet implemented")
    }

    override suspend fun getString(path: ObjectPath): String {
        TODO("Not yet implemented")
    }

    override suspend fun getInputStream(path: ObjectPath): InputStream {
        TODO("Not yet implemented")
    }

    override suspend fun getInputStreamRange(path: ObjectPath, range: LongRange): InputStream {
        TODO("Not yet implemented")
    }

    override suspend fun setInputStream(path: ObjectPath, stream: InputStream, length: Long?): Long {
        TODO("Not yet implemented")
    }

    override suspend fun delete(path: ObjectPath) {
        TODO("Not yet implemented")
    }

    override suspend fun getSignedDownloadUrl(path: ObjectPath, principal: AuthenticatedPrincipal?, metadata: Metadata, supplementaryId: UUID?, filename: Boolean): SignedUrl {
        TODO("Not yet implemented")
    }

    override suspend fun getSignedUploadUrl(
        path: ObjectPath,
        principal: AuthenticatedPrincipal,
        metadata: Metadata,
        supplementaryId: UUID?
    ): SignedUrl {
        TODO("Not yet implemented")
    }

    override suspend fun getSignedDownloadUrl(path: ObjectPath, principal: AuthenticatedPrincipal?, collection: Collection, supplementaryId: UUID?, filename: Boolean): SignedUrl {
        TODO("Not yet implemented")
    }

    override suspend fun getSignedUploadUrl(
        path: ObjectPath,
        principal: AuthenticatedPrincipal,
        collection: Collection,
        supplementaryId: UUID?
    ): SignedUrl {
        TODO("Not yet implemented")
    }
}