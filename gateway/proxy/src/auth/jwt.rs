use std::sync::Arc;

use jsonwebtoken::{Algorithm, DecodingKey, Validation, decode};
use moka::future::Cache;
use serde::Deserialize;
use tokio::sync::RwLock;

use super::{AuthError, AuthenticatedUser};
use crate::config::GatewayAuthMethod;

#[derive(Clone)]
pub struct JwksClient {
    jwks_uri: String,
    issuer: String,
    audience: String,
    keys: Arc<RwLock<Vec<JwkEntry>>>,
    validation_cache: Cache<String, AuthenticatedUser>,
    http_client: reqwest::Client,
}

#[derive(Clone)]
struct JwkEntry {
    kid: String,
    key: DecodingKey,
    alg: Algorithm,
}

#[derive(Deserialize)]
struct JwksResponse {
    keys: Vec<JwkKey>,
}

#[derive(Deserialize)]
struct JwkKey {
    kid: Option<String>,
    kty: String,
    alg: Option<String>,
    n: Option<String>,
    e: Option<String>,
    #[serde(rename = "use")]
    key_use: Option<String>,
}

#[derive(Deserialize)]
struct Claims {
    sub: String,
    email: Option<String>,
    name: Option<String>,
    #[serde(default)]
    groups: Vec<String>,
    /// API-token scopes — present when the JWT was minted from an API
    /// token credential. Absent for regular user sessions.
    #[serde(default)]
    scopes: Option<Vec<String>>,
}

impl JwksClient {
    pub fn new(
        jwks_uri: &str,
        issuer: &str,
        audience: &str,
        validation_cache_ttl_secs: u64,
        http_client: reqwest::Client,
    ) -> Self {
        Self {
            jwks_uri: jwks_uri.to_string(),
            issuer: issuer.to_string(),
            audience: audience.to_string(),
            keys: Arc::new(RwLock::new(Vec::new())),
            validation_cache: Cache::builder()
                .time_to_live(std::time::Duration::from_secs(validation_cache_ttl_secs))
                .max_capacity(10_000)
                .build(),
            http_client,
        }
    }

    pub async fn fetch_keys(&self) -> Result<(), AuthError> {
        let resp = self
            .http_client
            .get(&self.jwks_uri)
            .send()
            .await
            .map_err(|e| AuthError::Unavailable(format!("JWKS fetch failed: {e}")))?;
        let jwks: JwksResponse = resp
            .json()
            .await
            .map_err(|e| AuthError::Unavailable(format!("JWKS parse failed: {e}")))?;

        let mut entries = Vec::new();
        for key in jwks.keys {
            if key.key_use.as_deref() == Some("enc") {
                continue;
            }
            if key.kty != "RSA" {
                continue;
            }
            let Some(n) = &key.n else { continue };
            let Some(e) = &key.e else { continue };
            let Ok(decoding_key) = DecodingKey::from_rsa_components(n, e) else {
                continue;
            };
            let alg = match key.alg.as_deref() {
                Some("RS384") => Algorithm::RS384,
                Some("RS512") => Algorithm::RS512,
                _ => Algorithm::RS256,
            };
            entries.push(JwkEntry {
                kid: key.kid.unwrap_or_default(),
                key: decoding_key,
                alg,
            });
        }

        let mut guard = self.keys.write().await;
        *guard = entries;
        tracing::info!(count = guard.len(), "JWKS keys loaded");
        Ok(())
    }

    pub async fn validate_token(&self, token: &str) -> Result<AuthenticatedUser, AuthError> {
        if let Some(cached) = self.validation_cache.get(token).await {
            return Ok(cached);
        }

        let header = jsonwebtoken::decode_header(token).map_err(|_| AuthError::Invalid)?;

        // A token without a `kid` header is rejected. Falling back to
        // "first key" is a key-confusion footgun — JWKS responses are
        // unordered, so an attacker who can swap in their own key (e.g.
        // by registering one with the IdP) would have it picked up.
        // Real OIDC IdPs always set `kid`. This check happens BEFORE
        // any JWKS fetch so an invalid token can't trigger a remote
        // call (small DoS hardening).
        let kid = header.kid.as_deref().ok_or(AuthError::Invalid)?.to_string();

        let keys = self.keys.read().await;
        if keys.is_empty() {
            drop(keys);
            self.fetch_keys().await?;
        }
        let keys = self.keys.read().await;

        let entry = match keys.iter().find(|k| k.kid == kid).cloned() {
            Some(k) => k,
            None => {
                drop(keys);
                self.fetch_keys().await?;
                let keys = self.keys.read().await;
                keys.iter()
                    .find(|k| k.kid == kid)
                    .cloned()
                    .ok_or(AuthError::Invalid)?
            }
        };

        let mut validation = Validation::new(entry.alg);
        validation.set_issuer(&[&self.issuer]);
        validation.set_audience(&[&self.audience]);

        let data =
            decode::<Claims>(token, &entry.key, &validation).map_err(|e| match e.kind() {
                jsonwebtoken::errors::ErrorKind::ExpiredSignature => AuthError::Expired,
                _ => AuthError::Invalid,
            })?;

        let user = AuthenticatedUser {
            subject: data.claims.sub,
            email: data.claims.email,
            name: data.claims.name,
            groups: data.claims.groups,
            scopes: data.claims.scopes,
            auth_method: GatewayAuthMethod::Jwt,
        };

        self.validation_cache
            .insert(token.to_string(), user.clone())
            .await;
        Ok(user)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use base64::Engine;
    use jsonwebtoken::{EncodingKey, Header, encode};
    use serde::Serialize;

    /// Build a token whose header omits `kid`. Signature/issuer/audience
    /// don't matter — the rejection must happen before any of those
    /// checks (and before any JWKS network call).
    fn token_without_kid() -> String {
        #[derive(Serialize)]
        struct Claims {
            sub: String,
            exp: usize,
        }
        let mut header = Header::new(Algorithm::HS256);
        header.kid = None;
        let claims = Claims {
            sub: "anyone".into(),
            exp: 9_999_999_999,
        };
        encode(&header, &claims, &EncodingKey::from_secret(b"k")).unwrap()
    }

    /// Build a token with a `kid` that we know is NOT in any JWKS
    /// the client has cached. This exercises the unknown-kid path,
    /// which goes through a JWKS re-fetch — but the test's client
    /// has no reachable JWKS URI, so the second fetch errors out and
    /// the call ends in [`AuthError::Unavailable`].
    fn token_with_unknown_kid() -> String {
        #[derive(Serialize)]
        struct Claims {
            sub: String,
            exp: usize,
        }
        let mut header = Header::new(Algorithm::HS256);
        header.kid = Some("never-issued".into());
        let claims = Claims {
            sub: "anyone".into(),
            exp: 9_999_999_999,
        };
        encode(&header, &claims, &EncodingKey::from_secret(b"k")).unwrap()
    }

    fn jwks_client_pointing_nowhere() -> JwksClient {
        // `http://127.0.0.1:1` reliably refuses connections, so any
        // JWKS fetch becomes a connection error rather than a hang
        // or a 200.
        JwksClient::new(
            "http://127.0.0.1:1/jwks.json",
            "https://issuer.example",
            "test-audience",
            60,
            reqwest::Client::new(),
        )
    }

    #[tokio::test]
    async fn rejects_token_without_kid_before_jwks_fetch() {
        let client = jwks_client_pointing_nowhere();
        let token = token_without_kid();

        let result = client.validate_token(&token).await;
        match result {
            Err(AuthError::Invalid) => {} // expected
            other => panic!("expected AuthError::Invalid for kid-less token, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn rejects_token_with_unparseable_structure() {
        let client = jwks_client_pointing_nowhere();
        let result = client.validate_token("not.a.jwt").await;
        match result {
            Err(AuthError::Invalid) => {}
            other => panic!("expected AuthError::Invalid for garbage token, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn token_with_unknown_kid_fails_when_jwks_is_unreachable() {
        let client = jwks_client_pointing_nowhere();
        let token = token_with_unknown_kid();
        let result = client.validate_token(&token).await;
        // The JWKS fetch fails (connection refused) → AuthError::Unavailable.
        // The point of this test: the unknown-kid path TRIES to re-fetch
        // rather than silently failing with a generic Invalid.
        match result {
            Err(AuthError::Unavailable(_)) => {}
            other => panic!("expected AuthError::Unavailable, got {other:?}"),
        }
    }

    #[test]
    fn token_without_kid_decodes_a_header_without_kid() {
        // Sanity check that our test helper produces what we think it
        // does — without this, a regression in the helper would cause
        // the rejection test to pass for the wrong reason.
        let token = token_without_kid();
        let header = jsonwebtoken::decode_header(&token).unwrap();
        assert!(header.kid.is_none(), "test helper accidentally set a kid");
        // Sanity: the encoded JWT does have three dot-separated parts.
        assert_eq!(token.matches('.').count(), 2);
        // Sanity: the payload base64-decodes to JSON.
        let parts: Vec<&str> = token.split('.').collect();
        let payload = base64::engine::general_purpose::URL_SAFE_NO_PAD
            .decode(parts[1])
            .unwrap();
        let s = String::from_utf8(payload).unwrap();
        assert!(s.contains("\"sub\":\"anyone\""));
    }
}
