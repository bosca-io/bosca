package bosca.security.model

/**
 * An entity whose access is governed by permission checks and visibility flags.
 *
 * The `public*` flags control unauthenticated access at different granularities,
 * while lifecycle flags ([isPublished], [isDeleted]) affect whether the entity
 * appears in normal queries.
 *
 * @param ID the primary key type of this entity
 */
interface PermissibleEntity<ID> {

    /** The unique identifier for this entity. */
    val id: ID

    /** Whether the entity itself is visible to unauthenticated users. */
    val public: Boolean

    /** Whether the entity's content is visible to unauthenticated users. */
    val publicContent: Boolean

    /** Whether the entity appears in public listing queries. */
    val publicList: Boolean

    /** Whether supplementary data (e.g. metadata attributes) is publicly accessible. */
    val publicSupplementary: Boolean

    /** Whether the entity has been published (i.e. is in a published workflow state). */
    val isPublished: Boolean

    /** Whether the entity is marked as advertised / promoted. */
    val isAdvertised: Boolean

    /** Whether the entity has been soft-deleted. */
    val isDeleted: Boolean
}
