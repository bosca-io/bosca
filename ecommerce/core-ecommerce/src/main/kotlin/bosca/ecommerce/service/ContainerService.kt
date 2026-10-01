package bosca.ecommerce.service

import bosca.ecommerce.model.Container
import bosca.ecommerce.model.ContainerInput
import bosca.serialization.UUID
import bosca.service.Service

/** Shipping containers — a company's catalog of box types the packer fills. */
interface ContainerService : Service {

    suspend fun get(id: UUID): Container?

    suspend fun getByCompany(companyId: UUID): List<Container>

    suspend fun add(input: ContainerInput, principalId: UUID?): Container

    suspend fun edit(id: UUID, input: ContainerInput, principalId: UUID?): Container

    /** Soft-delete a container. Returns false if it is absent. */
    suspend fun delete(id: UUID, principalId: UUID?): Boolean
}
