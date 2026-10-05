@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.security

import bosca.serialization.UUID
import kotlin.uuid.ExperimentalUuidApi

/**
 * Conventions for the three project-scoped groups created on every
 * [bosca.localization.model.LocalizationProject] insert.
 *
 * Names are stable across versions because the server uses them for permission
 * lookups when resolving "who can edit translations in this project"; renaming
 * them would break existing ACLs.
 */
object ProjectGroupNames {

    /** Read-only access (VIEW permission). */
    fun viewer(projectId: UUID): String = "translator-viewer:$projectId"

    /** Translation authoring access (EDIT permission). */
    fun contributor(projectId: UUID): String = "translator-contributor:$projectId"

    /** Project administration access (MANAGE permission). */
    fun manager(projectId: UUID): String = "translator-manager:$projectId"

    /** All three role group names for [projectId], in escalating permission order. */
    fun all(projectId: UUID): List<String> = listOf(viewer(projectId), contributor(projectId), manager(projectId))
}
