package bosca.core.models

import kotlin.uuid.Uuid

data class Group(
    val id: Uuid,
    val name: String,
    val description: String,
)