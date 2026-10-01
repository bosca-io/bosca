use base64::Engine;
use moka::future::Cache;
use rand::RngCore;
use serde::Deserialize;

use super::{AuthError, sanitize_return_path};

const PENDING_TTL_SECS: u64 = 300;
const STUDIO_HANDOFF_PATH: &str = "/auth/gateway";
const GATEWAY_CALLBACK_PATH: &str = "/auth/studio/callback";

/// Studio-backed browser authentication for interactive gateway routes.
///
/// The browser carries only an opaque random state value between the gateway
/// and Studio. The original local route is retained in the gateway's bounded,
/// expiring cache and is consumed atomically by the callback.
#[derive(Clone)]
pub struct StudioAuthHandler {
    studio_url: reqwest::Url,
    graphql_url: reqwest::Url,
    http_client: reqwest::Client,
    pending: Cache<String, PendingStudioFlow>,
    #[cfg(test)]
    mock_token: Option<String>,
}

#[derive(Clone, Debug)]
pub struct PendingStudioFlow {
    pub return_path: String,
}

#[derive(Clone, Debug)]
pub struct StudioAuthorizationStart {
    pub url: String,
    pub state: String,
}

#[derive(Deserialize)]
struct GraphQlResponse<T> {
    data: Option<T>,
    #[serde(default)]
    errors: Vec<GraphQlError>,
}

#[derive(Deserialize)]
struct GraphQlError {
    message: String,
}

#[derive(Deserialize)]
struct ExchangeData {
    security: ExchangeSecurity,
}

#[derive(Deserialize)]
struct ExchangeSecurity {
    login: ExchangeLogin,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ExchangeLogin {
    exchange_token: ExchangeLoginResponse,
}

#[derive(Deserialize)]
struct ExchangeLoginResponse {
    token: ExchangeJwt,
}

#[derive(Deserialize)]
struct ExchangeJwt {
    token: String,
}

impl StudioAuthHandler {
    pub fn new(
        studio_url: &str,
        bosca_api_url: &str,
        http_client: reqwest::Client,
    ) -> anyhow::Result<Self> {
        let studio_url = reqwest::Url::parse(studio_url)?;
        let bosca_api_url = bosca_api_url.trim_end_matches('/');
        let graphql_url = reqwest::Url::parse(&format!("{bosca_api_url}/graphql"))?;
        Ok(Self {
            studio_url,
            graphql_url,
            http_client,
            pending: Cache::builder()
                .time_to_live(std::time::Duration::from_secs(PENDING_TTL_SECS))
                .max_capacity(10_000)
                .build(),
            #[cfg(test)]
            mock_token: None,
        })
    }

    #[cfg(test)]
    pub(crate) fn with_mock_token(studio_url: &str, bosca_api_url: &str, token: &str) -> Self {
        let mut handler = Self::new(studio_url, bosca_api_url, reqwest::Client::new()).unwrap();
        handler.mock_token = Some(token.to_string());
        handler
    }

    pub fn pending_ttl_secs(&self) -> u64 {
        PENDING_TTL_SECS
    }

    /// Starts a Studio handoff and stores the local return path server-side.
    pub async fn begin_authorization(
        &self,
        callback_url: &str,
        return_path: &str,
    ) -> Result<StudioAuthorizationStart, AuthError> {
        validate_callback_url(callback_url)?;
        let state = random_token();
        self.pending
            .insert(
                state.clone(),
                PendingStudioFlow {
                    return_path: sanitize_return_path(return_path, "/"),
                },
            )
            .await;

        let mut redirect = reqwest::Url::parse(callback_url).map_err(|_| AuthError::Invalid)?;
        redirect.query_pairs_mut().append_pair("state", &state);
        let mut url = self
            .studio_url
            .join(STUDIO_HANDOFF_PATH)
            .map_err(|e| AuthError::Unavailable(format!("invalid Studio URL: {e}")))?;
        url.query_pairs_mut()
            .append_pair("redirect", redirect.as_str());

        Ok(StudioAuthorizationStart {
            url: url.to_string(),
            state,
        })
    }

    /// Atomically consumes a pending flow, preventing callback replay.
    pub async fn consume_pending(&self, state: &str) -> Option<PendingStudioFlow> {
        self.pending.remove(state).await
    }

    /// Redeems Studio's single-use exchange token for a Bosca JWT.
    pub async fn exchange_token(&self, exchange_token: &str) -> Result<String, AuthError> {
        #[cfg(test)]
        if let Some(token) = &self.mock_token {
            return Ok(token.clone());
        }

        let response = self
            .http_client
            .post(self.graphql_url.clone())
            .json(&serde_json::json!({
                "query": "mutation GatewayExchange($token: String!) { security { login { exchangeToken(token: $token) { token { token } } } } }",
                "variables": { "token": exchange_token },
            }))
            .send()
            .await
            .map_err(|e| AuthError::Unavailable(format!("exchange request failed: {e}")))?;

        if !response.status().is_success() {
            return Err(AuthError::Unavailable(format!(
                "exchange request returned {}",
                response.status()
            )));
        }

        let body: GraphQlResponse<ExchangeData> = response
            .json()
            .await
            .map_err(|e| AuthError::Unavailable(format!("exchange response was invalid: {e}")))?;
        if !body.errors.is_empty() {
            tracing::warn!(
                errors = ?body.errors.iter().map(|error| &error.message).collect::<Vec<_>>(),
                "Studio exchange token was rejected"
            );
            return Err(AuthError::Invalid);
        }
        body.data
            .map(|data| data.security.login.exchange_token.token.token)
            .ok_or(AuthError::Invalid)
    }
}

fn validate_callback_url(callback_url: &str) -> Result<(), AuthError> {
    let parsed = reqwest::Url::parse(callback_url).map_err(|_| AuthError::Invalid)?;
    if !matches!(parsed.scheme(), "http" | "https")
        || parsed.host_str().is_none()
        || !parsed.username().is_empty()
        || parsed.password().is_some()
        || parsed.path() != GATEWAY_CALLBACK_PATH
        || parsed.query().is_some()
        || parsed.fragment().is_some()
    {
        return Err(AuthError::Invalid);
    }
    Ok(())
}

fn random_token() -> String {
    let mut bytes = [0u8; 32];
    rand::thread_rng().fill_bytes(&mut bytes);
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn handler() -> StudioAuthHandler {
        StudioAuthHandler::new(
            "https://studio.example.com",
            "https://api.example.com",
            reqwest::Client::new(),
        )
        .unwrap()
    }

    #[tokio::test]
    async fn authorization_url_carries_the_state_bound_redirect() {
        let handler = handler();
        let start = handler
            .begin_authorization(
                "https://warehouse.example.com/auth/studio/callback",
                "/ui/query?tab=history",
            )
            .await
            .unwrap();

        let url = reqwest::Url::parse(&start.url).unwrap();
        assert_eq!(
            url.origin().ascii_serialization(),
            "https://studio.example.com"
        );
        assert_eq!(url.path(), STUDIO_HANDOFF_PATH);
        let redirect = url
            .query_pairs()
            .find(|(key, _)| key == "redirect")
            .map(|(_, value)| value.into_owned())
            .unwrap();
        let redirect = reqwest::Url::parse(&redirect).unwrap();
        assert_eq!(
            redirect.origin().ascii_serialization(),
            "https://warehouse.example.com"
        );
        assert_eq!(redirect.path(), GATEWAY_CALLBACK_PATH);
        assert_eq!(
            redirect
                .query_pairs()
                .find(|(key, _)| key == "state")
                .map(|(_, value)| value.into_owned()),
            Some(start.state.clone())
        );
        assert!(!start.url.contains("/ui/query"));

        let pending = handler.consume_pending(&start.state).await.unwrap();
        assert_eq!(pending.return_path, "/ui/query?tab=history");
        assert!(handler.consume_pending(&start.state).await.is_none());
    }

    #[tokio::test]
    async fn authorization_rejects_unsafe_callback_urls() {
        let handler = handler();
        for callback in [
            "javascript:alert(1)",
            "https://user@warehouse.example.com/auth/studio/callback",
            "https://warehouse.example.com/other",
            "https://warehouse.example.com/auth/studio/callback?next=evil",
        ] {
            assert!(
                handler.begin_authorization(callback, "/").await.is_err(),
                "callback should be rejected: {callback}"
            );
        }
    }

    #[tokio::test]
    async fn authorization_sanitizes_the_stored_return_path() {
        let handler = handler();
        let start = handler
            .begin_authorization(
                "https://warehouse.example.com/auth/studio/callback",
                "https://evil.example.com",
            )
            .await
            .unwrap();
        assert_eq!(
            handler
                .consume_pending(&start.state)
                .await
                .unwrap()
                .return_path,
            "/"
        );
    }
}
