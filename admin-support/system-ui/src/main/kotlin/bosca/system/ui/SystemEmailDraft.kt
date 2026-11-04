package bosca.system.ui

import kotlinx.serialization.Serializable

@Serializable
data class SystemEmailDraft(
    var provider: String = "deployment",
    var host: String = "", var port: String = "587", var security: String = "STARTTLS",
    var username: String = "", var password: String = "", var clearPassword: Boolean = false,
    var apiKey: String = "", var clearApiKey: Boolean = false,
    var verificationKey: String = "", var clearVerificationKey: Boolean = false,
    var domain: String = "", var apiBaseUrl: String = "https://api.mailgun.net",
)
