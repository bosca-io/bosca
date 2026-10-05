package bosca.security.routes.passkey

import com.webauthn4j.WebAuthnManager
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.verifier.attestation.statement.androidkey.AndroidKeyAttestationStatementVerifier
import com.webauthn4j.verifier.attestation.statement.none.NoneAttestationStatementVerifier
import com.webauthn4j.verifier.attestation.statement.packed.PackedAttestationStatementVerifier
import com.webauthn4j.verifier.attestation.statement.u2f.FIDOU2FAttestationStatementVerifier
import com.webauthn4j.anchor.TrustAnchorRepository
import com.webauthn4j.data.attestation.authenticator.AAGUID
import com.webauthn4j.verifier.attestation.trustworthiness.certpath.DefaultCertPathTrustworthinessVerifier
import com.webauthn4j.verifier.attestation.trustworthiness.self.DefaultSelfAttestationTrustworthinessVerifier

/**
 * Application-wide singleton providing a properly configured [WebAuthnManager] and shared
 * [ObjectConverter] for WebAuthn registration and authentication ceremonies.
 *
 * The manager accepts `none` attestation (matching the client-side `attestation: 'none'` policy)
 * as well as `packed`, `fido-u2f`, and `android-key` attestation formats, because some
 * authenticators send attestation statements even when the relying party requests none.
 *
 * The [ObjectConverter] (which includes CBOR and JSON converters) is expensive to construct,
 * so it is shared across all ceremony invocations rather than created per request.
 */
object WebAuthnManagerFactory {

    /** Shared CBOR/JSON converter used for serializing and deserializing WebAuthn data structures. */
    val objectConverter: ObjectConverter = ObjectConverter()

    /** Pre-configured WebAuthn manager that validates attestation and assertion responses. */
    val webAuthnManager: WebAuthnManager = run {
        val emptyTrustAnchorRepository = object : TrustAnchorRepository {
            override fun find(aaguid: AAGUID): Set<java.security.cert.TrustAnchor> = emptySet()
            override fun find(bytes: ByteArray): Set<java.security.cert.TrustAnchor> = emptySet()
        }
        val certPathVerifier = DefaultCertPathTrustworthinessVerifier(emptyTrustAnchorRepository)
        val selfAttestationVerifier = DefaultSelfAttestationTrustworthinessVerifier()

        val attestationStatementVerifiers = listOf(
            NoneAttestationStatementVerifier(),
            PackedAttestationStatementVerifier(),
            FIDOU2FAttestationStatementVerifier(),
            AndroidKeyAttestationStatementVerifier(),
        )

        WebAuthnManager(
            attestationStatementVerifiers,
            certPathVerifier,
            selfAttestationVerifier,
            objectConverter,
        )
    }
}
