package bosca.scripting.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

@DbMapper(ScriptTypeMapper::class)
@Serializable
enum class ScriptType {
    GENERAL,
    TRIGGER,
    TOOL,
    EPHEMERAL,
    API
}

object ScriptTypeMapper : EnumMapper<ScriptType>({ ScriptType.valueOf(it.uppercase()) })
