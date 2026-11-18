package bosca.system.ui

import bosca.bml.graphql.execute
import bosca.bml.render.RenderContext
import bosca.system.ui.graphql.SystemAccess
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal suspend fun systemAdministrator(ctx: RenderContext): Boolean =
    ctx.gql.execute(SystemAccess, Unit).security.principals.current.groups.any { it.name in setOf("administrators", "sa") }

fun systemEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
internal fun systemChoices(value: String): List<String> = Json.decodeFromString(ListSerializer(String.serializer()), value).distinct()
