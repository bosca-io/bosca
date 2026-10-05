package bosca.storage.service

import kotlinx.serialization.Serializable

@Serializable
data class SignedUrlHeader(val name: String, val value: String)

@Serializable
data class SignedUrl(val url: String, val headers: List<SignedUrlHeader>)
