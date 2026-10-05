use std::collections::HashMap;

use serde::{Deserialize, Serialize};

use super::template::HeaderTemplate;

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct GatewayConfig {
    #[serde(default)]
    pub version: String,
    #[serde(default)]
    pub services: Vec<Gateway>,
    #[serde(default)]
    pub routes: Vec<GatewayRoute>,

    #[serde(skip)]
    pub compiled_routes: Vec<CompiledRoute>,
    #[serde(skip)]
    pub gateway_map: HashMap<String, usize>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Gateway {
    pub id: String,
    pub name: String,
    pub url: String,
    pub health_check_path: Option<String>,
    #[serde(default = "default_30")]
    pub health_check_interval_secs: u64,
    #[serde(default = "default_5")]
    pub connect_timeout_secs: u64,
    #[serde(default = "default_300")]
    pub request_timeout_secs: u64,
    #[serde(default = "default_10")]
    pub pool_max_idle: usize,
    #[serde(default = "default_90")]
    pub pool_idle_timeout_secs: u64,
    #[serde(default = "default_true")]
    pub enabled: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GatewayRoute {
    pub id: String,
    pub gateway_id: String,
    pub path_pattern: String,
    /// Host patterns this route accepts. Empty list = match any host —
    /// preserves the prior host-agnostic behaviour. Each entry is a
    /// literal host (`api.bosca.io`) or a leading-`*.` wildcard
    /// (`*.bosca.io`, matching one label segment).
    #[serde(default)]
    pub hosts: Vec<String>,
    pub auth_method: GatewayAuthMethod,
    #[serde(default)]
    pub strip_prefix: bool,
    /// Groups allowed to issue read-method requests (GET/HEAD/OPTIONS)
    /// through this route. An empty list denies all reads for any
    /// authenticated principal.
    #[serde(default)]
    pub read_groups: Vec<String>,
    /// Groups allowed to issue write-method requests
    /// (POST/PUT/PATCH/DELETE). An empty list denies all writes for any
    /// authenticated principal.
    #[serde(default)]
    pub write_groups: Vec<String>,
    #[serde(default)]
    pub inject_headers: HashMap<String, String>,
    #[serde(default)]
    pub sort_order: i32,
    #[serde(default = "default_true")]
    pub enabled: bool,
}

/// Wire format intentionally UPPERCASE to match the canonical Kotlin
/// enum name (`OAUTH2`, `JWT`, `BASIC`, `NONE`). Bosca's GraphQL layer
/// uses kotlinx-serialization, which encodes enum variants as their
/// Kotlin name when no `@SerialName` is set. Keeping both sides on the
/// enum-name form means Postgres (lowercase) is the only spot that
/// case-converts, via `EnumMapper`.
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "UPPERCASE")]
pub enum GatewayAuthMethod {
    Oauth2,
    Jwt,
    Basic,
    None,
}

#[derive(Clone, Debug)]
pub struct CompiledRoute {
    pub route_index: usize,
    pub gateway_index: usize,
    /// Pre-computed match descriptor distinguishing exact-match routes
    /// from prefix routes. Stored so the hot path doesn't re-parse the
    /// pattern on every request.
    pub matcher: PathMatcher,
    /// Pre-compiled host matchers. An empty Vec means "match any host"
    /// — operators leave `hosts` empty when they want a single-host
    /// proxy to behave as it did before host-routing was added.
    pub host_matchers: Vec<HostMatcher>,
    /// Specificity of the most specific host matcher, used for sort
    /// precedence: 2 = literal, 1 = wildcard, 0 = any (empty list).
    /// A single route may carry literal + wildcard entries; the most
    /// specific one decides where the route falls in the sort, which
    /// is fine — a request that matches the literal also matches the
    /// wildcard, so the most-specific-wins ordering is consistent.
    pub host_specificity: u8,
    pub compiled_headers: Vec<(String, HeaderTemplate)>,
}

#[derive(Clone, Debug)]
pub enum PathMatcher {
    /// `/foo` — request path must equal exactly.
    Exact(String),
    /// `/foo/**` or `/foo/*` — request path must equal `/foo` or start
    /// with `/foo/`. The stored prefix has a trailing `/` stripped.
    Prefix(String),
}

#[derive(Clone, Debug)]
pub enum HostMatcher {
    /// A literal hostname. Match is ASCII-case-insensitive per RFC 3986
    /// §3.2.2 — `Api.Example.COM` and `api.example.com` are the same
    /// authority, so an operator who types one shouldn't be at the
    /// mercy of which casing the client sent.
    Literal(String),
    /// A leading-`*.` wildcard. The stored suffix is everything after
    /// the `*.` (e.g. `*.bosca.io` → `bosca.io`). Matches any host
    /// whose label-suffix equals the stored value AND that has at
    /// least one extra label in front (i.e. `bosca.io` itself does NOT
    /// match `*.bosca.io`, only `api.bosca.io`, `cdn.bosca.io`, etc.).
    Wildcard(String),
}

impl HostMatcher {
    fn from_pattern(pattern: &str) -> Option<Self> {
        let pattern = pattern.trim();
        if pattern.is_empty() {
            return None;
        }
        if let Some(suffix) = pattern.strip_prefix("*.") {
            if suffix.is_empty() {
                return None;
            }
            Some(HostMatcher::Wildcard(suffix.to_ascii_lowercase()))
        } else {
            Some(HostMatcher::Literal(pattern.to_ascii_lowercase()))
        }
    }

    /// `host` is the inbound `Host` header value, already stripped of
    /// any `:port` suffix (the caller normalizes that — see
    /// [normalize_host_header]). Comparison is ASCII-case-insensitive.
    pub fn matches(&self, host: &str) -> bool {
        match self {
            HostMatcher::Literal(target) => host.eq_ignore_ascii_case(target),
            HostMatcher::Wildcard(suffix) => {
                let host_lc = host.to_ascii_lowercase();
                if host_lc.len() <= suffix.len() + 1 {
                    return false;
                }
                let split_at = host_lc.len() - suffix.len();
                if !host_lc[split_at..].eq_ignore_ascii_case(suffix) {
                    return false;
                }
                // The character just before the suffix must be `.` and
                // the prefix must be exactly one label (no embedded
                // dots) — `*.bosca.io` matches `api.bosca.io` but NOT
                // `a.b.bosca.io`. This mirrors how nginx and Traefik
                // interpret leading-`*.` patterns by default.
                if host_lc.as_bytes()[split_at - 1] != b'.' {
                    return false;
                }
                let label = &host_lc[..split_at - 1];
                !label.is_empty() && !label.contains('.')
            }
        }
    }
}

/// Strip the optional `:port` suffix and trailing dot from a Host
/// header value. Returns the bare authority host. We do not validate
/// the host shape here — the comparison is purely string-based against
/// the operator's pattern.
pub fn normalize_host_header(raw: &str) -> &str {
    let trimmed = raw.trim();
    // IPv6 hosts arrive in brackets (`[::1]:9999`). We don't try to
    // strip the brackets — operators who want to match an IPv6 host
    // should configure the pattern with brackets too.
    let without_port = if trimmed.starts_with('[') {
        // `[::1]:9999` → `[::1]`
        match trimmed.rfind(']') {
            Some(end) => &trimmed[..=end],
            None => trimmed,
        }
    } else {
        match trimmed.rsplit_once(':') {
            Some((host, _port)) => host,
            None => trimmed,
        }
    };
    without_port.trim_end_matches('.')
}

impl PathMatcher {
    fn from_pattern(pattern: &str) -> Self {
        if let Some(prefix) = pattern.strip_suffix("/**") {
            PathMatcher::Prefix(prefix.to_string())
        } else if let Some(prefix) = pattern.strip_suffix("/*") {
            PathMatcher::Prefix(prefix.to_string())
        } else if let Some(prefix) = pattern.strip_suffix("**") {
            // bare "**" or "abc**" — treat the leading literal as the prefix
            PathMatcher::Prefix(prefix.to_string())
        } else if let Some(prefix) = pattern.strip_suffix('*') {
            PathMatcher::Prefix(prefix.to_string())
        } else {
            PathMatcher::Exact(pattern.to_string())
        }
    }

    /// Length used for "longest-prefix wins" sorting.
    fn match_length(&self) -> usize {
        match self {
            PathMatcher::Exact(s) | PathMatcher::Prefix(s) => s.len(),
        }
    }

    /// Returns true iff `path` matches this matcher with strict
    /// segment-boundary semantics — so `/api/foo` does NOT match
    /// `/api/foobar`. The prefix `/api/foo` matches `/api/foo` and
    /// `/api/foo/anything`, but not `/api/foobar`.
    pub fn matches(&self, path: &str) -> bool {
        match self {
            PathMatcher::Exact(target) => path == target,
            PathMatcher::Prefix(prefix) => {
                if !path.starts_with(prefix) {
                    return false;
                }
                if path.len() == prefix.len() {
                    return true;
                }
                // Require a `/` segment boundary at the end of the prefix.
                let rest = &path[prefix.len()..];
                rest.starts_with('/') || prefix.ends_with('/')
            }
        }
    }
}

impl GatewayConfig {
    pub fn compile(mut self) -> Self {
        self.gateway_map = self
            .services
            .iter()
            .enumerate()
            .map(|(i, s)| (s.id.clone(), i))
            .collect();

        let mut compiled = Vec::with_capacity(self.routes.len());
        for (ri, route) in self.routes.iter().enumerate() {
            if !route.enabled {
                continue;
            }
            let gateway_index = match self.gateway_map.get(&route.gateway_id) {
                Some(&idx) => idx,
                None => continue,
            };
            let matcher = PathMatcher::from_pattern(&route.path_pattern);
            let host_matchers: Vec<HostMatcher> = route
                .hosts
                .iter()
                .filter_map(|h| HostMatcher::from_pattern(h))
                .collect();
            // Specificity = the most specific entry on the route. A
            // route with both `api.bosca.io` (literal=2) and
            // `*.bosca.io` (wildcard=1) ranks as 2 — the literal wins.
            let host_specificity = host_matchers.iter().fold(0u8, |acc, m| {
                let s = match m {
                    HostMatcher::Literal(_) => 2,
                    HostMatcher::Wildcard(_) => 1,
                };
                acc.max(s)
            });
            let compiled_headers = route
                .inject_headers
                .iter()
                .map(|(k, v)| (k.clone(), HeaderTemplate::compile_with_context(v, Some(k))))
                .collect();
            compiled.push(CompiledRoute {
                route_index: ri,
                gateway_index,
                matcher,
                host_matchers,
                host_specificity,
                compiled_headers,
            });
        }
        // Sort precedence (most-specific-wins, top of list checked first):
        //   1. host specificity (literal > wildcard > any)
        //   2. path-prefix length (longest wins)
        //   3. operator-provided sort_order (lower wins)
        compiled.sort_by(|a, b| {
            b.host_specificity
                .cmp(&a.host_specificity)
                .then_with(|| b.matcher.match_length().cmp(&a.matcher.match_length()))
                .then_with(|| {
                    self.routes[a.route_index]
                        .sort_order
                        .cmp(&self.routes[b.route_index].sort_order)
                })
        });
        self.compiled_routes = compiled;
        self
    }

    /// Resolve a request to a route. `host` is the value of the
    /// inbound `Host` header (or HTTP/2 `:authority`), already
    /// normalized via [normalize_host_header]. An empty `host`
    /// disables host filtering for that request — only routes with no
    /// `hosts` configured can match.
    pub fn match_route(
        &self,
        host: &str,
        path: &str,
    ) -> Option<(&CompiledRoute, &GatewayRoute, &Gateway)> {
        for cr in &self.compiled_routes {
            if !host_matches(&cr.host_matchers, host) {
                continue;
            }
            if cr.matcher.matches(path) {
                let route = &self.routes[cr.route_index];
                let gateway = &self.services[cr.gateway_index];
                return Some((cr, route, gateway));
            }
        }
        None
    }
}

/// `matchers` empty → match any host. Otherwise the host must satisfy
/// at least one entry.
fn host_matches(matchers: &[HostMatcher], host: &str) -> bool {
    if matchers.is_empty() {
        return true;
    }
    matchers.iter().any(|m| m.matches(host))
}

fn default_5() -> u64 {
    5
}
fn default_10() -> usize {
    10
}
fn default_30() -> u64 {
    30
}
fn default_90() -> u64 {
    90
}
fn default_300() -> u64 {
    300
}
fn default_true() -> bool {
    true
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn prefix_does_not_leak_across_segments() {
        let m = PathMatcher::from_pattern("/api/foo/**");
        assert!(m.matches("/api/foo"));
        assert!(m.matches("/api/foo/"));
        assert!(m.matches("/api/foo/bar"));
        assert!(!m.matches("/api/foobar"));
        assert!(!m.matches("/api/foozilla/x"));
    }

    #[test]
    fn exact_match_only_matches_exact() {
        let m = PathMatcher::from_pattern("/api/health");
        assert!(m.matches("/api/health"));
        assert!(!m.matches("/api/health/"));
        assert!(!m.matches("/api/healthy"));
        assert!(!m.matches("/api/health/sub"));
    }

    #[test]
    fn single_star_treats_as_segment_prefix() {
        let m = PathMatcher::from_pattern("/api/foo/*");
        assert!(m.matches("/api/foo"));
        assert!(m.matches("/api/foo/bar"));
        assert!(!m.matches("/api/foobar"));
    }

    fn make_gateway(id: &str, name: &str) -> Gateway {
        Gateway {
            id: id.into(),
            name: name.into(),
            url: format!("http://{name}.internal"),
            health_check_path: None,
            health_check_interval_secs: 30,
            connect_timeout_secs: 5,
            request_timeout_secs: 300,
            pool_max_idle: 10,
            pool_idle_timeout_secs: 90,
            enabled: true,
        }
    }

    fn make_route(id: &str, gateway_id: &str, pattern: &str, sort_order: i32) -> GatewayRoute {
        make_route_with_hosts(id, gateway_id, pattern, sort_order, &[])
    }

    fn make_route_with_hosts(
        id: &str,
        gateway_id: &str,
        pattern: &str,
        sort_order: i32,
        hosts: &[&str],
    ) -> GatewayRoute {
        GatewayRoute {
            id: id.into(),
            gateway_id: gateway_id.into(),
            path_pattern: pattern.into(),
            hosts: hosts.iter().map(|h| (*h).to_string()).collect(),
            auth_method: GatewayAuthMethod::None,
            strip_prefix: false,
            read_groups: vec![],
            write_groups: vec![],
            inject_headers: HashMap::new(),
            sort_order,
            enabled: true,
        }
    }

    fn config_with(routes: Vec<GatewayRoute>) -> GatewayConfig {
        GatewayConfig {
            version: "v".into(),
            services: vec![make_gateway("g", "g")],
            routes,
            compiled_routes: vec![],
            gateway_map: HashMap::new(),
        }
        .compile()
    }

    #[test]
    fn longest_prefix_wins() {
        let config = config_with(vec![
            make_route("r1", "g", "/api/**", 0),
            make_route("r2", "g", "/api/admin/**", 0),
        ]);
        let (cr, _, _) = config
            .match_route("", "/api/admin/users")
            .expect("should match");
        assert_eq!(config.routes[cr.route_index].path_pattern, "/api/admin/**");
    }

    #[test]
    fn sort_order_breaks_ties_at_same_prefix_length() {
        let config = config_with(vec![
            make_route("low", "g", "/x/**", 100),
            make_route("high", "g", "/x/**", 1),
        ]);
        let (cr, _, _) = config.match_route("", "/x/anything").expect("should match");
        assert_eq!(
            config.routes[cr.route_index].sort_order, 1,
            "lower sort_order should win ties at the same prefix length",
        );
    }

    #[test]
    fn match_route_returns_none_when_no_pattern_matches() {
        let config = config_with(vec![make_route("r", "g", "/api/**", 0)]);
        assert!(config.match_route("", "/other").is_none());
        assert!(config.match_route("", "/").is_none());
    }

    #[test]
    fn disabled_routes_are_excluded_from_compile() {
        let mut route = make_route("r", "g", "/api/**", 0);
        route.enabled = false;
        let config = config_with(vec![route]);
        assert!(config.match_route("", "/api/anything").is_none());
    }

    #[test]
    fn routes_pointing_at_unknown_gateway_are_dropped() {
        let config = config_with(vec![make_route("orphan", "no-such-gateway", "/api/**", 0)]);
        assert!(config.match_route("", "/api/x").is_none());
    }

    // ---------------------------------------------------------------
    // Host matching
    // ---------------------------------------------------------------

    #[test]
    fn empty_hosts_matches_any_host() {
        let config = config_with(vec![make_route("any", "g", "/**", 0)]);
        assert!(config.match_route("trino.example.com", "/q").is_some());
        assert!(config.match_route("preview.example.com", "/q").is_some());
        assert!(config.match_route("", "/q").is_some());
    }

    #[test]
    fn literal_host_only_matches_that_host() {
        let config = config_with(vec![make_route_with_hosts(
            "r",
            "g",
            "/**",
            0,
            &["trino.example.com"],
        )]);
        assert!(config.match_route("trino.example.com", "/q").is_some());
        assert!(config.match_route("preview.example.com", "/q").is_none());
        // No host header at all should not match a host-scoped route —
        // we can't prove the request was for the configured host.
        assert!(config.match_route("", "/q").is_none());
    }

    #[test]
    fn literal_host_matching_is_case_insensitive() {
        let config = config_with(vec![make_route_with_hosts(
            "r",
            "g",
            "/**",
            0,
            &["API.Example.COM"],
        )]);
        assert!(config.match_route("api.example.com", "/q").is_some());
        assert!(config.match_route("API.EXAMPLE.COM", "/q").is_some());
    }

    #[test]
    fn wildcard_host_matches_one_label() {
        let config = config_with(vec![make_route_with_hosts(
            "r",
            "g",
            "/**",
            0,
            &["*.bosca.io"],
        )]);
        assert!(config.match_route("api.bosca.io", "/q").is_some());
        assert!(config.match_route("trino.bosca.io", "/q").is_some());
        // No prefix label.
        assert!(config.match_route("bosca.io", "/q").is_none());
        // Two labels in the prefix — `*.bosca.io` matches one label
        // only, by convention.
        assert!(config.match_route("a.b.bosca.io", "/q").is_none());
        // Different parent domain.
        assert!(config.match_route("api.bosca.dev", "/q").is_none());
    }

    #[test]
    fn host_literal_outranks_host_wildcard_at_same_path() {
        let config = config_with(vec![
            make_route_with_hosts("wild", "g", "/**", 0, &["*.bosca.io"]),
            make_route_with_hosts("lit", "g", "/**", 0, &["api.bosca.io"]),
        ]);
        let (cr, _, _) = config
            .match_route("api.bosca.io", "/q")
            .expect("should match");
        assert_eq!(
            config.routes[cr.route_index].id, "lit",
            "literal host should outrank wildcard",
        );
        // A host that only the wildcard covers still resolves via the
        // wildcard.
        let (cr, _, _) = config
            .match_route("preview.bosca.io", "/q")
            .expect("should match");
        assert_eq!(config.routes[cr.route_index].id, "wild");
    }

    #[test]
    fn host_wildcard_outranks_host_any_at_same_path() {
        let config = config_with(vec![
            make_route("any", "g", "/**", 0),
            make_route_with_hosts("wild", "g", "/**", 0, &["*.bosca.io"]),
        ]);
        let (cr, _, _) = config
            .match_route("api.bosca.io", "/q")
            .expect("should match");
        assert_eq!(config.routes[cr.route_index].id, "wild");
        let (cr, _, _) = config
            .match_route("other.example.com", "/q")
            .expect("should match");
        assert_eq!(config.routes[cr.route_index].id, "any");
    }

    #[test]
    fn host_specificity_outranks_path_length() {
        // A host-literal at `/**` outranks a host-any at `/api/**`
        // even though the host-any has the longer path. This is the
        // intended behaviour: host scoping is the operator's stronger
        // signal, and the operator who set the literal host meant
        // "this is the route for this host".
        let config = config_with(vec![
            make_route("path-long", "g", "/api/**", 0),
            make_route_with_hosts("host-lit", "g", "/**", 0, &["api.bosca.io"]),
        ]);
        let (cr, _, _) = config
            .match_route("api.bosca.io", "/api/x")
            .expect("should match");
        assert_eq!(config.routes[cr.route_index].id, "host-lit");
    }

    #[test]
    fn one_route_with_literal_and_wildcard_accepts_both() {
        let config = config_with(vec![make_route_with_hosts(
            "r",
            "g",
            "/**",
            0,
            &["api.bosca.io", "*.bosca.io"],
        )]);
        assert!(config.match_route("api.bosca.io", "/q").is_some());
        assert!(config.match_route("cdn.bosca.io", "/q").is_some());
        assert!(config.match_route("bosca.io", "/q").is_none());
    }

    #[test]
    fn normalize_host_strips_port_and_trailing_dot() {
        assert_eq!(normalize_host_header("api.bosca.io"), "api.bosca.io");
        assert_eq!(normalize_host_header("api.bosca.io:9999"), "api.bosca.io");
        assert_eq!(normalize_host_header("api.bosca.io."), "api.bosca.io");
        assert_eq!(normalize_host_header("api.bosca.io.:9999"), "api.bosca.io");
        assert_eq!(normalize_host_header("[::1]:9999"), "[::1]");
        assert_eq!(normalize_host_header("[::1]"), "[::1]");
        assert_eq!(normalize_host_header("  api.bosca.io  "), "api.bosca.io");
    }
}
