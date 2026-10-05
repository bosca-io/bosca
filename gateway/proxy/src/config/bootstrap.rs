use std::net::SocketAddr;
use std::path::PathBuf;

use serde::Deserialize;

#[derive(Clone, Debug, Deserialize)]
pub struct BootstrapConfig {
    pub gateway: GatewaySettings,
    pub bosca: BoscaSettings,
    /// All three auth providers are optional. A deployment that only
    /// uses JWT bearer auth can omit `[auth.oauth2]` and `[auth.basic]`
    /// entirely. Omitting the whole `[auth]` table is valid when every
    /// configured route uses `auth_method = "none"`.
    #[serde(default)]
    pub auth: AuthProviders,
}

#[derive(Clone, Debug, Deserialize)]
pub struct GatewaySettings {
    #[serde(default = "default_bind")]
    pub bind: String,
    #[serde(default = "default_drain_timeout")]
    pub drain_timeout_secs: u64,
    /// Public origin of Studio. When configured, interactive routes hand
    /// unauthenticated browsers to Studio and accept the resulting
    /// single-use exchange token on the gateway callback.
    pub studio_url: Option<String>,
    /// CIDRs whose `X-Forwarded-*` headers the proxy *trusts*. The
    /// canonical deployment is "Cilium / nginx / ALB sits in front and
    /// hands us the real client metadata via X-Forwarded-Proto / Host /
    /// For; that hop's pod-or-instance CIDR goes in this list."
    ///
    /// When the peer of an inbound connection is contained in any of
    /// these CIDRs, the proxy reads `X-Forwarded-Proto` / `Host` /
    /// `For` from the request to populate the `{{request.*}}` template
    /// variables and the outbound X-Forwarded-* it stamps onto the
    /// upstream call. When the peer is NOT trusted, the proxy ignores
    /// inbound forwarded-style headers (defending against client
    /// spoofing) and falls back to the bind/peer view.
    ///
    /// Empty list = no upstream hop is trusted, i.e. every connection
    /// is treated as a direct client. That's the right setting when
    /// the proxy is the edge.
    #[serde(default)]
    pub trusted_proxies: Vec<String>,
    /// Scheme to claim in `X-Forwarded-Proto` (and the
    /// `{{request.scheme}}` template variable) when no trusted-proxy
    /// hop provided one. Useful when the proxy is bound to plain HTTP
    /// inside a cluster but TLS is terminated upstream by an
    /// L4/L7 LB that does NOT send `X-Forwarded-Proto`.
    ///
    /// When set together with a populated `trusted_proxies`, the
    /// trusted hop's value wins for that request — `external_scheme`
    /// is the fallback only.
    pub external_scheme: Option<String>,
}

impl GatewaySettings {
    pub fn bind_addr(&self) -> anyhow::Result<SocketAddr> {
        self.bind
            .parse()
            .map_err(|e| anyhow::anyhow!("invalid bind address '{}': {}", self.bind, e))
    }
}

#[derive(Clone, Debug, Deserialize)]
pub struct BoscaSettings {
    pub api_url: String,
    pub api_token: String,
    #[serde(default = "default_poll_interval")]
    pub poll_interval_secs: u64,
    #[serde(default = "default_cache_file")]
    pub cache_file: String,
}

#[derive(Clone, Debug, Default, Deserialize)]
pub struct AuthProviders {
    pub oauth2: Option<OAuth2Settings>,
    pub jwt: Option<JwtSettings>,
    pub basic: Option<BasicSettings>,
}

#[derive(Clone, Debug, Deserialize)]
pub struct OAuth2Settings {
    pub issuer: String,
    pub authorization_endpoint: Option<String>,
    pub token_endpoint: Option<String>,
    /// Userinfo URL. Optional — defaults to issuer + `/userinfo` (which
    /// works for most OIDC providers but NOT for ones that publish a
    /// different `userinfo_endpoint` in discovery, such as Google).
    /// Explicitly set this if your provider's discovery document
    /// disagrees with the default.
    pub userinfo_endpoint: Option<String>,
    pub client_id: String,
    pub client_secret: String,
    pub redirect_uri: String,
    #[serde(default = "default_scopes")]
    pub scopes: Vec<String>,
    #[serde(default = "default_session_ttl")]
    pub session_ttl_secs: u64,
    /// Where to send the user after successful authentication when no
    /// `state`-bound return URL is available. Must be an absolute path
    /// on this gateway (e.g. `/`); arbitrary URLs are rejected to prevent
    /// open-redirect attacks via crafted OAuth2 links.
    #[serde(default = "default_post_login_path")]
    pub default_post_login_path: String,
    /// How long a pending OAuth2 flow (state + nonce + PKCE verifier)
    /// stays valid. Tighter is safer — most real users complete the
    /// IdP redirect in well under a minute. Default 300s.
    #[serde(default = "default_pending_flow_ttl")]
    pub pending_flow_ttl_secs: u64,
}

#[derive(Clone, Debug, Deserialize)]
pub struct JwtSettings {
    pub issuer: String,
    pub audience: String,
    pub jwks_uri: String,
    /// How long fetched JWKS keys stay in the in-memory key cache.
    /// Lower values pick up key rotations faster at the cost of more
    /// JWKS fetches.
    #[serde(default = "default_jwks_cache_ttl")]
    pub jwks_cache_ttl_secs: u64,
    /// Escape hatch for environments that cannot serve JWKS over TLS —
    /// e.g. an in-cluster bosca-server that hasn't been wired to a
    /// service-mesh / cert-manager yet, or a local dev loop. Defaults
    /// to false; flipping it on emits a startup warning so the relaxed
    /// posture stays visible in logs.
    #[serde(default)]
    pub allow_insecure_jwks_uri: bool,
    /// How long a successful JWT validation is cached so back-to-back
    /// requests from the same client don't re-validate the signature
    /// on every hop. Should be a small fraction of token lifetime —
    /// the default 60s is well below any reasonable access token TTL.
    #[serde(default = "default_jwt_validation_cache_ttl")]
    pub jwt_validation_cache_ttl_secs: u64,
}

#[derive(Clone, Debug, Deserialize)]
pub struct BasicSettings {
    pub validation_endpoint: String,
    #[serde(default = "default_basic_cache_ttl")]
    pub cache_ttl_secs: u64,
}

impl BootstrapConfig {
    pub fn load(path: &PathBuf) -> anyhow::Result<Self> {
        let raw = std::fs::read_to_string(path)
            .map_err(|e| anyhow::anyhow!("read {}: {}", path.display(), e))?;
        let expanded = expand_env_vars(&raw)?;
        let cfg: Self = toml::from_str(&expanded)
            .map_err(|e| anyhow::anyhow!("parse {}: {}", path.display(), e))?;
        cfg.validate()?;
        Ok(cfg)
    }

    fn validate(&self) -> anyhow::Result<()> {
        if let Some(jwt) = &self.auth.jwt {
            if !jwt.jwks_uri.starts_with("https://") {
                if !jwt.allow_insecure_jwks_uri {
                    anyhow::bail!(
                        "auth.jwt.jwks_uri must use https:// (got {}). JWKS keys decide who is \
                         authenticated, so fetching them over plaintext lets a network attacker \
                         forge signing keys. Set auth.jwt.allow_insecure_jwks_uri = true to \
                         override (e.g. for in-cluster bosca-server before TLS is wired up).",
                        jwt.jwks_uri
                    );
                }
                tracing::warn!(
                    jwks_uri = %jwt.jwks_uri,
                    "auth.jwt.allow_insecure_jwks_uri is enabled — JWKS fetched over plaintext; \
                     a network attacker on the path between the gateway and the JWKS host can \
                     forge signing keys. Move to https as soon as the upstream supports it."
                );
            }
        }
        if let Some(oauth2) = &self.auth.oauth2 {
            if !oauth2.default_post_login_path.starts_with('/')
                || oauth2.default_post_login_path.starts_with("//")
            {
                anyhow::bail!(
                    "auth.oauth2.default_post_login_path must be an absolute path on this gateway \
                     (e.g. '/'), got '{}'",
                    oauth2.default_post_login_path
                );
            }
        }
        if let Some(studio_url) = &self.gateway.studio_url {
            let parsed = reqwest::Url::parse(studio_url)
                .map_err(|e| anyhow::anyhow!("gateway.studio_url is invalid: {e}"))?;
            if !matches!(parsed.scheme(), "http" | "https") {
                anyhow::bail!(
                    "gateway.studio_url must use http:// or https://, got '{}'",
                    studio_url
                );
            }
            if !parsed.username().is_empty() || parsed.password().is_some() {
                anyhow::bail!("gateway.studio_url must not contain credentials");
            }
            if parsed.path() != "/" || parsed.query().is_some() || parsed.fragment().is_some() {
                anyhow::bail!(
                    "gateway.studio_url must be an origin without a path, query, or fragment"
                );
            }
        }
        if let Some(scheme) = &self.gateway.external_scheme {
            if scheme != "http" && scheme != "https" {
                anyhow::bail!("gateway.external_scheme must be 'http' or 'https', got '{scheme}'",);
            }
        }
        // Validate CIDR shape early so a typo doesn't silently disable
        // every trusted-hop check (which would degrade the proxy back
        // to "treat everyone as direct client" — invisible at boot,
        // visible only as wrong audit IPs in production).
        for cidr in &self.gateway.trusted_proxies {
            cidr.parse::<ipnet::IpNet>()
                .map_err(|e| anyhow::anyhow!("invalid CIDR '{cidr}' in trusted_proxies: {e}"))?;
        }
        Ok(())
    }
}

/// Expand `${VAR}` references in the raw config text. Missing variables
/// are a hard error — a config that quietly leaves `${GATEWAY_BOSCA_TOKEN}`
/// in place would have the proxy authenticate to Bosca with that literal
/// string, which would either fail confusingly or, worse, succeed against
/// a misconfigured server.
fn expand_env_vars(input: &str) -> anyhow::Result<String> {
    let mut result = String::with_capacity(input.len());
    let mut chars = input.chars().peekable();
    let mut missing: Vec<String> = Vec::new();
    while let Some(c) = chars.next() {
        if c == '$' && chars.peek() == Some(&'{') {
            chars.next(); // consume '{'
            let mut var_name = String::new();
            for c in chars.by_ref() {
                if c == '}' {
                    break;
                }
                var_name.push(c);
            }
            match std::env::var(&var_name) {
                Ok(val) => result.push_str(&val),
                Err(_) => missing.push(var_name),
            }
        } else {
            result.push(c);
        }
    }
    if !missing.is_empty() {
        anyhow::bail!(
            "config references unset environment variable(s): {}",
            missing.join(", ")
        );
    }
    Ok(result)
}

fn default_bind() -> String {
    "0.0.0.0:9999".to_string()
}
fn default_drain_timeout() -> u64 {
    30
}
fn default_poll_interval() -> u64 {
    30
}
fn default_cache_file() -> String {
    "/var/lib/gateway/config-cache.json".to_string()
}
fn default_scopes() -> Vec<String> {
    vec![
        "openid".to_string(),
        "profile".to_string(),
        "email".to_string(),
    ]
}
fn default_session_ttl() -> u64 {
    28800
}
fn default_jwks_cache_ttl() -> u64 {
    3600
}
fn default_jwt_validation_cache_ttl() -> u64 {
    60
}
fn default_basic_cache_ttl() -> u64 {
    60
}
fn default_post_login_path() -> String {
    "/".to_string()
}
fn default_pending_flow_ttl() -> u64 {
    300
}

#[cfg(test)]
mod tests {
    use super::*;

    fn write_config(contents: &str) -> tempfile::NamedTempFile {
        use std::io::Write;
        let mut f = tempfile::NamedTempFile::new().unwrap();
        f.write_all(contents.as_bytes()).unwrap();
        f
    }

    const MINIMAL_CONFIG: &str = r#"
[gateway]
bind = "0.0.0.0:9999"

[bosca]
api_url = "http://localhost:8080"
api_token = "test-token"
"#;

    #[test]
    fn unset_env_var_is_a_hard_error() {
        let f = write_config(
            r#"
[gateway]
bind = "0.0.0.0:9999"

[bosca]
api_url = "http://localhost:8080"
api_token = "${BOSCA_GATEWAY_DEFINITELY_UNSET_VAR_XYZ}"
"#,
        );
        let err = BootstrapConfig::load(&f.path().to_path_buf()).unwrap_err();
        assert!(
            err.to_string().contains("unset environment variable"),
            "expected hard-fail on missing env var, got: {err}",
        );
        assert!(
            err.to_string()
                .contains("BOSCA_GATEWAY_DEFINITELY_UNSET_VAR_XYZ"),
            "error should name the unset variable, got: {err}",
        );
    }

    #[test]
    fn jwks_uri_must_be_https() {
        let mut config = MINIMAL_CONFIG.to_string();
        config.push_str(
            r#"
[auth.jwt]
issuer = "https://issuer.example"
audience = "test"
jwks_uri = "http://issuer.example/jwks.json"
"#,
        );
        let f = write_config(&config);
        let err = BootstrapConfig::load(&f.path().to_path_buf()).unwrap_err();
        assert!(
            err.to_string().contains("https://"),
            "expected http-jwks-uri rejection, got: {err}",
        );
    }

    #[test]
    fn jwks_uri_allows_http_when_opt_in_is_set() {
        let mut config = MINIMAL_CONFIG.to_string();
        config.push_str(
            r#"
[auth.jwt]
issuer = "https://issuer.example"
audience = "test"
jwks_uri = "http://bosca-server:8080/.well-known/jwks.json"
allow_insecure_jwks_uri = true
"#,
        );
        let f = write_config(&config);
        BootstrapConfig::load(&f.path().to_path_buf())
            .expect("http jwks_uri must load when allow_insecure_jwks_uri = true");
    }

    #[test]
    fn jwks_uri_accepts_https() {
        let mut config = MINIMAL_CONFIG.to_string();
        config.push_str(
            r#"
[auth.jwt]
issuer = "https://issuer.example"
audience = "test"
jwks_uri = "https://issuer.example/jwks.json"
"#,
        );
        let f = write_config(&config);
        BootstrapConfig::load(&f.path().to_path_buf()).expect("https jwks_uri should load");
    }

    #[test]
    fn oauth2_default_post_login_path_rejects_absolute_url() {
        let mut config = MINIMAL_CONFIG.to_string();
        config.push_str(
            r#"
[auth.oauth2]
issuer = "https://idp.example"
client_id = "test"
client_secret = "shh"
redirect_uri = "https://gateway.example/oauth2/callback"
default_post_login_path = "https://evil.example.com"
"#,
        );
        let f = write_config(&config);
        let err = BootstrapConfig::load(&f.path().to_path_buf()).unwrap_err();
        assert!(
            err.to_string().contains("absolute path"),
            "expected open-redirect rejection, got: {err}",
        );
    }

    #[test]
    fn oauth2_default_post_login_path_rejects_scheme_relative() {
        let mut config = MINIMAL_CONFIG.to_string();
        config.push_str(
            r#"
[auth.oauth2]
issuer = "https://idp.example"
client_id = "test"
client_secret = "shh"
redirect_uri = "https://gateway.example/oauth2/callback"
default_post_login_path = "//evil.example.com/x"
"#,
        );
        let f = write_config(&config);
        let err = BootstrapConfig::load(&f.path().to_path_buf()).unwrap_err();
        assert!(
            err.to_string().contains("absolute path"),
            "expected scheme-relative rejection, got: {err}",
        );
    }

    #[test]
    fn minimal_config_loads_with_defaults() {
        let f = write_config(MINIMAL_CONFIG);
        let cfg = BootstrapConfig::load(&f.path().to_path_buf()).unwrap();
        assert_eq!(cfg.gateway.bind, "0.0.0.0:9999");
        assert_eq!(cfg.bosca.poll_interval_secs, 30);
        assert_eq!(cfg.gateway.drain_timeout_secs, 30);
        assert_eq!(cfg.gateway.studio_url, None);
    }

    #[test]
    fn studio_url_loads_when_configured() {
        let config = MINIMAL_CONFIG.replace(
            "bind = \"0.0.0.0:9999\"",
            "bind = \"0.0.0.0:9999\"\nstudio_url = \"https://studio.example.com\"",
        );
        let f = write_config(&config);
        let cfg = BootstrapConfig::load(&f.path().to_path_buf()).unwrap();
        assert_eq!(
            cfg.gateway.studio_url.as_deref(),
            Some("https://studio.example.com")
        );
    }

    #[test]
    fn studio_url_rejects_relative_urls() {
        let config = MINIMAL_CONFIG.replace(
            "bind = \"0.0.0.0:9999\"",
            "bind = \"0.0.0.0:9999\"\nstudio_url = \"/auth/login\"",
        );
        let f = write_config(&config);
        let err = BootstrapConfig::load(&f.path().to_path_buf()).unwrap_err();
        assert!(
            err.to_string().contains("studio_url is invalid"),
            "expected relative Studio URL rejection, got: {err}",
        );
    }

    #[test]
    fn studio_url_rejects_non_http_schemes() {
        let config = MINIMAL_CONFIG.replace(
            "bind = \"0.0.0.0:9999\"",
            "bind = \"0.0.0.0:9999\"\nstudio_url = \"javascript:alert(1)\"",
        );
        let f = write_config(&config);
        let err = BootstrapConfig::load(&f.path().to_path_buf()).unwrap_err();
        assert!(
            err.to_string().contains("must use http:// or https://"),
            "expected unsafe Studio URL scheme rejection, got: {err}",
        );
    }
}
