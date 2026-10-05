use base64::Engine;
use moka::future::Cache;
use rand::RngCore;
use serde::Deserialize;
use sha2::{Digest, Sha256};

use super::{AuthError, AuthenticatedUser};
use crate::config::GatewayAuthMethod;

/// OAuth2 + OpenID Connect client implementing the Authorization Code
/// flow with PKCE, nonce, and id_token validation.
///
/// Per-request pending-state records are kept in a TTL cache: when we
/// generate the redirect URL we stash the random `state`, `nonce`,
/// PKCE `code_verifier`, and the original return URI. On callback we
/// look the state up (rejecting unknown / replayed states), validate
/// the id_token's nonce against the stored one, and redirect to the
/// stored URI (not a user-supplied query parameter).
#[derive(Clone)]
pub struct OAuth2Handler {
    client_id: String,
    client_secret: String,
    authorization_endpoint: String,
    token_endpoint: String,
    userinfo_endpoint: String,
    redirect_uri: String,
    scopes: Vec<String>,
    default_post_login_path: String,
    http_client: reqwest::Client,
    pending: Cache<String, PendingFlow>,
    pending_ttl_secs: u64,
}

#[derive(Clone, Debug)]
pub struct PendingFlow {
    pub nonce: String,
    pub code_verifier: String,
    pub return_path: String,
}

#[derive(Clone, Debug)]
pub struct AuthorizationStart {
    pub url: String,
    pub state: String,
}

#[derive(Deserialize)]
struct TokenResponse {
    access_token: String,
    #[serde(default)]
    id_token: Option<String>,
}

#[derive(Deserialize)]
struct UserinfoResponse {
    sub: String,
    email: Option<String>,
    name: Option<String>,
    #[serde(default)]
    groups: Vec<String>,
}

impl OAuth2Handler {
    pub fn new(
        settings: &crate::config::bootstrap::OAuth2Settings,
        http_client: reqwest::Client,
    ) -> anyhow::Result<Self> {
        let authorization_endpoint = settings
            .authorization_endpoint
            .clone()
            .unwrap_or_else(|| format!("{}/oauth2/authorize", settings.issuer));
        let token_endpoint = settings
            .token_endpoint
            .clone()
            .unwrap_or_else(|| format!("{}/oauth2/token", settings.issuer));
        let userinfo_endpoint = settings
            .userinfo_endpoint
            .clone()
            .unwrap_or_else(|| format!("{}/userinfo", settings.issuer));

        let pending_ttl_secs = settings.pending_flow_ttl_secs;
        Ok(Self {
            client_id: settings.client_id.clone(),
            client_secret: settings.client_secret.clone(),
            authorization_endpoint,
            token_endpoint,
            userinfo_endpoint,
            redirect_uri: settings.redirect_uri.clone(),
            scopes: settings.scopes.clone(),
            default_post_login_path: settings.default_post_login_path.clone(),
            http_client,
            pending: Cache::builder()
                .time_to_live(std::time::Duration::from_secs(pending_ttl_secs))
                // 10_000 active flows is generous for a real workload
                // and bounds the memory cost of a state-fuzzing
                // attacker. Older entries are evicted in TTL order.
                .max_capacity(10_000)
                .build(),
            pending_ttl_secs,
        })
    }

    pub fn pending_ttl_secs(&self) -> u64 {
        self.pending_ttl_secs
    }

    pub fn default_post_login_path(&self) -> &str {
        &self.default_post_login_path
    }

    /// Returns the URL to redirect the user-agent to in order to begin
    /// the auth flow, plus the freshly-minted `state` token. The state
    /// is also stored server-side so the callback can validate it.
    pub async fn begin_authorization(&self, return_path: &str) -> AuthorizationStart {
        let safe_return_path = sanitize_return_path(return_path, &self.default_post_login_path);
        let state = random_token();
        let nonce = random_token();
        let code_verifier = random_token();
        let code_challenge = pkce_challenge(&code_verifier);

        self.pending
            .insert(
                state.clone(),
                PendingFlow {
                    nonce: nonce.clone(),
                    code_verifier: code_verifier.clone(),
                    return_path: safe_return_path,
                },
            )
            .await;

        let scopes = self.scopes.join(" ");
        let url = format!(
            "{}?response_type=code&client_id={}&redirect_uri={}&scope={}&state={}&nonce={}\
             &code_challenge={}&code_challenge_method=S256",
            self.authorization_endpoint,
            encode(&self.client_id),
            encode(&self.redirect_uri),
            encode(&scopes),
            encode(&state),
            encode(&nonce),
            encode(&code_challenge),
        );
        AuthorizationStart { url, state }
    }

    /// Atomically consume the pending state token from the callback.
    /// Returns the stored flow if and only if `state` matched a record
    /// we stored when generating the redirect. `moka::remove` is the
    /// only API on `Cache` that combines lookup + delete in one shot —
    /// using `get` then `invalidate` would let two concurrent
    /// callbacks with the same state both pass the lookup, weakening
    /// the replay-protection guarantee.
    pub async fn consume_pending(&self, state: &str) -> Option<PendingFlow> {
        self.pending.remove(state).await
    }

    /// Exchange the auth code for an access token (with PKCE), fetch
    /// userinfo, and return the authenticated user.
    pub async fn exchange_code(
        &self,
        code: &str,
        code_verifier: &str,
        expected_nonce: &str,
    ) -> Result<AuthenticatedUser, AuthError> {
        let resp = self
            .http_client
            .post(&self.token_endpoint)
            .form(&[
                ("grant_type", "authorization_code"),
                ("code", code),
                ("redirect_uri", &self.redirect_uri),
                ("client_id", &self.client_id),
                ("client_secret", &self.client_secret),
                ("code_verifier", code_verifier),
            ])
            .send()
            .await
            .map_err(|e| AuthError::Unavailable(format!("token exchange failed: {e}")))?;

        if !resp.status().is_success() {
            return Err(AuthError::Invalid);
        }

        let token_resp: TokenResponse = resp
            .json()
            .await
            .map_err(|e| AuthError::Unavailable(format!("token parse failed: {e}")))?;

        // Validate the id_token's nonce when one was issued. Full
        // signature/audience/issuer validation of the id_token is
        // delegated to the JWKS client when JWT-protected routes are
        // hit; here we only ensure the issued id_token contains the
        // nonce we sent, which binds this callback to *this* user-agent.
        if let Some(id_token) = &token_resp.id_token {
            verify_id_token_nonce(id_token, expected_nonce)?;
        }

        let userinfo_resp = self
            .http_client
            .get(&self.userinfo_endpoint)
            .bearer_auth(&token_resp.access_token)
            .send()
            .await
            .map_err(|e| AuthError::Unavailable(format!("userinfo failed: {e}")))?;

        if !userinfo_resp.status().is_success() {
            return Err(AuthError::Invalid);
        }

        let userinfo: UserinfoResponse = userinfo_resp
            .json()
            .await
            .map_err(|e| AuthError::Unavailable(format!("userinfo parse failed: {e}")))?;

        Ok(AuthenticatedUser {
            subject: userinfo.sub,
            email: userinfo.email,
            name: userinfo.name,
            groups: userinfo.groups,
            // OAuth2 redirect flow is for interactive users — scopes
            // belong to API tokens, not session-backed principals.
            scopes: None,
            auth_method: GatewayAuthMethod::Oauth2,
        })
    }
}

/// Constrain the return-path to an absolute path on this gateway. Any
/// scheme, host, or scheme-relative URL is rejected and falls back to
/// the configured default. This blocks open-redirect attacks via crafted
/// `state` parameters or `?return=https://evil.example.com` query args.
pub(crate) fn sanitize_return_path(candidate: &str, default_path: &str) -> String {
    let trimmed = candidate.trim();
    if trimmed.is_empty()
        || !trimmed.starts_with('/')
        || trimmed.starts_with("//")
        || trimmed.starts_with("/\\")
        || trimmed.chars().any(char::is_control)
    {
        return default_path.to_string();
    }
    trimmed.to_string()
}

fn random_token() -> String {
    let mut bytes = [0u8; 32];
    rand::thread_rng().fill_bytes(&mut bytes);
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes)
}

fn pkce_challenge(verifier: &str) -> String {
    let digest = Sha256::digest(verifier.as_bytes());
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(digest)
}

fn encode(s: &str) -> String {
    percent_encoding::utf8_percent_encode(s, percent_encoding::NON_ALPHANUMERIC).to_string()
}

/// Decode the id_token's claims (without signature verification) just
/// far enough to inspect the `nonce`. Signature verification belongs to
/// the JWKS client, but the nonce binding is part of the OAuth2 flow.
fn verify_id_token_nonce(id_token: &str, expected: &str) -> Result<(), AuthError> {
    let parts: Vec<&str> = id_token.split('.').collect();
    if parts.len() != 3 {
        return Err(AuthError::Invalid);
    }
    let payload_bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(parts[1])
        .map_err(|_| AuthError::Invalid)?;
    #[derive(Deserialize)]
    struct WithNonce {
        nonce: Option<String>,
    }
    let claims: WithNonce =
        serde_json::from_slice(&payload_bytes).map_err(|_| AuthError::Invalid)?;
    match claims.nonce.as_deref() {
        Some(n) if n == expected => Ok(()),
        _ => Err(AuthError::Invalid),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn relative_path_passes_through() {
        assert_eq!(sanitize_return_path("/dashboard", "/"), "/dashboard");
        assert_eq!(sanitize_return_path("/a?b=c", "/"), "/a?b=c");
    }

    #[test]
    fn absolute_url_falls_back_to_default() {
        assert_eq!(sanitize_return_path("https://evil.example.com", "/"), "/");
        assert_eq!(sanitize_return_path("//evil.example.com/path", "/"), "/");
        assert_eq!(sanitize_return_path("/\\evil", "/"), "/");
        assert_eq!(sanitize_return_path("javascript:alert(1)", "/"), "/");
        assert_eq!(sanitize_return_path("/safe\r\nlocation: unsafe", "/"), "/");
    }

    #[test]
    fn pkce_challenge_matches_rfc_7636_example() {
        // From RFC 7636 Appendix B.
        let verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        let expected = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
        assert_eq!(pkce_challenge(verifier), expected);
    }

    fn test_handler() -> OAuth2Handler {
        let settings = crate::config::bootstrap::OAuth2Settings {
            issuer: "https://idp.example".into(),
            authorization_endpoint: None,
            token_endpoint: None,
            userinfo_endpoint: None,
            client_id: "test".into(),
            client_secret: "shh".into(),
            redirect_uri: "https://gateway.example/oauth2/callback".into(),
            scopes: vec!["openid".into()],
            session_ttl_secs: 28800,
            default_post_login_path: "/".into(),
            pending_flow_ttl_secs: 300,
        };
        OAuth2Handler::new(&settings, reqwest::Client::new()).unwrap()
    }

    #[tokio::test]
    async fn consume_pending_is_atomic_and_single_use() {
        // The contract that C8 fixes: two concurrent callbacks with
        // the same state must NOT both succeed. The fix uses moka's
        // `remove()` instead of `get()` + `invalidate()`, so the
        // second consume returns None.
        let handler = test_handler();
        let start = handler.begin_authorization("/dashboard").await;

        let first = handler.consume_pending(&start.state).await;
        let second = handler.consume_pending(&start.state).await;

        assert!(first.is_some(), "first consumer should win");
        assert!(
            second.is_none(),
            "second consumer must NOT see the same state"
        );
    }

    #[tokio::test]
    async fn consume_pending_returns_none_for_unknown_state() {
        let handler = test_handler();
        assert!(handler.consume_pending("never-issued").await.is_none());
    }

    #[tokio::test]
    async fn begin_authorization_stores_return_path() {
        let handler = test_handler();
        let start = handler.begin_authorization("/dashboard?x=1").await;
        let pending = handler
            .consume_pending(&start.state)
            .await
            .expect("state should be retrievable");
        assert_eq!(pending.return_path, "/dashboard?x=1");
        // Verify state, nonce, code_verifier are all populated and
        // distinct — otherwise the PKCE/nonce defenses degenerate.
        assert!(!start.state.is_empty());
        assert!(!pending.nonce.is_empty());
        assert!(!pending.code_verifier.is_empty());
        assert_ne!(start.state, pending.nonce);
        assert_ne!(start.state, pending.code_verifier);
        assert_ne!(pending.nonce, pending.code_verifier);
    }

    #[tokio::test]
    async fn begin_authorization_sanitizes_absolute_url_return_path() {
        let handler = test_handler();
        let start = handler
            .begin_authorization("https://evil.example.com")
            .await;
        let pending = handler.consume_pending(&start.state).await.unwrap();
        // Must fall back to the configured default, not echo the
        // attacker URL.
        assert_eq!(pending.return_path, "/");
    }

    #[tokio::test]
    async fn authorization_url_includes_pkce_challenge_method() {
        let handler = test_handler();
        let start = handler.begin_authorization("/").await;
        assert!(
            start.url.contains("code_challenge_method=S256"),
            "PKCE challenge method must be S256: {}",
            start.url,
        );
        assert!(start.url.contains("code_challenge="));
        assert!(start.url.contains("nonce="));
        assert!(start.url.contains("state="));
    }
}
