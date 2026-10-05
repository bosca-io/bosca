package bosca.ecommerce.graphql

import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.ecommerce.model.Product
import bosca.ecommerce.model.ProductInput
import bosca.ecommerce.service.ProductService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID

/**
 * Mutations scoped to one product (the id carried by [ProductMutation]). Because a product is backed
 * by a content Metadata document, management is gated on the **backing document's permissions** (via
 * [MetadataPermissionEvaluator]) — not a group: EDIT to edit commerce fields, MANAGE to delete. There
 * is deliberately no publish mutation — publishing happens in the content workflow and the pin
 * follows automatically.
 */
@TypeController
class ProductMutationController(
    private val productService: ProductService,
    private val metadataService: MetadataService,
    private val metadataPermissions: MetadataPermissionEvaluator,
) : GraphQLController<ProductMutation> {

    @Field
    suspend fun edit(authentication: AuthenticationContext, source: ProductMutation, input: ProductInput): Product {
        verifyMetadataPermission(authentication, source.id, PermissionAction.EDIT)
        return productService.edit(source.id, input, authentication.principal()?.id)
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, source: ProductMutation): Boolean {
        verifyMetadataPermission(authentication, source.id, PermissionAction.MANAGE)
        return productService.delete(source.id, authentication.principal()?.id)
    }

    /** Authorize against the product's backing content document at the product's pinned version. */
    private suspend fun verifyMetadataPermission(
        authentication: AuthenticationContext,
        productId: UUID,
        action: PermissionAction,
    ) {
        val product = productService.get(productId) ?: throw IllegalArgumentException("product $productId not found")
        val metadata = metadataService.getById(product.metadataId, product.metadataVersion)
            ?: throw IllegalArgumentException("metadata ${product.metadataId} not found")
        metadataPermissions.verifyAllowed(authentication, metadata, action)
    }
}
