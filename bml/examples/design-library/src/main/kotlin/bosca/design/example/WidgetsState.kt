package bosca.design.example

import kotlinx.serialization.Serializable

/** Application-owned values for the independent rich-widget form. */
@Serializable
class WidgetsState(val id: String) {
    var document: String = """{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Consumer document"}]}]}"""
        private set
    var graph: String = """{"nodes":[{"id":"start","type":"input","position":{"x":0,"y":0},"data":{"label":"Start"}},{"id":"finish","type":"output","position":{"x":240,"y":0},"data":{"label":"Finish"}}],"edges":[]}"""
        private set
    var layout: String = """[{"id":"chart","x":0,"y":0,"w":8,"h":6},{"id":"notes","x":8,"y":0,"w":4,"h":6}]"""
        private set
    val chart: String = """{"type":"bar","data":{"labels":["Monday","Tuesday"],"datasets":[{"label":"Visits","data":[12,18]}]},"options":{"animation":false}}"""
    var saves: Int = 0
        private set
    var updates: Int = 0
        private set

    fun save(document: String, graph: String, layout: String) {
        this.document = document
        this.graph = graph
        this.layout = layout
        saves++
    }

    fun refresh() { updates++ }
}
