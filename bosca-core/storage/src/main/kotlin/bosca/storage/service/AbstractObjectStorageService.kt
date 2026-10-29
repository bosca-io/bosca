@file:OptIn(ExperimentalUuidApi::class)
package bosca.storage.service

import bosca.content.collection.model.Collection
import bosca.content.metadata.model.Metadata
import bosca.di.ObjectProvider
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.withContext

abstract class AbstractObjectStorageService(
    private val urlPrefix: String,
    private val urlUploadPrefix: String,
    private val urlSigner: ObjectProvider<UrlSigner>,
    private val securityService: ObjectProvider<SecurityService>
) : ObjectStorageService {

    protected open fun newPath(path: String): ObjectPath = StringObjectPath(path)

    override suspend fun getPath(metadata: Metadata, supplementaryId: UUID?) =
        if (supplementaryId != null) {
            newPath(path = "metadata/${metadata.id}/${metadata.version}/supplementary/${supplementaryId}")
        } else {
            newPath(path = "metadata/${metadata.id}/${metadata.version}/content")
        }

    override suspend fun getPath(collection: Collection, supplementaryId: UUID?) =
        if (supplementaryId != null) {
            newPath(path = "collection/${collection.id}/supplementary/${supplementaryId}")
        } else {
            newPath(path = "collection/${collection.id}/content")
        }

    // Reading the body blocks, so it runs on StorageDispatcher like the backends' other storage I/O,
    // never on the caller's thread (which may be a request event loop).
    override suspend fun getString(path: ObjectPath): String = withContext(StorageDispatcher) {
        getInputStream(path).use { it.bufferedReader().use { reader -> reader.readText() } }
    }

    override suspend fun getSignedDownloadUrl(
        path: ObjectPath,
        principal: AuthenticatedPrincipal?,
        metadata: Metadata,
        supplementaryId: UUID?,
        filename: Boolean,
    ): SignedUrl {
        var url = supplementaryId?.let {
            "$urlPrefix/api/v1/content/metadata/download?id=${metadata.id}&supplementaryId=$it"
        } ?: "$urlPrefix/api/v1/content/metadata/download?id=${metadata.id}"
        if (filename) {
            url += "&filename=true"
        }
        // TODO: attach some read-only attribute
        val token = principal?.let { securityService.get().createJwtToken(it.asPrincipal(), emptyMap()) }
        return SignedUrl(
            urlSigner.get().sign(url, 1800),
            token?.let {
                listOf(
                    SignedUrlHeader(
                        name = "Authorization",
                        value = "Bearer ${it.token}"
                    )
                )
            } ?: emptyList()
        )
    }

    override suspend fun getSignedUploadUrl(
        path: ObjectPath,
        principal: AuthenticatedPrincipal,
        metadata: Metadata,
        supplementaryId: UUID?
    ): SignedUrl {
        val url = supplementaryId?.let {
            "$urlUploadPrefix/api/v1/content/metadata/upload?id=${metadata.id}&supplementaryId=$it"
        } ?: "$urlUploadPrefix/api/v1/content/metadata/upload?id=${metadata.id}"
        // TODO: attach some read-only attribute, except for this upload action
        val token = securityService.get().createJwtToken(principal.asPrincipal(), emptyMap())
        return SignedUrl(
            urlSigner.get().sign(url, 500),
            listOf(
                SignedUrlHeader(
                    name = "Authorization",
                    value = "Bearer ${token.token}"
                )
            )
        )
    }

    override suspend fun getSignedDownloadUrl(
        path: ObjectPath,
        principal: AuthenticatedPrincipal?,
        collection: Collection,
        supplementaryId: UUID?,
        filename: Boolean,
    ): SignedUrl {
        var url = supplementaryId?.let {
            "$urlPrefix/files/collection/download?id=${collection.id}&supplementaryId=$it"
        } ?: "$urlPrefix/files/collection/download?id=${collection.id}"
        if (filename) {
            url += "&filename=true"
        }
        // TODO: attach some read-only attribute
        val token = principal?.let { securityService.get().createJwtToken(principal.asPrincipal(), emptyMap()) }
        return SignedUrl(
            urlSigner.get().sign(url, 500),
            token?.let {
                listOf(
                    SignedUrlHeader(
                        name = "Authorization",
                        value = "Bearer ${it.token}"
                    )
                )
            } ?: emptyList()
        )
    }

    override suspend fun getSignedUploadUrl(
        path: ObjectPath,
        principal: AuthenticatedPrincipal,
        collection: Collection,
        supplementaryId: UUID?
    ): SignedUrl {
        val url = supplementaryId?.let {
            "$urlUploadPrefix/files/collection/upload?id=${collection.id}&supplementaryId=$it"
        } ?: "$urlUploadPrefix/files/collection/upload?id=${collection.id}"
        // TODO: attach some read-only attribute, except for this upload
        val token = securityService.get().createJwtToken(principal.asPrincipal(), emptyMap())
        return SignedUrl(
            urlSigner.get().sign(url, 500),
            listOf(
                SignedUrlHeader(
                    name = "Authorization",
                    value = "Bearer ${token.token}"
                )
            )
        )
    }
}