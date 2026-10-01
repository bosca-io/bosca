package bosca.communications.mailers

interface Email {

    val name: String
    val email: String

    /**
     * Non-PII provider arguments associated with this recipient.
     *
     * Mailers that support provider callbacks attach these values to the
     * recipient's personalization so the webhook can correlate the event.
     */
    val customArguments: Map<String, String>
        get() = emptyMap()
}
