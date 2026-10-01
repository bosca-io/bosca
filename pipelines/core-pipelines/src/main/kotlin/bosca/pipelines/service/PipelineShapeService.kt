package bosca.pipelines.service

import bosca.pipelines.model.PipelineNamedShape
import bosca.pipelines.node.ShapeField
import bosca.service.Service

/** CRUD for reusable [PipelineNamedShape]s — named object shapes an Input/Output references as a type. */
interface PipelineShapeService : Service {
    /** All named shapes, for the editor's type pickers and Type Browser. */
    suspend fun list(): List<PipelineNamedShape>

    /** The shape named [name], or null if none. */
    suspend fun getByName(name: String): PipelineNamedShape?

    /** Create or replace the shape [name] with [fields]. Returns the saved shape. */
    suspend fun save(name: String, fields: List<ShapeField>): PipelineNamedShape

    /** Remove the shape [name] (no-op if absent). */
    suspend fun delete(name: String)
}
