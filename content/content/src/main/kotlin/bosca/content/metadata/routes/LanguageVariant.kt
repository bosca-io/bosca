package bosca.content.metadata.routes

import bosca.content.metadata.service.MetadataService
import bosca.routes.Route
import bosca.routes.annotations.RouteController
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.server.ServerCall
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

@Serializable
data class IdResponse(val id: String)

@RouteController("/api/v1/content/metadata/{id}/variant/language/{language}/id")
open class LanguageVariant(
    private val metadataService: MetadataService,
) : Route<IdResponse>() {

    override fun serializer(): KSerializer<IdResponse> = IdResponse.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): IdResponse? {
        val id = UUID.parse(call.pathParameters["id"] ?: error("missing id"))
        val language = call.pathParameters["language"] ?: error("missing language")
        val newId = metadataService.getLanguageVariantById(id, language) ?: return null
        return IdResponse(newId.toString())
    }
}

@RouteController("/api/v1/metadata/{id}/variant/language/{language}/id")
class LanguageVariantLegacy(metadataService: MetadataService) : LanguageVariant(metadataService)