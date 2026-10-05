mod basic;
mod jwt;
mod oauth2_flow;
mod session;
mod studio;

pub use basic::BasicAuthValidator;
pub use jwt::JwksClient;
pub(crate) use oauth2_flow::sanitize_return_path;
pub use oauth2_flow::{AuthorizationStart, OAuth2Handler, PendingFlow};
pub use session::{SessionData, SessionStore};
pub use studio::{PendingStudioFlow, StudioAuthHandler, StudioAuthorizationStart};

use crate::config::{BootstrapConfig, GatewayAuthMethod};

#[derive(Clone, Debug)]
pub struct AuthenticatedUser {
    pub subject: String,
    pub email: Option<String>,
    pub name: Option<String>,
    pub groups: Vec<String>,
    /// API-token scope restrictions, or `None` if the principal is an
    /// unrestricted user session (OAuth2 login, regular JWT, etc.).
    /// `Some(empty)` means "an API token with no scopes" — a deny-all
    /// state that must be distinguished from `None` (no scope check).
    pub scopes: Option<Vec<String>>,
    pub auth_method: GatewayAuthMethod,
}

impl AuthenticatedUser {
    /// Returns true when the principal is allowed to exercise the given
    /// scope. Non-scoped principals (sessions / JWTs without scopes) are
    /// unrestricted.
    pub fn has_scope(&self, scope: &str) -> bool {
        match &self.scopes {
            None => true,
            Some(s) => s.iter().any(|x| x == scope),
        }
    }
}

#[derive(Clone)]
pub struct AuthState {
    pub jwks: Option<JwksClient>,
    pub oauth2: Option<OAuth2Handler>,
    pub studio: Option<StudioAuthHandler>,
    pub basic: Option<BasicAuthValidator>,
    pub sessions: SessionStore,
}

impl AuthState {
    pub fn new(config: &BootstrapConfig, http_client: reqwest::Client) -> anyhow::Result<Self> {
        let jwks = config.auth.jwt.as_ref().map(|jwt| {
            JwksClient::new(
                &jwt.jwks_uri,
                &jwt.issuer,
                &jwt.audience,
                jwt.jwt_validation_cache_ttl_secs,
                http_client.clone(),
            )
        });

        let oauth2 = config
            .auth
            .oauth2
            .as_ref()
            .map(|o| OAuth2Handler::new(o, http_client.clone()))
            .transpose()?;

        let studio = config
            .gateway
            .studio_url
            .as_ref()
            .map(|url| StudioAuthHandler::new(url, &config.bosca.api_url, http_client.clone()))
            .transpose()?;

        let basic = config.auth.basic.as_ref().map(|b| {
            BasicAuthValidator::new(
                &b.validation_endpoint,
                b.cache_ttl_secs,
                http_client.clone(),
            )
        });

        let session_ttl = config
            .auth
            .oauth2
            .as_ref()
            .map(|o| o.session_ttl_secs)
            .unwrap_or(28800);

        let sessions = SessionStore::new(session_ttl);

        Ok(Self {
            jwks,
            oauth2,
            studio,
            basic,
            sessions,
        })
    }
}

#[derive(Debug, thiserror::Error)]
pub enum AuthError {
    #[error("missing credentials")]
    Missing,
    #[error("invalid credentials")]
    Invalid,
    #[error("token expired")]
    Expired,
    #[error("insufficient permissions")]
    Forbidden,
    #[error("auth provider unavailable: {0}")]
    Unavailable(String),
}

impl AuthError {
    pub fn status_code(&self) -> axum::http::StatusCode {
        match self {
            Self::Missing | Self::Invalid | Self::Expired => axum::http::StatusCode::UNAUTHORIZED,
            Self::Forbidden => axum::http::StatusCode::FORBIDDEN,
            Self::Unavailable(_) => axum::http::StatusCode::BAD_GATEWAY,
        }
    }
}
