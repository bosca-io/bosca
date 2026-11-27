package bosca.design.example

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Independent consumer-owned form contract, with no WorkOps or backend dependency. */
@Serializable
class SchemaFormState(val id: String) {
    var draft: JsonObject = JsonObject(emptyMap())
        private set
    var saved: String = ""
        private set
    val schema get() = Json.parseToJsonElement("""{"type":"object","properties":{"choice":{"type":"string"},"attachment":{"type":["string","null"]},"illustration":{"type":["string","null"]},"name":{"type":"string","minLength":2},"quantity":{"type":"integer","minimum":0,"default":0},"enabled":{"type":"boolean","default":false}},"required":["name"]}""").jsonObject
    val uiSchema get() = Json.parseToJsonElement("""{"version":1,"layout":[{"type":"section","label":"Preferences","children":[{"type":"field","property":"name","control":"text-input","label":"Name"},{"type":"field","property":"choice","control":"remote-select","label":"Remote choice"},{"type":"field","property":"attachment","control":"file-upload","label":"Attachment"},{"type":"field","property":"illustration","control":"image-upload","label":"Illustration"},{"type":"row","children":[{"type":"field","property":"quantity","control":"number-input","label":"Quantity","col":6},{"type":"field","property":"enabled","control":"checkbox","label":"Enabled","col":6}]}]}]}""").jsonObject
    fun save(attributes: String) {
        draft = Json.parseToJsonElement(attributes).jsonObject
        saved = draft.toString()
    }
}
