package bosca.git.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

/**
 * Controls who can discover and access a repository without explicit permission grants.
 *
 * [PUBLIC] repositories are visible to unauthenticated users for clone/fetch.
 * [INTERNAL] repositories appear in listings for authenticated users but require
 * a permission grant for access. [PRIVATE] repositories are invisible without an
 * explicit grant.
 */
@DbMapper(VisibilityMapper::class)
@Serializable
enum class Visibility {
    PUBLIC,
    INTERNAL,
    PRIVATE
}

object VisibilityMapper : EnumMapper<Visibility>({ Visibility.valueOf(it.uppercase()) })
