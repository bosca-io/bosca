package bosca.system.ui

import kotlinx.serialization.Serializable
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Serializable
data class SystemTokenDraft(
    val name: String = "", val description: String = "", val scopes: List<String> = emptyList(),
    val owner: String = "", val expires: String = OffsetDateTime.now(ZoneOffset.UTC).plusDays(90).toLocalDateTime().withSecond(0).withNano(0).toString(),
)
