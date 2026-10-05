package bosca.core.models

import kotlin.uuid.Uuid

data class Profile(
    val id: Uuid,
    val name: String,
)