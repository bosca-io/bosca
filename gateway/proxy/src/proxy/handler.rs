use std::net::SocketAddr;
use std::time::Instant;

use axum::body::Body;
use axum::extract::{ConnectInfo, State};
use axum::http::{HeaderMap, HeaderName, HeaderValue, Method, StatusCode, Uri, header};
use axum::response::{IntoResponse, Response};
use futures::TryStreamExt;

use crate::auth::{AuthError, AuthenticatedUser, SessionStore};
use crate::config::{GatewayAuthMethod, PathMatcher, RenderContext, normalize_host_header};

use super::forwarding::{ForwardingContext, ResolveArgs};

use super::router::AppState;

/// Standard hop-by-hop headers per RFC 7230 §6.1. Stripped from both
/// inbound (before forwarding upstream) and outbound (response from
/// upstream).
const HOP_BY_HOP: &[&str] = &[
    "connection",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailer",
    "transfer-encoding",
    "upgrade",
];

/// Headers that carry sensitive inbound state and must not be copied verbatim
/// to the upstream. The raw Cookie header includes the gateway's own auth
/// cookies; after this blanket strip, [forwarded_cookie_header] reconstructs it
/// without any gateway-owned entries so application cookies still reach the
/// upstream.
const SENSITIVE_INBOUND: &[&str] = &["authorization", "cookie", "host"];

/// Namespace reserved for cookies owned by this gateway. Keeping the filter at
/// the namespace boundary automatically covers new gateway cookies without
/// maintaining a second list alongside the individual cookie constants below.
const GATEWAY_COOKIE_PREFIX: &str = "__Host-gateway_";

/// `X-Forwarded-*` and RFC 7239 `Forwarded` headers are how reverse
/// proxies communicate the original request's apparent origin to
/// upstream services. The gateway is the trust boundary here — a
/// client-supplied value would let an attacker spoof their apparent
/// IP, host, or scheme. We strip every inbound forwarded-* header
/// and (if the upstream cares about origin) rewrite a known-good
/// `X-Forwarded-For` based on the actual peer address.
///
/// Per IETF guidance any header starting with `forwarded` or
/// `x-forwarded-` is unsafe to forward verbatim.
fn is_forwarded_header(name: &str) -> bool {
    name == "forwarded" || name.starts_with("x-forwarded-") || name == "x-real-ip"
}

/// Name of the short-lived cookie that binds an OAuth2 flow to the
/// user-agent that started it. The `__Host-` prefix locks the cookie
/// to the exact host (no `Domain`, requires HTTPS, `Path=/`).
const OAUTH2_STATE_COOKIE: &str = "__Host-gateway_oauth2_state";
/// Browser state for a Studio authentication handoff. The callback requires
/// this cookie to match its query state before consuming the pending flow.
const STUDIO_STATE_COOKIE: &str = "__Host-gateway_studio_state";
const SESSION_COOKIE: &str = "__Host-gateway_session";
/// API-token scope required to issue read-method requests through any
/// gateway. Must stay in sync with `ApiTokenScopes.GATEWAY_READ` in the
/// Bosca server.
const GATEWAY_READ_SCOPE: &str = "gateway:read";

/// API-token scope required to issue write-method requests through any
/// gateway. Must stay in sync with `ApiTokenScopes.GATEWAY_WRITE` in
/// the Bosca server.
const GATEWAY_WRITE_SCOPE: &str = "gateway:write";

/// Classify an HTTP method as a read (safe per RFC 7231 §4.2.1) or
/// write. WebDAV/CalDAV verbs that the gateway might encounter
/// (PROPFIND, REPORT) are treated as reads; everything not in this set
/// — including CONNECT, TRACE, and custom verbs — is conservatively
/// treated as a write so unexpected methods fall under the stricter
/// gating.
fn is_read_method(method: &Method) -> bool {
    matches!(method, &Method::GET | &Method::HEAD | &Method::OPTIONS)
}

pub async fn proxy_request(
    State(state): State<AppState>,
    ConnectInfo(peer_addr): ConnectInfo<SocketAddr>,
    method: Method,
    uri: Uri,
    headers: HeaderMap,
    body: Body,
) -> Response {
    let started = Instant::now();
    // Captured up front so the access log can reference them after
    // `method` is moved into the upstream request builder below and
    // after the borrow on `uri` drops.
    let method_label = method.to_string();
    let client_ip_label = peer_addr.ip().to_string();
    let config = state.config.load();
    let raw_path = uri.path();
    // Defense in depth: collapse runs of `/` so `//api/foo` and
    // `/api//foo` route the same way as `/api/foo`. Axum's URI parser
    // preserves multiple slashes literally, leaving the matcher open
    // to operators who probe with double-slashes to slip past prefix
    // checks.
    let normalized = collapse_slashes(raw_path);
    let path = normalized.as_str();

    // Authority for host-based routing. HTTP/2 carries it on
    // `uri.authority()`; HTTP/1.1 puts it in the `Host` header.
    let inbound_authority = uri
        .authority()
        .map(|a| a.as_str())
        .or_else(|| headers.get(header::HOST).and_then(|v| v.to_str().ok()))
        .unwrap_or("");

    // Trust-aware view of the request: if the inbound peer is in
    // `trusted_proxies`, the `X-Forwarded-*` headers are taken as
    // truth; otherwise the peer IS the client.
    let forwarding = ForwardingContext::resolve(ResolveArgs {
        peer: peer_addr.ip(),
        trusted: &state.trusted_proxies,
        headers: &headers,
        inbound_authority,
        fallback_scheme: &state.fallback_scheme,
        fallback_port: &state.fallback_port,
    });

    // Route on the *client's* host — the one a trusted hop forwarded,
    // not the proxy's internal authority. Falls back to the inbound
    // authority when no trusted hop is in play.
    let host = normalize_host_header(&forwarding.host);

    let (compiled_route, route, service) = match config.match_route(host, path) {
        Some(m) => m,
        None => {
            state.metrics.requests_total("unknown", "4xx");
            log_outcome(
                started,
                &method_label,
                path,
                &client_ip_label,
                StatusCode::NOT_FOUND,
                None,
                None,
                None,
                Some("no matching route"),
            );
            return (StatusCode::NOT_FOUND, "no matching route").into_response();
        }
    };

    let user = if route.auth_method == GatewayAuthMethod::None {
        None
    } else {
        match authenticate(&state, &route.auth_method, &headers).await {
            Ok(u) => Some(u),
            Err(AuthError::Missing | AuthError::Invalid | AuthError::Expired)
                if route.auth_method == GatewayAuthMethod::Oauth2 =>
            {
                let return_path = original_return_path(&uri);
                let studio = match &state.auth.studio {
                    Some(studio) => studio,
                    None => {
                        let error = AuthError::Unavailable(
                            "Studio authentication is not configured".to_string(),
                        );
                        state.metrics.auth_attempts(&route.auth_method, false);
                        return auth_error_response(&error, &route.auth_method);
                    }
                };
                let callback_url = format!(
                    "{}://{}/auth/studio/callback",
                    forwarding.scheme, forwarding.host
                );
                let start = match studio
                    .begin_authorization(&callback_url, &return_path)
                    .await
                {
                    Ok(start) => start,
                    Err(error) => {
                        state.metrics.auth_attempts(&route.auth_method, false);
                        tracing::warn!(error = %error, "failed to begin Studio authentication");
                        return auth_error_response(&error, &route.auth_method);
                    }
                };
                let cookie = format!(
                    "{}={}; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age={}",
                    STUDIO_STATE_COOKIE,
                    start.state,
                    studio.pending_ttl_secs(),
                );
                log_outcome(
                    started,
                    &method_label,
                    path,
                    &client_ip_label,
                    StatusCode::SEE_OTHER,
                    Some(&service.name),
                    None,
                    Some(&route.auth_method),
                    Some("redirecting unauthenticated browser to Studio"),
                );
                return Response::builder()
                    .status(StatusCode::SEE_OTHER)
                    .header(header::SET_COOKIE, cookie)
                    .header(header::LOCATION, start.url)
                    .body(Body::empty())
                    .unwrap_or_else(|_| {
                        (StatusCode::INTERNAL_SERVER_ERROR, "redirect failed").into_response()
                    });
            }
            Err(e) => {
                state.metrics.auth_attempts(&route.auth_method, false);
                let reason = format!("auth failed: {e}");
                log_outcome(
                    started,
                    &method_label,
                    path,
                    &client_ip_label,
                    e.status_code(),
                    Some(&service.name),
                    None,
                    Some(&route.auth_method),
                    Some(&reason),
                );
                return auth_error_response(&e, &route.auth_method);
            }
        }
    };

    if let Some(ref user) = user {
        state.metrics.auth_attempts(&route.auth_method, true);

        // Authorize the request by HTTP-method class (read vs write).
        // The route's `read_groups` / `write_groups` arrays gate which
        // groups can issue each class of request; API tokens
        // additionally need the matching `gateway:read` / `gateway:write`
        // scope. Non-scoped principals (interactive sessions) skip the
        // scope check — they're already authenticated as a user.
        let (allowed_groups, required_scope, method_class) = if is_read_method(&method) {
            (&route.read_groups, GATEWAY_READ_SCOPE, "read")
        } else {
            (&route.write_groups, GATEWAY_WRITE_SCOPE, "write")
        };

        if allowed_groups.is_empty() || !allowed_groups.iter().any(|g| user.groups.contains(g)) {
            state.metrics.requests_total(&service.name, "4xx");
            let reason = format!(
                "auth failed: principal groups {:?} do not intersect route {}_groups {:?}",
                user.groups, method_class, allowed_groups
            );
            log_outcome(
                started,
                &method_label,
                path,
                &client_ip_label,
                StatusCode::FORBIDDEN,
                Some(&service.name),
                Some(&user.subject),
                Some(&route.auth_method),
                Some(&reason),
            );
            return (StatusCode::FORBIDDEN, "access denied").into_response();
        }

        if !user.has_scope(required_scope) {
            state.metrics.requests_total(&service.name, "4xx");
            let reason = format!(
                "auth failed: API token missing required scope {required_scope} ({method_class} request)"
            );
            log_outcome(
                started,
                &method_label,
                path,
                &client_ip_label,
                StatusCode::FORBIDDEN,
                Some(&service.name),
                Some(&user.subject),
                Some(&route.auth_method),
                Some(&reason),
            );
            return (StatusCode::FORBIDDEN, "access denied").into_response();
        }
    }

    let upstream_path = compute_upstream_path(path, route.strip_prefix, &compiled_route.matcher);
    let mut upstream_url = format!("{}{}", service.url.trim_end_matches('/'), upstream_path);
    if let Some(query) = uri.query() {
        upstream_url.push('?');
        upstream_url.push_str(query);
    }

    let mut upstream_headers = HeaderMap::new();
    for (key, value) in &headers {
        let name = key.as_str().to_ascii_lowercase();
        if HOP_BY_HOP.contains(&name.as_str())
            || SENSITIVE_INBOUND.contains(&name.as_str())
            || is_forwarded_header(&name)
        {
            continue;
        }
        upstream_headers.insert(key.clone(), value.clone());
    }
    if let Some(value) = forwarded_cookie_header(&headers) {
        upstream_headers.insert(header::COOKIE, value);
    }

    // Auto-stamp the canonical X-Forwarded-* set on every outbound
    // request. The values reflect the trust-aware `ForwardingContext`:
    // when a trusted hop sent its own X-Forwarded-* on the inbound,
    // we propagate those (and append ourselves to the For chain);
    // otherwise the immediate peer is treated as the client. Route
    // `inject_headers` runs *after* this and can still overwrite — so
    // an operator can pin a value (e.g. the Trino preset hardcoded
    // `X-Forwarded-Proto: https` for upstreams that demand it).
    insert_forwarded_header(
        &mut upstream_headers,
        "x-forwarded-for",
        &forwarding.xff_chain,
    );
    insert_forwarded_header(
        &mut upstream_headers,
        "x-forwarded-proto",
        &forwarding.scheme,
    );
    insert_forwarded_header(&mut upstream_headers, "x-forwarded-host", &forwarding.host);
    insert_forwarded_header(&mut upstream_headers, "x-forwarded-port", &forwarding.port);

    // Inject route-configured headers (identity claims,
    // upstream-specific routing hints, custom overrides). These run
    // AFTER the auto-stamp so an operator can pin individual values
    // (e.g. an explicit `X-Forwarded-Proto: https`). The loop runs
    // unconditionally — anonymous routes can still stamp non-identity
    // headers; user-referencing templates drop the header when no
    // user is present.
    let render_ctx = RenderContext {
        user: user.as_ref(),
        request_path: path,
        request_host: &forwarding.host,
        request_scheme: &forwarding.scheme,
        request_port: &forwarding.port,
        request_client_ip: &forwarding.client_ip,
        upstream_name: &service.name,
    };
    for (header_name, template) in &compiled_route.compiled_headers {
        if let Some(value) = template.render(&render_ctx) {
            if let (Ok(name), Ok(val)) = (
                HeaderName::from_bytes(header_name.as_bytes()),
                HeaderValue::from_str(&value),
            ) {
                upstream_headers.insert(name, val);
            }
        }
    }

    let timeout = std::time::Duration::from_secs(service.request_timeout_secs);

    let body_stream = body
        .into_data_stream()
        .map_err(|e| std::io::Error::other(e.to_string()));
    let reqwest_body = reqwest::Body::wrap_stream(body_stream);

    let subject_label = user.as_ref().map(|u| u.subject.clone());
    let upstream_resp = match tokio::time::timeout(timeout, async {
        state
            .http_client
            .request(method, &upstream_url)
            .headers(upstream_headers)
            .body(reqwest_body)
            .send()
            .await
    })
    .await
    {
        Ok(Ok(resp)) => resp,
        Ok(Err(e)) => {
            state.metrics.requests_total(&service.name, "5xx");
            let reason = format!("upstream request failed: {e}");
            log_outcome(
                started,
                &method_label,
                path,
                &client_ip_label,
                StatusCode::BAD_GATEWAY,
                Some(&service.name),
                subject_label.as_deref(),
                Some(&route.auth_method),
                Some(&reason),
            );
            return (StatusCode::BAD_GATEWAY, "upstream unavailable").into_response();
        }
        Err(_) => {
            state.metrics.requests_total(&service.name, "5xx");
            log_outcome(
                started,
                &method_label,
                path,
                &client_ip_label,
                StatusCode::GATEWAY_TIMEOUT,
                Some(&service.name),
                subject_label.as_deref(),
                Some(&route.auth_method),
                Some("upstream request timed out"),
            );
            return (StatusCode::GATEWAY_TIMEOUT, "upstream timeout").into_response();
        }
    };

    let status = upstream_resp.status();
    let status_bucket = match status.as_u16() / 100 {
        2 => "2xx",
        3 => "3xx",
        4 => "4xx",
        5 => "5xx",
        _ => "other",
    };
    state.metrics.requests_total(&service.name, status_bucket);
    state
        .metrics
        .request_duration(&service.name, started.elapsed().as_secs_f64());

    let mut response_headers = HeaderMap::new();
    for (key, value) in upstream_resp.headers() {
        let name = key.as_str().to_ascii_lowercase();
        if HOP_BY_HOP.contains(&name.as_str()) {
            continue;
        }
        // `Location` on a 3xx is the one header where the upstream's
        // view of the URL space leaks to the browser. The upstream
        // knows nothing about the gateway's prefix or external host —
        // an unrewritten Location sends the user-agent straight at
        // the backend, defeating auth and host scoping in one hop.
        if status.is_redirection() && name == "location" {
            if let Ok(loc) = value.to_str() {
                let rewritten = rewrite_location(
                    loc,
                    &service.url,
                    &forwarding.host,
                    route.strip_prefix,
                    &compiled_route.matcher,
                );
                if let Ok(val) = HeaderValue::from_str(&rewritten) {
                    response_headers.insert(key.clone(), val);
                    continue;
                } else {
                    // An unrewritable value (control chars from a
                    // pathological upstream) would otherwise fall
                    // through to the verbatim-copy path below and
                    // leak. Drop it instead and warn loudly so the
                    // operator notices.
                    tracing::warn!(
                        upstream = %service.name,
                        original = %loc,
                        rewritten = %rewritten,
                        "dropping un-encodable Location header on 3xx response"
                    );
                    continue;
                }
            }
        }
        response_headers.insert(key.clone(), value.clone());
    }

    let resp_stream = upstream_resp.bytes_stream();

    let mut response = Response::builder().status(status);
    for (key, value) in &response_headers {
        response = response.header(key, value);
    }
    let final_response = response
        .body(Body::from_stream(resp_stream))
        .unwrap_or_else(|_| {
            (StatusCode::INTERNAL_SERVER_ERROR, "response build error").into_response()
        });

    // Log the proxied response. A 4xx/5xx here comes from the *upstream*,
    // not the gateway — we surface it at warn/error level all the same
    // so an operator skimming `request` lines doesn't have to flip
    // levels to notice degraded upstreams. The success path is the
    // hottest, so this is the only log line a healthy proxy emits per
    // request — keep it lean.
    log_outcome(
        started,
        &method_label,
        path,
        &client_ip_label,
        final_response.status(),
        Some(&service.name),
        subject_label.as_deref(),
        Some(&route.auth_method),
        None,
    );
    final_response
}

pub async fn oauth2_callback(
    State(state): State<AppState>,
    axum::extract::Query(params): axum::extract::Query<std::collections::HashMap<String, String>>,
    headers: HeaderMap,
) -> Response {
    let oauth2 = match &state.auth.oauth2 {
        Some(o) => o,
        None => {
            return (StatusCode::INTERNAL_SERVER_ERROR, "oauth2 not configured").into_response();
        }
    };

    let state_token = match params.get("state") {
        Some(s) => s,
        None => return (StatusCode::BAD_REQUEST, "missing state parameter").into_response(),
    };
    let code = match params.get("code") {
        Some(c) => c,
        None => return (StatusCode::BAD_REQUEST, "missing code parameter").into_response(),
    };

    // CSRF: the inbound cookie must equal the `?state=` query param.
    // The cookie was set on the same user-agent that initiated the
    // flow; without it, a stolen state token can't be completed by
    // an attacker in a different browser.
    let cookie_state = extract_named_cookie(&headers, OAUTH2_STATE_COOKIE);
    match cookie_state {
        Some(c) if constant_time_eq(c.as_bytes(), state_token.as_bytes()) => {}
        _ => {
            tracing::warn!("oauth2 callback missing or mismatched state cookie");
            return (StatusCode::BAD_REQUEST, "invalid state").into_response();
        }
    }

    // Consume the pending state from the server-side store atomically.
    // An unknown or already-consumed state aborts the flow.
    let pending = match oauth2.consume_pending(state_token).await {
        Some(p) => p,
        None => {
            tracing::warn!("oauth2 callback with unknown or expired state");
            return (StatusCode::BAD_REQUEST, "invalid state").into_response();
        }
    };

    let user = match oauth2
        .exchange_code(code, &pending.code_verifier, &pending.nonce)
        .await
    {
        Ok(u) => u,
        Err(e) => {
            tracing::warn!(error = %e, "oauth2 code exchange failed");
            return (StatusCode::UNAUTHORIZED, "authentication failed").into_response();
        }
    };

    let session_id = SessionStore::create_session_id();
    state.auth.sessions.store(&session_id, user).await;

    let session_cookie = format!(
        "{}={}; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age={}",
        SESSION_COOKIE,
        session_id,
        state.auth.sessions.ttl_secs(),
    );
    // Clear the OAuth2 state cookie now that the flow is complete.
    let state_clear = format!(
        "{}=; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=0",
        OAUTH2_STATE_COOKIE
    );

    // The redirect target comes from the stored PendingFlow, NOT the
    // query string. This blocks open-redirect attacks that craft
    // `state` to point at an attacker-controlled URL.
    Response::builder()
        .status(StatusCode::FOUND)
        .header(header::SET_COOKIE, session_cookie)
        .header(header::SET_COOKIE, state_clear)
        .header(header::LOCATION, &pending.return_path)
        .body(Body::empty())
        .unwrap()
}

pub async fn studio_callback(
    State(state): State<AppState>,
    axum::extract::Query(params): axum::extract::Query<std::collections::HashMap<String, String>>,
    headers: HeaderMap,
) -> Response {
    let studio = match &state.auth.studio {
        Some(studio) => studio,
        None => {
            return (
                StatusCode::SERVICE_UNAVAILABLE,
                "Studio authentication is not configured",
            )
                .into_response();
        }
    };
    let state_token = match params.get("state") {
        Some(state) => state,
        None => return (StatusCode::BAD_REQUEST, "missing state parameter").into_response(),
    };
    let exchange_token = match params.get("exchangeToken") {
        Some(token) => token,
        None => {
            return (StatusCode::BAD_REQUEST, "missing exchange token parameter").into_response();
        }
    };

    match extract_named_cookie(&headers, STUDIO_STATE_COOKIE) {
        Some(cookie) if constant_time_eq(cookie.as_bytes(), state_token.as_bytes()) => {}
        _ => {
            tracing::warn!("Studio callback missing or mismatched state cookie");
            return (StatusCode::BAD_REQUEST, "invalid state").into_response();
        }
    }

    let pending = match studio.consume_pending(state_token).await {
        Some(pending) => pending,
        None => {
            tracing::warn!("Studio callback with unknown or expired state");
            return (StatusCode::BAD_REQUEST, "invalid state").into_response();
        }
    };

    let token = match studio.exchange_token(exchange_token).await {
        Ok(token) => token,
        Err(error) => {
            tracing::warn!(error = %error, "Studio exchange token failed");
            return (error.status_code(), "authentication failed").into_response();
        }
    };

    let token_cookie = format!(
        "{}={}; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age={}",
        SESSION_COOKIE,
        token,
        state.auth.sessions.ttl_secs(),
    );
    let state_clear = format!(
        "{}=; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=0",
        STUDIO_STATE_COOKIE
    );

    Response::builder()
        .status(StatusCode::FOUND)
        .header(header::SET_COOKIE, token_cookie)
        .header(header::SET_COOKIE, state_clear)
        .header(header::LOCATION, pending.return_path)
        .header(header::CACHE_CONTROL, "no-store")
        .header("referrer-policy", "no-referrer")
        .body(Body::empty())
        .unwrap_or_else(|_| (StatusCode::INTERNAL_SERVER_ERROR, "redirect failed").into_response())
}

pub async fn oauth2_logout(State(state): State<AppState>, headers: HeaderMap) -> Response {
    if let Some(session_id) = extract_named_cookie(&headers, SESSION_COOKIE) {
        state.auth.sessions.remove(&session_id).await;
    }

    let clear = format!(
        "{}=; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=0",
        SESSION_COOKIE
    );
    let target = state
        .auth
        .oauth2
        .as_ref()
        .map(|o| o.default_post_login_path().to_string())
        .unwrap_or_else(|| "/".to_string());
    Response::builder()
        .status(StatusCode::FOUND)
        .header(header::SET_COOKIE, clear)
        .header(header::LOCATION, target)
        .body(Body::empty())
        .unwrap()
}

async fn authenticate(
    state: &AppState,
    method: &GatewayAuthMethod,
    headers: &HeaderMap,
) -> Result<AuthenticatedUser, AuthError> {
    match method {
        GatewayAuthMethod::Jwt => {
            let token = extract_bearer_token(headers).ok_or(AuthError::Missing)?;
            let jwks = state
                .auth
                .jwks
                .as_ref()
                .ok_or_else(|| AuthError::Unavailable("JWT auth not configured".to_string()))?;
            jwks.validate_token(token).await
        }
        GatewayAuthMethod::Basic => {
            let (user, pass) = extract_basic_credentials(headers).ok_or(AuthError::Missing)?;
            let validator =
                state.auth.basic.as_ref().ok_or_else(|| {
                    AuthError::Unavailable("Basic auth not configured".to_string())
                })?;
            validator.validate(&user, &pass).await
        }
        GatewayAuthMethod::Oauth2 => {
            let session_value =
                extract_named_cookie(headers, SESSION_COOKIE).ok_or(AuthError::Missing)?;
            if let Some(session) = state.auth.sessions.get(&session_value).await {
                return Ok(session.user);
            }
            let validator = state.auth.basic.as_ref().ok_or_else(|| {
                AuthError::Unavailable("Bosca credential validation is not configured".to_string())
            })?;
            validator.validate_bearer(&session_value).await
        }
        GatewayAuthMethod::None => unreachable!(),
    }
}

fn extract_bearer_token(headers: &HeaderMap) -> Option<&str> {
    headers
        .get(header::AUTHORIZATION)?
        .to_str()
        .ok()?
        .strip_prefix("Bearer ")
}

/// RFC 7617 doesn't require padding in the base64 blob, and RFC 4648 §3.2
/// explicitly allows omitting it when the length is known. Several
/// real-world clients (and proxies between the client and us) emit
/// unpadded standard-alphabet base64. `general_purpose::STANDARD` uses
/// `DecodePaddingMode::RequireCanonical`, which silently rejects those
/// inputs — `extract_basic_credentials` returns `None`, the caller maps
/// that to `AuthError::Missing`, and the client sees a 401 with no
/// useful signal about what went wrong. Use a STANDARD-alphabet engine
/// configured to be indifferent to padding so we match every other
/// Basic-auth-accepting server.
static BASIC_AUTH_DECODER: base64::engine::GeneralPurpose = base64::engine::GeneralPurpose::new(
    &base64::alphabet::STANDARD,
    base64::engine::GeneralPurposeConfig::new()
        .with_decode_padding_mode(base64::engine::DecodePaddingMode::Indifferent),
);

fn extract_basic_credentials(headers: &HeaderMap) -> Option<(String, String)> {
    use base64::Engine;
    let value = headers.get(header::AUTHORIZATION)?.to_str().ok()?;
    let encoded = value.strip_prefix("Basic ")?;
    let decoded = String::from_utf8(BASIC_AUTH_DECODER.decode(encoded).ok()?).ok()?;
    let (user, pass) = decoded.split_once(':')?;
    Some((user.to_string(), pass.to_string()))
}

fn extract_named_cookie(headers: &HeaderMap, name: &str) -> Option<String> {
    let prefix = format!("{}=", name);
    let cookies = headers.get(header::COOKIE)?.to_str().ok()?;
    for cookie in cookies.split(';') {
        let cookie = cookie.trim();
        if let Some(value) = cookie.strip_prefix(prefix.as_str()) {
            if !value.is_empty() {
                return Some(value.to_string());
            }
        }
    }
    None
}

/// Rebuilds the browser's Cookie header for the upstream while removing the
/// gateway's private authentication and handoff cookies. Every non-gateway
/// cookie is preserved, including application auth, BML session, locale, and
/// future site cookies that the gateway does not know about.
fn forwarded_cookie_header(headers: &HeaderMap) -> Option<HeaderValue> {
    let mut forwarded = Vec::new();
    for value in headers.get_all(header::COOKIE) {
        let cookies = value.to_str().ok()?;
        for cookie in cookies
            .split(';')
            .map(str::trim)
            .filter(|cookie| !cookie.is_empty())
        {
            let name = cookie
                .split_once('=')
                .map_or(cookie, |(name, _)| name)
                .trim();
            if !name.starts_with(GATEWAY_COOKIE_PREFIX) {
                forwarded.push(cookie);
            }
        }
    }

    if forwarded.is_empty() {
        return None;
    }
    HeaderValue::from_str(&forwarded.join("; ")).ok()
}

/// Constant-time byte equality. State tokens are random and large but
/// using a timing-safe comparison here removes a class of side-channel
/// concerns without measurable cost.
fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut diff: u8 = 0;
    for i in 0..a.len() {
        diff |= a[i] ^ b[i];
    }
    diff == 0
}

fn auth_error_response(err: &AuthError, method: &GatewayAuthMethod) -> Response {
    let status = err.status_code();
    let mut resp = Response::builder().status(status);
    if status == StatusCode::UNAUTHORIZED {
        match method {
            GatewayAuthMethod::Basic => {
                resp = resp.header(header::WWW_AUTHENTICATE, "Basic realm=\"Bosca Gateway\"");
            }
            GatewayAuthMethod::Jwt => {
                resp = resp.header(header::WWW_AUTHENTICATE, "Bearer");
            }
            _ => {}
        }
    }
    resp.body(Body::from("authentication required"))
        .unwrap_or_else(|_| (StatusCode::INTERNAL_SERVER_ERROR, "error").into_response())
}

/// Compute the path to send upstream after applying the route's
/// `strip_prefix` flag.
///
/// * `Prefix("/api/foo")` matching `/api/foo/bar` with strip → `/bar`
/// * `Prefix("/api/foo")` matching `/api/foo` with strip → `/`
/// * `Exact("/api/foo")` with strip → the operator misconfigured this
///   route; an exact-match leaves nothing to strip. Rather than
///   silently rewriting to `/` (which sends the request to the wrong
///   upstream handler), we keep the original path and let the operator
///   see the upstream response unchanged. The misconfiguration should
///   be flagged at config-load time instead.
fn compute_upstream_path(path: &str, strip: bool, matcher: &PathMatcher) -> String {
    if !strip {
        return path.to_string();
    }
    let prefix = match matcher {
        PathMatcher::Exact(_) => return path.to_string(),
        PathMatcher::Prefix(p) => p.trim_end_matches('/'),
    };
    let stripped = path.strip_prefix(prefix).unwrap_or(path);
    if stripped.is_empty() {
        "/".to_string()
    } else {
        stripped.to_string()
    }
}

/// Rewrite an upstream `Location` header so a 3xx redirect points
/// back through the gateway instead of at the backend.
///
/// Four cases:
///
///  1. **Absolute URL whose `host:port` matches the upstream's.**
///     The upstream emitted an absolute self-link
///     (`Location: http://localhost:8080/foo`). Strip the prefix down
///     to path-and-query (`/foo`). Match is scheme-blind so an
///     upstream that forces HTTPS in its self-URL (Trino's auth
///     handler) still gets caught.
///
///  2. **Absolute URL whose `host:port` matches the gateway's own
///     inbound `Host` header.** The upstream picked up an
///     `X-Forwarded-Host` we sent it and re-stamped the gateway's
///     hostname into the Location, but with the *upstream's* preferred
///     scheme (typically HTTPS even when the client is on HTTP). Strip
///     to a relative URL so the user-agent resolves against whatever
///     scheme it actually dialed the gateway with.
///
///  3. **Relative URL** (`/foo`, `foo`, or `?q=1`). The user-agent
///     resolves against the gateway by default. Re-prepend the
///     stripped path prefix if applicable.
///
///  4. **Absolute URL to a third-party host** (OAuth IdP redirect, S3
///     pre-signed URL, CDN). Leave it alone — the upstream
///     deliberately sent the client off-platform.
///
/// `gateway_authority` is the raw inbound `Host` header (or HTTP/2
/// `:authority`) value, with port if present. Empty means "we don't
/// know what the client dialed" — in that case case 2 is skipped.
pub(super) fn rewrite_location(
    location: &str,
    upstream_url: &str,
    gateway_authority: &str,
    strip_prefix: bool,
    matcher: &PathMatcher,
) -> String {
    let upstream_base = upstream_url.trim_end_matches('/');
    let path_and_query = if let Some(rest) = strip_known_upstream_base(location, upstream_base) {
        if rest.is_empty() {
            "/".to_string()
        } else {
            rest.to_string()
        }
    } else if !gateway_authority.is_empty()
        && matches_authority(location, gateway_authority).is_some()
    {
        // Case 2: Location's host:port equals the gateway's own.
        // Strip to relative so the client uses the scheme it
        // originally dialed.
        let rest = matches_authority(location, gateway_authority).unwrap_or("");
        if rest.is_empty() {
            "/".to_string()
        } else {
            rest.to_string()
        }
    } else if looks_absolute(location) {
        // Case 4: absolute URL elsewhere — out of our problem space.
        return location.to_string();
    } else {
        // Case 3: relative URL.
        location.to_string()
    };

    if !strip_prefix {
        return path_and_query;
    }
    let prefix = match matcher {
        PathMatcher::Prefix(p) => p.trim_end_matches('/'),
        // Exact-match routes with strip_prefix are operator error —
        // [compute_upstream_path] documents this — so there's nothing
        // sensible to prepend.
        PathMatcher::Exact(_) => return path_and_query,
    };
    if prefix.is_empty() || !path_and_query.starts_with('/') {
        // Either nothing meaningful to prepend, or the Location is
        // protocol-relative / query-only — leave it alone to avoid
        // building a malformed URL like `/trinofoo` from `?q=1`.
        return path_and_query;
    }
    format!("{prefix}{path_and_query}")
}

/// `Some(rest)` if `location` is absolute and its authority (host +
/// port) byte-matches `upstream_base`'s. `rest` is the suffix starting
/// at the path (or empty if the location IS the base).
///
/// The match is **scheme-agnostic**: an upstream that emits
/// `https://host:port/...` when the proxy is configured with
/// `http://host:port` (Trino is the canonical offender) still gets
/// recognized as upstream-internal and rewritten. The trade-off is
/// that a legitimate cross-scheme redirect from the same host:port
/// (e.g. http→https upgrade) is also collapsed to a relative URL —
/// which we want: the client should follow the gateway's scheme, not
/// the upstream's preference.
fn strip_known_upstream_base<'a>(location: &'a str, upstream_base: &str) -> Option<&'a str> {
    let (_, upstream_authority, _) = split_origin(upstream_base)?;
    let (_, loc_authority, loc_rest) = split_origin(location)?;
    if upstream_authority.eq_ignore_ascii_case(loc_authority) {
        Some(loc_rest)
    } else {
        None
    }
}

/// `Some(path_and_query)` if `location` is absolute and its authority
/// byte-matches `expected_authority` (ASCII-case-insensitive).
/// Otherwise `None`.
fn matches_authority<'a>(location: &'a str, expected_authority: &str) -> Option<&'a str> {
    let (_, authority, rest) = split_origin(location)?;
    if authority.eq_ignore_ascii_case(expected_authority) {
        Some(rest)
    } else {
        None
    }
}

/// Split an absolute URL into `(scheme, authority, rest)`. `authority`
/// is the host[:port] portion (no userinfo handled — the proxy never
/// sees URLs with userinfo in practice, and ambiguous parsing here
/// would be worse than rejecting them). `rest` is everything from the
/// first `/`, `?`, or `#` onwards, or `""` if the URL ends at the
/// authority.
///
/// Returns `None` for relative or protocol-relative URLs.
fn split_origin(url: &str) -> Option<(&str, &str, &str)> {
    let scheme_end = url.find("://")?;
    let scheme = &url[..scheme_end];
    if scheme.is_empty() || !scheme.bytes().next()?.is_ascii_alphabetic() {
        return None;
    }
    let after_scheme = &url[scheme_end + 3..];
    // Authority ends at the first `/`, `?`, or `#` — whichever comes
    // first. None of those → the whole remainder is authority.
    let authority_end = after_scheme
        .find(['/', '?', '#'])
        .unwrap_or(after_scheme.len());
    let authority = &after_scheme[..authority_end];
    let rest = &after_scheme[authority_end..];
    Some((scheme, authority, rest))
}

/// True for any value that looks absolute: scheme-qualified
/// (`http://...`, `https://...`) or protocol-relative (`//host/path`).
/// We do not parse — we only need to discriminate "absolute to
/// somewhere we don't recognize" from "relative".
fn looks_absolute(location: &str) -> bool {
    if location.starts_with("//") {
        return true;
    }
    // RFC 3986 §3.1 scheme = ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )
    let bytes = location.as_bytes();
    if bytes.is_empty() || !bytes[0].is_ascii_alphabetic() {
        return false;
    }
    for (i, &b) in bytes.iter().enumerate().skip(1) {
        if b == b':' {
            return i > 0;
        }
        if !(b.is_ascii_alphanumeric() || b == b'+' || b == b'-' || b == b'.') {
            return false;
        }
    }
    false
}

/// Reconstruct the original path-and-query the client requested so we
/// can return them there after the OAuth2 flow completes. We never
/// trust an arbitrary `?return=` parameter — the path comes from the
/// URI the client actually hit, and the OAuth2 handler validates it
/// against open-redirect rules.
fn original_return_path(uri: &Uri) -> String {
    let mut path = uri.path().to_string();
    if let Some(q) = uri.query() {
        path.push('?');
        path.push_str(q);
    }
    path
}

/// Insert a forwarded-style header onto the outbound request iff the
/// value isn't empty. We never want to emit `X-Forwarded-Proto: `
/// with an empty payload — that's a malformed header that some
/// upstreams reject outright and others interpret in surprising ways.
fn insert_forwarded_header(headers: &mut HeaderMap, name: &str, value: &str) {
    if value.is_empty() {
        return;
    }
    if let (Ok(name), Ok(val)) = (
        HeaderName::from_bytes(name.as_bytes()),
        HeaderValue::from_str(value),
    ) {
        headers.insert(name, val);
    }
}

/// Collapse runs of `/` in a path to a single `/`. `"//api//foo"`
/// becomes `"/api/foo"`. Returns the input untouched when no
/// collapsing is needed to avoid a per-request allocation on the
/// happy path.
fn collapse_slashes(path: &str) -> String {
    if !path.contains("//") {
        return path.to_string();
    }
    let mut out = String::with_capacity(path.len());
    let mut last_was_slash = false;
    for c in path.chars() {
        if c == '/' {
            if !last_was_slash {
                out.push('/');
            }
            last_was_slash = true;
        } else {
            out.push(c);
            last_was_slash = false;
        }
    }
    out
}

/// Emit a structured access-log line for one completed request. Level is
/// picked from the status code:
///
///  - 2xx / 3xx → `info`
///  - 4xx → `warn` (auth failures, forbids, no-route — visible at the
///    default `info` filter without needing `RUST_LOG=debug`)
///  - 5xx → `error`
///
/// `reason` is the human-readable explanation for non-success paths
/// (the AuthError variant, "no matching route", etc.). On the success
/// path it stays empty so the line isn't noisy.
#[allow(clippy::too_many_arguments)]
fn log_outcome(
    started: Instant,
    method: &str,
    path: &str,
    client_ip: &str,
    status: StatusCode,
    upstream: Option<&str>,
    subject: Option<&str>,
    auth_method: Option<&GatewayAuthMethod>,
    reason: Option<&str>,
) {
    let duration_ms = started.elapsed().as_millis() as u64;
    let code = status.as_u16();
    let upstream = upstream.unwrap_or("-");
    let subject = subject.unwrap_or("-");
    let auth_method = auth_method.map(auth_method_label).unwrap_or("-");
    let reason = reason.unwrap_or("");

    if status.is_server_error() {
        tracing::error!(
            method,
            path,
            status = code,
            duration_ms,
            client_ip,
            upstream,
            subject,
            auth_method,
            reason,
            "request"
        );
    } else if status.is_client_error() {
        tracing::warn!(
            method,
            path,
            status = code,
            duration_ms,
            client_ip,
            upstream,
            subject,
            auth_method,
            reason,
            "request"
        );
    } else {
        tracing::info!(
            method,
            path,
            status = code,
            duration_ms,
            client_ip,
            upstream,
            subject,
            auth_method,
            reason,
            "request"
        );
    }
}

fn auth_method_label(m: &GatewayAuthMethod) -> &'static str {
    match m {
        GatewayAuthMethod::Jwt => "jwt",
        GatewayAuthMethod::Basic => "basic",
        GatewayAuthMethod::Oauth2 => "oauth2",
        GatewayAuthMethod::None => "none",
    }
}

#[cfg(test)]
mod tests {
    use std::collections::HashMap;
    use std::net::SocketAddr;
    use std::sync::Arc;

    use arc_swap::ArcSwap;
    use axum::extract::{ConnectInfo, Query, State};

    use crate::auth::{AuthState, StudioAuthHandler};
    use crate::config::{BootstrapConfig, Gateway, GatewayConfig, GatewayRoute};
    use crate::metrics::Metrics;
    use crate::proxy::TrustedProxies;

    use super::*;

    fn studio_bootstrap() -> BootstrapConfig {
        toml::from_str(
            r#"
[gateway]
bind = "127.0.0.1:9999"
studio_url = "https://studio.example.com"

[bosca]
api_url = "https://api.example.com"
api_token = "test"

"#,
        )
        .unwrap()
    }

    fn studio_gateway_config() -> GatewayConfig {
        GatewayConfig {
            version: "1".into(),
            services: vec![Gateway {
                id: "warehouse".into(),
                name: "warehouse".into(),
                url: "http://127.0.0.1:1".into(),
                health_check_path: None,
                health_check_interval_secs: 30,
                connect_timeout_secs: 5,
                request_timeout_secs: 30,
                pool_max_idle: 10,
                pool_idle_timeout_secs: 90,
                enabled: true,
            }],
            routes: vec![GatewayRoute {
                id: "warehouse-route".into(),
                gateway_id: "warehouse".into(),
                path_pattern: "/ui/**".into(),
                hosts: vec![],
                auth_method: GatewayAuthMethod::Oauth2,
                strip_prefix: false,
                read_groups: vec!["users".into()],
                write_groups: vec!["users".into()],
                inject_headers: HashMap::new(),
                sort_order: 0,
                enabled: true,
            }],
            compiled_routes: vec![],
            gateway_map: HashMap::new(),
        }
        .compile()
    }

    fn studio_app_state() -> AppState {
        let bootstrap = studio_bootstrap();
        let client = reqwest::Client::new();
        let mut auth = AuthState::new(&bootstrap, client.clone()).unwrap();
        auth.studio = Some(StudioAuthHandler::with_mock_token(
            "https://studio.example.com",
            "https://api.example.com",
            "issued-jwt",
        ));
        AppState {
            config: Arc::new(ArcSwap::from_pointee(studio_gateway_config())),
            bootstrap: Arc::new(bootstrap),
            http_client: client,
            auth,
            metrics: Metrics::new().unwrap(),
            trusted_proxies: TrustedProxies::default(),
            fallback_scheme: "https".into(),
            fallback_port: "443".into(),
        }
    }

    fn cookie_value(response: &Response, name: &str) -> Option<String> {
        response
            .headers()
            .get_all(header::SET_COOKIE)
            .iter()
            .filter_map(|value| value.to_str().ok())
            .find_map(|cookie| {
                cookie
                    .strip_prefix(&format!("{name}="))
                    .and_then(|rest| rest.split(';').next())
                    .filter(|value| !value.is_empty())
                    .map(str::to_string)
            })
    }

    #[tokio::test]
    async fn unauthenticated_interactive_route_round_trips_through_studio() {
        let state = studio_app_state();
        let mut request_headers = HeaderMap::new();
        request_headers.insert(header::HOST, "warehouse.example.com".parse().unwrap());
        let response = proxy_request(
            State(state.clone()),
            ConnectInfo("127.0.0.1:45123".parse::<SocketAddr>().unwrap()),
            Method::GET,
            "/ui/query?tab=history".parse::<Uri>().unwrap(),
            request_headers,
            Body::empty(),
        )
        .await;

        assert_eq!(response.status(), StatusCode::SEE_OTHER);
        let location = response
            .headers()
            .get(header::LOCATION)
            .unwrap()
            .to_str()
            .unwrap();
        let studio_url = reqwest::Url::parse(location).unwrap();
        assert_eq!(
            studio_url.origin().ascii_serialization(),
            "https://studio.example.com"
        );
        assert_eq!(studio_url.path(), "/auth/gateway");
        let redirect = studio_url
            .query_pairs()
            .find(|(key, _)| key == "redirect")
            .map(|(_, value)| value.into_owned())
            .unwrap();
        let redirect = reqwest::Url::parse(&redirect).unwrap();
        assert_eq!(
            redirect.origin().ascii_serialization(),
            "https://warehouse.example.com"
        );
        assert_eq!(redirect.path(), "/auth/studio/callback");
        let state_token = redirect
            .query_pairs()
            .find(|(key, _)| key == "state")
            .map(|(_, value)| value.into_owned())
            .unwrap();
        assert_eq!(
            cookie_value(&response, STUDIO_STATE_COOKIE).as_deref(),
            Some(state_token.as_str())
        );

        let mut callback_headers = HeaderMap::new();
        callback_headers.insert(
            header::COOKIE,
            format!("{STUDIO_STATE_COOKIE}={state_token}")
                .parse()
                .unwrap(),
        );
        let callback = studio_callback(
            State(state.clone()),
            Query(HashMap::from([
                ("state".to_string(), state_token),
                ("exchangeToken".to_string(), "one-time-token".to_string()),
            ])),
            callback_headers,
        )
        .await;

        assert_eq!(callback.status(), StatusCode::FOUND);
        assert_eq!(
            callback.headers().get(header::LOCATION).unwrap(),
            "/ui/query?tab=history"
        );
        assert_eq!(
            callback.headers().get("referrer-policy").unwrap(),
            "no-referrer"
        );
        assert_eq!(
            cookie_value(&callback, SESSION_COOKIE).as_deref(),
            Some("issued-jwt")
        );
    }

    #[tokio::test]
    async fn studio_callback_rejects_a_browser_state_mismatch() {
        let state = studio_app_state();
        let studio = state.auth.studio.as_ref().unwrap();
        let start = studio
            .begin_authorization(
                "https://warehouse.example.com/auth/studio/callback",
                "/ui/query",
            )
            .await
            .unwrap();
        let mut headers = HeaderMap::new();
        headers.insert(
            header::COOKIE,
            format!("{STUDIO_STATE_COOKIE}=different-state")
                .parse()
                .unwrap(),
        );

        let callback = studio_callback(
            State(state),
            Query(HashMap::from([
                ("state".to_string(), start.state),
                ("exchangeToken".to_string(), "one-time-token".to_string()),
            ])),
            headers,
        )
        .await;

        assert_eq!(callback.status(), StatusCode::BAD_REQUEST);
        assert!(cookie_value(&callback, SESSION_COOKIE).is_none());
    }

    fn prefix_matcher(p: &str) -> PathMatcher {
        PathMatcher::Prefix(p.to_string())
    }

    #[test]
    fn strip_prefix_removes_matched_prefix() {
        assert_eq!(
            compute_upstream_path("/api/foo/bar", true, &prefix_matcher("/api/foo")),
            "/bar"
        );
        assert_eq!(
            compute_upstream_path("/api/foo", true, &prefix_matcher("/api/foo")),
            "/"
        );
    }

    #[test]
    fn no_strip_keeps_path() {
        assert_eq!(
            compute_upstream_path("/api/foo/bar", false, &prefix_matcher("/api/foo")),
            "/api/foo/bar"
        );
    }

    #[test]
    fn exact_match_with_strip_keeps_path_unchanged() {
        // Exact match + strip is operator error — there's nothing to
        // strip from an exact match. Keep the path so the upstream
        // receives what it expects rather than silently rewriting to /.
        assert_eq!(
            compute_upstream_path(
                "/api/foo",
                true,
                &PathMatcher::Exact("/api/foo".to_string())
            ),
            "/api/foo"
        );
    }

    #[test]
    fn forwarded_headers_are_classified() {
        assert!(is_forwarded_header("forwarded"));
        assert!(is_forwarded_header("x-forwarded-for"));
        assert!(is_forwarded_header("x-forwarded-proto"));
        assert!(is_forwarded_header("x-real-ip"));
        assert!(!is_forwarded_header("x-trino-user"));
        assert!(!is_forwarded_header("authorization"));
    }

    #[test]
    fn constant_time_eq_handles_inputs() {
        assert!(constant_time_eq(b"abc", b"abc"));
        assert!(!constant_time_eq(b"abc", b"abd"));
        assert!(!constant_time_eq(b"abc", b"ab"));
        assert!(constant_time_eq(b"", b""));
    }

    #[test]
    fn is_read_method_classifies_safe_methods() {
        assert!(is_read_method(&Method::GET));
        assert!(is_read_method(&Method::HEAD));
        assert!(is_read_method(&Method::OPTIONS));
        // Mutating verbs MUST be treated as writes.
        assert!(!is_read_method(&Method::POST));
        assert!(!is_read_method(&Method::PUT));
        assert!(!is_read_method(&Method::PATCH));
        assert!(!is_read_method(&Method::DELETE));
        // Unusual verbs are conservatively classified as writes so an
        // operator who hasn't enumerated them in `read_groups` doesn't
        // see them slip past read-only gating.
        assert!(!is_read_method(&Method::CONNECT));
        assert!(!is_read_method(&Method::TRACE));
    }

    #[test]
    fn collapse_slashes_normalizes_double_slashes() {
        assert_eq!(collapse_slashes("/api/foo"), "/api/foo");
        assert_eq!(collapse_slashes("//api/foo"), "/api/foo");
        assert_eq!(collapse_slashes("/api//foo"), "/api/foo");
        assert_eq!(collapse_slashes("/api///foo//bar"), "/api/foo/bar");
        assert_eq!(collapse_slashes(""), "");
    }

    #[test]
    fn extract_named_cookie_finds_value() {
        let mut h = HeaderMap::new();
        h.insert(
            header::COOKIE,
            "a=1; __Host-gateway_session=xyz; b=2".parse().unwrap(),
        );
        assert_eq!(
            extract_named_cookie(&h, "__Host-gateway_session"),
            Some("xyz".to_string())
        );
        assert_eq!(extract_named_cookie(&h, "missing"), None);
    }

    #[test]
    fn forwarded_cookie_header_strips_gateway_cookies_and_preserves_the_rest() {
        let mut headers = HeaderMap::new();
        headers.append(
            header::COOKIE,
            "_bat_preview=access-token; __Host-gateway_session=gateway-token; bml_locale=es"
                .parse()
                .unwrap(),
        );
        headers.append(
            header::COOKIE,
            "__Host-gateway_studio_state=state; bml_session=session-id; preference=a=b"
                .parse()
                .unwrap(),
        );

        let forwarded = forwarded_cookie_header(&headers).unwrap();
        assert_eq!(
            forwarded.to_str().unwrap(),
            "_bat_preview=access-token; bml_locale=es; bml_session=session-id; preference=a=b"
        );
    }

    #[test]
    fn forwarded_cookie_header_omits_header_when_only_gateway_cookies_remain() {
        let mut headers = HeaderMap::new();
        headers.insert(
            header::COOKIE,
            "__Host-gateway_session=gateway-token; __Host-gateway_oauth2_state=state"
                .parse()
                .unwrap(),
        );

        assert!(forwarded_cookie_header(&headers).is_none());
    }

    #[test]
    fn forwarded_cookie_header_is_absent_without_browser_cookies() {
        assert!(forwarded_cookie_header(&HeaderMap::new()).is_none());
    }

    // ---------------------------------------------------------------
    // Basic auth credential extraction
    // ---------------------------------------------------------------

    fn auth_header(value: &str) -> HeaderMap {
        let mut h = HeaderMap::new();
        h.insert(header::AUTHORIZATION, value.parse().unwrap());
        h
    }

    /// Encode `creds` with the canonical STANDARD engine (padded). Used
    /// by the round-trip tests below so the test isn't sensitive to
    /// hand-computed encodings of specific strings.
    fn b64_padded(creds: &str) -> String {
        use base64::Engine;
        base64::engine::general_purpose::STANDARD.encode(creds)
    }

    #[test]
    fn extract_basic_accepts_padded_standard_base64() {
        // 16-byte credential ⇒ canonical encoding ends in "==".
        let encoded = b64_padded("api_token:bsk_xx");
        assert!(
            encoded.ends_with("=="),
            "test precondition: padded form must include canonical padding"
        );
        let h = auth_header(&format!("Basic {encoded}"));
        let (u, p) = extract_basic_credentials(&h).expect("padded decode");
        assert_eq!(u, "api_token");
        assert_eq!(p, "bsk_xx");
    }

    #[test]
    fn extract_basic_accepts_unpadded_standard_base64() {
        // Same credentials, but with the canonical `=` padding stripped
        // — the form some HTTP clients and intermediaries emit per
        // RFC 4648 §3.2. The strict `STANDARD` engine rejects this,
        // which surfaced as JDBC clients seeing 401 "authentication
        // required" with no useful signal. The indifferent-padding
        // engine accepts both forms; this test pins that behavior.
        let encoded = b64_padded("api_token:bsk_xx");
        let stripped = encoded.trim_end_matches('=');
        assert_ne!(
            stripped, encoded,
            "test precondition: must actually strip padding"
        );
        let h = auth_header(&format!("Basic {stripped}"));
        let (u, p) = extract_basic_credentials(&h).expect("unpadded decode");
        assert_eq!(u, "api_token");
        assert_eq!(p, "bsk_xx");
    }

    #[test]
    fn extract_basic_rejects_non_basic_scheme() {
        let h = auth_header("Bearer abc.def.ghi");
        assert!(extract_basic_credentials(&h).is_none());
    }

    #[test]
    fn extract_basic_rejects_garbage_base64() {
        let h = auth_header("Basic !!!not-base64!!!");
        assert!(extract_basic_credentials(&h).is_none());
    }

    #[test]
    fn extract_basic_rejects_decoded_without_colon() {
        let encoded = b64_padded("nopassword");
        let h = auth_header(&format!("Basic {encoded}"));
        assert!(extract_basic_credentials(&h).is_none());
    }

    // ---------------------------------------------------------------
    // Location rewriting on 3xx responses
    // ---------------------------------------------------------------

    #[test]
    fn rewrite_strips_upstream_origin_to_make_redirect_relative() {
        // Upstream said "go to http://upstream:8080/next" — the client
        // would dial the backend directly. We rewrite to a relative
        // path so the browser resolves against the gateway origin.
        let out = rewrite_location(
            "http://upstream:8080/next",
            "http://upstream:8080",
            "",
            false,
            &prefix_matcher("/api"),
        );
        assert_eq!(out, "/next");
    }

    #[test]
    fn rewrite_handles_upstream_url_with_trailing_slash() {
        // The configured upstream URL might end with `/`. The strip
        // logic should still produce a clean path.
        let out = rewrite_location(
            "http://upstream:8080/next",
            "http://upstream:8080/",
            "",
            false,
            &prefix_matcher("/api"),
        );
        assert_eq!(out, "/next");
    }

    #[test]
    fn rewrite_preserves_query_and_fragment() {
        let out = rewrite_location(
            "http://upstream:8080/next?token=abc#section",
            "http://upstream:8080",
            "",
            false,
            &prefix_matcher("/api"),
        );
        assert_eq!(out, "/next?token=abc#section");
    }

    #[test]
    fn rewrite_re_prepends_stripped_prefix_on_relative_location() {
        // Route `/trino/**` with strip_prefix → upstream sees `/foo`
        // and returns `Location: /bar`. The client knows nothing
        // about `/trino`; we have to re-attach the prefix.
        let out = rewrite_location(
            "/bar",
            "http://trino:8080",
            "",
            true,
            &prefix_matcher("/trino"),
        );
        assert_eq!(out, "/trino/bar");
    }

    #[test]
    fn rewrite_re_prepends_stripped_prefix_on_absolute_self_location() {
        // The upstream sent an absolute self-link `http://trino:8080/foo/bar`
        // through a route with strip_prefix=`/trino`. After the origin
        // strip we have `/foo/bar`; re-attach the prefix.
        let out = rewrite_location(
            "http://trino:8080/foo/bar",
            "http://trino:8080",
            "",
            true,
            &prefix_matcher("/trino"),
        );
        assert_eq!(out, "/trino/foo/bar");
    }

    #[test]
    fn rewrite_leaves_third_party_absolute_location_alone() {
        // OAuth provider redirect — we MUST NOT eat the host. The
        // user-agent has to dial the IdP directly.
        let out = rewrite_location(
            "https://auth.example.com/oauth2/authorize?client_id=x",
            "http://upstream:8080",
            "",
            true,
            &prefix_matcher("/api"),
        );
        assert_eq!(out, "https://auth.example.com/oauth2/authorize?client_id=x");
    }

    #[test]
    fn rewrite_leaves_protocol_relative_third_party_location_alone() {
        let out = rewrite_location(
            "//cdn.example.com/asset.js",
            "http://upstream:8080",
            "",
            false,
            &prefix_matcher("/api"),
        );
        assert_eq!(out, "//cdn.example.com/asset.js");
    }

    #[test]
    fn rewrite_strips_upstream_when_only_scheme_differs() {
        // Trino's canonical bug: configured as `http://localhost:8089`
        // but emits `https://localhost:8089/ui/` regardless of
        // X-Forwarded-Proto. Match on host:port and rewrite anyway.
        let out = rewrite_location(
            "https://localhost:8089/ui/",
            "http://localhost:8089",
            "",
            false,
            &prefix_matcher("/trino"),
        );
        assert_eq!(out, "/ui/");
    }

    #[test]
    fn rewrite_does_not_match_when_port_differs() {
        let out = rewrite_location(
            "http://localhost:9999/x",
            "http://localhost:8089",
            "",
            false,
            &prefix_matcher("/trino"),
        );
        assert_eq!(out, "http://localhost:9999/x");
    }

    #[test]
    fn rewrite_authority_match_is_case_insensitive_on_host() {
        let out = rewrite_location(
            "https://LOCALHOST:8089/ui/",
            "http://localhost:8089",
            "",
            false,
            &prefix_matcher("/trino"),
        );
        assert_eq!(out, "/ui/");
    }

    #[test]
    fn rewrite_does_not_match_on_substring_host_collision() {
        // upstream_url = `http://api.foo` and Location = `http://api.foobar/x`.
        // A naive `strip_prefix` would treat the second as belonging
        // to the first; we must not. Result must remain unchanged.
        let out = rewrite_location(
            "http://api.foobar/x",
            "http://api.foo",
            "",
            false,
            &prefix_matcher("/api"),
        );
        assert_eq!(out, "http://api.foobar/x");
    }

    #[test]
    fn rewrite_leaves_relative_location_alone_when_no_strip_prefix() {
        let out = rewrite_location(
            "/already/correct",
            "http://upstream:8080",
            "",
            false,
            &prefix_matcher("/api"),
        );
        assert_eq!(out, "/already/correct");
    }

    #[test]
    fn rewrite_query_only_relative_passes_through() {
        // `Location: ?reload=1` — the browser resolves this against
        // the request URL, which is the gateway path. Re-prepending
        // `/api` here would produce a nonsensical `/api?reload=1`.
        let out = rewrite_location(
            "?reload=1",
            "http://upstream:8080",
            "",
            true,
            &prefix_matcher("/api"),
        );
        assert_eq!(out, "?reload=1");
    }

    #[test]
    fn rewrite_root_redirect_on_upstream_becomes_slash() {
        // Upstream said "Location: http://upstream:8080" (no path).
        let out = rewrite_location(
            "http://upstream:8080",
            "http://upstream:8080",
            "",
            false,
            &prefix_matcher("/api"),
        );
        assert_eq!(out, "/");
    }

    #[test]
    fn rewrite_root_with_strip_becomes_just_the_prefix() {
        // Same as above with strip_prefix=true → we should land back
        // at the route's prefix root.
        let out = rewrite_location(
            "http://trino:8080/",
            "http://trino:8080",
            "",
            true,
            &prefix_matcher("/trino"),
        );
        assert_eq!(out, "/trino/");
    }

    #[test]
    fn rewrite_exact_match_route_does_not_prepend() {
        // Exact matches with strip_prefix are operator error; we keep
        // the rewritten path unchanged rather than producing garbage.
        let out = rewrite_location(
            "/somewhere",
            "http://upstream:8080",
            "",
            true,
            &PathMatcher::Exact("/health".to_string()),
        );
        assert_eq!(out, "/somewhere");
    }

    #[test]
    fn rewrite_strips_location_pointing_at_the_gateway_itself() {
        // Trino picks up X-Forwarded-Host from us, but its auth
        // handler forces HTTPS regardless of X-Forwarded-Proto, so it
        // emits a Location whose host:port is the *gateway's* own
        // (because that's what we told it via X-Forwarded-Host) but
        // with the wrong scheme. Strip to relative so the client
        // follows on whatever scheme it dialed the gateway with.
        let out = rewrite_location(
            "https://127.0.0.1:9999/ui/",
            "http://localhost:8089",
            "127.0.0.1:9999",
            false,
            &prefix_matcher("/"),
        );
        assert_eq!(out, "/ui/");
    }

    #[test]
    fn gateway_authority_strip_respects_strip_prefix() {
        let out = rewrite_location(
            "https://gw.example/ui/",
            "http://trino:8089",
            "gw.example",
            true,
            &prefix_matcher("/trino"),
        );
        assert_eq!(out, "/trino/ui/");
    }

    #[test]
    fn empty_gateway_authority_skips_the_self_match() {
        // The handler passes `""` when it can't observe an inbound
        // Host header. The self-match must be skipped so we don't
        // strip every URL with an empty authority off some malformed
        // input.
        let out = rewrite_location(
            "https://example.com/ui/",
            "http://upstream:8089",
            "",
            false,
            &prefix_matcher("/"),
        );
        assert_eq!(out, "https://example.com/ui/");
    }

    #[test]
    fn looks_absolute_classifies_correctly() {
        assert!(looks_absolute("http://example.com/"));
        assert!(looks_absolute("https://example.com/"));
        assert!(looks_absolute("//example.com/"));
        assert!(looks_absolute("ftp://example.com/"));
        assert!(!looks_absolute("/path"));
        assert!(!looks_absolute("path"));
        assert!(!looks_absolute("?q=1"));
        assert!(!looks_absolute(""));
        // Has a `:` but it isn't a scheme — first char is digit.
        assert!(!looks_absolute("1:bad"));
    }
}
