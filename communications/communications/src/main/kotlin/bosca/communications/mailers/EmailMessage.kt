package bosca.communications.mailers

interface EmailMessage {
    val from: Email
    val to: List<Email>
    val subject: String
    val content: List<Content>
}