package bosca.design.catalog

import bosca.design.model.SchemaFormOption
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Catalog-owned schemas and submission behavior around the shared Forms renderer. */
@Serializable
class CatalogSchemaForm(val id: String) {
    var draft: JsonObject = JsonObject(emptyMap())
        private set
    var message: String = ""
        private set
    var failed: Boolean = false
        private set
    var saves: Int = 0
        private set
    val schema: JsonObject get() = Json.parseToJsonElement("""{
      "type":"object","properties":{"choice":{"type":"string"},"attachment":{"type":["string","null"]},"illustration":{"type":["string","null"]},
        "title":{"type":"string","minLength":3,"default":"Example note"},
        "owner":{"type":"string"},"count":{"type":"integer","minimum":0,"default":0},
        "active":{"type":"boolean","default":false},"date":{"type":"string","format":"date"},
        "tags":{"type":"array","items":{"type":"string"},"default":["planning"]},
        "settings":{"type":"object","default":{"enabled":true}}
      },"required":["title","owner","date"]
    }""").jsonObject
    val uiSchema: JsonObject get() = Json.parseToJsonElement("""{
      "version":1,"layout":[{"type":"display","control":"help-text","text":"Use reject as the title to try a failed submission."},
        {"type":"tabs","children":[{"type":"tab","label":"Details","children":[
          {"type":"field","property":"title","control":"text-input","label":"Title"},
          {"type":"row","children":[{"type":"field","property":"owner","control":"select","label":"Owner","col":6},{"type":"field","property":"count","control":"number-input","label":"Count","col":6}]},
          {"type":"field","property":"active","control":"switch","label":"Active"},{"type":"field","property":"choice","control":"remote-select","label":"Remote choice"},{"type":"field","property":"attachment","control":"file-upload","label":"Attachment"},{"type":"field","property":"illustration","control":"image-upload","label":"Illustration"}
        ]},{"type":"tab","label":"Options","children":[
          {"type":"field","property":"date","control":"date-picker","label":"Date"},
          {"type":"field","property":"tags","control":"tag-input","label":"Tags"},
          {"type":"field","property":"settings","control":"json-editor","label":"Settings"}
        ]}]}]
    }""").jsonObject
    val options get() = mapOf(
        "owner" to listOf(SchemaFormOption("one", "First owner"), SchemaFormOption("two", "Second owner")),
        "tags" to listOf(SchemaFormOption("planning", "Planning"), SchemaFormOption("delivery", "Delivery")),
    )

    suspend fun save(attributes: String) {
        draft = Json.parseToJsonElement(attributes).jsonObject
        delay(250)
        failed = draft["title"]?.jsonPrimitive?.content?.lowercase() == "reject"
        message = if (failed) "The example rejected this submission. Your draft is retained." else "Form saved (${++saves})."
    }
}
