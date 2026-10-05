package bosca.security.graphql

import bosca.security.model.CredentialType

internal fun CredentialType.isPasswordCredential(): Boolean =
    this == CredentialType.PASSWORD || this == CredentialType.PASSWORD_SCRYPT
