use std::sync::Arc;

use arc_swap::ArcSwap;
use axum::Router;
use axum::extract::State;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::routing::get;

use crate::auth::AuthState;
use crate::config::{BootstrapConfig, GatewayConfig};
use crate::metrics::Metrics;

use super::forwarding::TrustedProxies;
use super::handler;

#[derive(Clone)]
pub struct AppState {
    pub config: Arc<ArcSwap<GatewayConfig>>,
    pub bootstrap: Arc<BootstrapConfig>,
    pub http_client: reqwest::Client,
    pub auth: AuthState,
    pub metrics: Metrics,
    /// CIDR list whose `X-Forwarded-*` we trust on inbound. Empty list
    /// = treat every connection as a direct client.
    pub trusted_proxies: TrustedProxies,
    /// Fallback scheme for the `{{request.scheme}}` template variable
    /// and outbound `X-Forwarded-Proto` when no trusted-proxy hop
    /// provided one. Comes from `gateway.external_scheme` config; if
    /// unset, defaults to `"http"`.
    pub fallback_scheme: Arc<str>,
    /// Fallback port for the `{{request.port}}` template variable and
    /// outbound `X-Forwarded-Port` when no trusted-proxy hop provided
    /// one. Defaults to the proxy's bind port.
    pub fallback_port: Arc<str>,
}

pub fn build_router(state: AppState) -> Router {
    Router::new()
        .route("/healthz", get(healthz))
        .route("/readyz", get(readyz))
        .route("/metrics", get(metrics_handler))
        .route("/auth/studio/callback", get(handler::studio_callback))
        .route("/oauth2/callback", get(handler::oauth2_callback))
        .route("/oauth2/logout", get(handler::oauth2_logout))
        .fallback(handler::proxy_request)
        .with_state(state)
}

async fn healthz() -> Response {
    (StatusCode::OK, r#"{"status":"ok"}"#).into_response()
}

async fn readyz(State(state): State<AppState>) -> Response {
    let config = state.config.load();
    if config.services.is_empty() {
        return (
            StatusCode::SERVICE_UNAVAILABLE,
            r#"{"status":"not_ready","reason":"no services configured"}"#,
        )
            .into_response();
    }
    (StatusCode::OK, r#"{"status":"ok"}"#).into_response()
}

async fn metrics_handler(State(state): State<AppState>) -> Response {
    let body = state.metrics.render();
    (
        StatusCode::OK,
        [("content-type", "text/plain; version=0.0.4")],
        body,
    )
        .into_response()
}
