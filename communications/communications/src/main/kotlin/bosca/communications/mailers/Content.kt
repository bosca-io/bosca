package bosca.communications.mailers

enum class ContentType {
    TEXT,
    HTML
}

interface Content {

    val content: String
}