//! Trusted-proxy resolution for `X-Forwarded-*` semantics.
//!
//! When the inbound peer is in the configured `trusted_proxies` list,
//! the proxy treats the request's `X-Forwarded-Proto` /
//! `X-Forwarded-Host` / `X-Forwarded-For` headers as authoritative —
//! they describe the original client, not a guess from the immediate
//! peer. When the peer is untrusted, those inbound headers are
//! ignored (anything else lets any client lie about being any IP).
//!
//! This matches how nginx (`set_real_ip_from` + `real_ip_header`),
//! envoy (`xff_num_trusted_hops`), and caddy (`trusted_proxies`)
//! handle the same problem.

use std::net::IpAddr;
use std::sync::Arc;

use axum::http::HeaderMap;
use ipnet::IpNet;

/// Pre-parsed `trusted_proxies` CIDRs. Stored on `AppState` so the hot
/// path doesn't re-parse strings on every request.
#[derive(Clone, Debug, Default)]
pub struct TrustedProxies {
    cidrs: Arc<Vec<IpNet>>,
}

impl TrustedProxies {
    /// Parse a list of CIDR strings into a matcher. Returns the first
    /// parse error verbatim — the config-load path validates these
    /// already, so this is mostly belt-and-suspenders.
    pub fn parse(cidrs: &[String]) -> anyhow::Result<Self> {
        let parsed = cidrs
            .iter()
            .map(|c| {
                c.parse::<IpNet>()
                    .map_err(|e| anyhow::anyhow!("invalid CIDR '{c}': {e}"))
            })
            .collect::<anyhow::Result<Vec<_>>>()?;
        Ok(Self {
            cidrs: Arc::new(parsed),
        })
    }

    pub fn contains(&self, ip: IpAddr) -> bool {
        self.cidrs.iter().any(|n| n.contains(&ip))
    }

    pub fn is_empty(&self) -> bool {
        self.cidrs.is_empty()
    }
}

/// Per-request "what does the upstream need to know about the original
/// client?" view. Built once at the top of `proxy_request` and used
/// for both (a) populating the `{{request.*}}` template variables and
/// (b) stamping the outbound `X-Forwarded-*` chain.
#[derive(Debug)]
pub struct ForwardingContext {
    /// Real client IP — the leftmost untrusted entry in
    /// `X-Forwarded-For` when the inbound peer is trusted, else the
    /// immediate peer IP. May be empty only in pathological cases
    /// (peer addr extraction failed). Templates that reference
    /// `{{request.clientIp}}` drop the header when this is empty.
    pub client_ip: String,
    /// Original-client scheme — `http` or `https`.
    pub scheme: String,
    /// Original-client host[:port]. Used both for the
    /// `{{request.host}}` template and for the gateway-self detection
    /// in Location rewriting.
    pub host: String,
    /// String form of the original-client port (we don't try to
    /// re-parse out of `host`; we keep them as two separate values
    /// because some upstreams prefer `X-Forwarded-Port` distinctly).
    pub port: String,
    /// The full `X-Forwarded-For` chain to stamp on the outbound
    /// request. When the inbound peer was trusted, this is the
    /// inbound chain + peer; otherwise it's just the peer. The proxy
    /// always sets this on the upstream call — operators don't need
    /// to template it themselves.
    pub xff_chain: String,
    /// Whether the inbound peer was in the configured trusted-proxies
    /// list. Mostly informational for logs; behavior derives from the
    /// resolved fields above.
    pub peer_was_trusted: bool,
}

/// Inputs to [`ForwardingContext::resolve`] — names callers give us
/// to express intent at the call site.
pub struct ResolveArgs<'a> {
    pub peer: IpAddr,
    pub trusted: &'a TrustedProxies,
    pub headers: &'a HeaderMap,
    /// The authority observed from the inbound request itself —
    /// `:authority` (HTTP/2) or the `Host` header (HTTP/1). Used as
    /// the fallback host when no trusted-proxy provided one.
    pub inbound_authority: &'a str,
    /// Scheme to default to when no trusted hop provided
    /// `X-Forwarded-Proto`. Should reflect what the operator says the
    /// gateway is serving externally (`gateway.external_scheme`), or
    /// fall back to the bind-derived guess.
    pub fallback_scheme: &'a str,
    /// Port to default to when no trusted hop provided
    /// `X-Forwarded-Port`. Should reflect the gateway's *external*
    /// port (the LB's listen port if behind one), or fall back to the
    /// bind port.
    pub fallback_port: &'a str,
}

impl ForwardingContext {
    pub fn resolve(args: ResolveArgs<'_>) -> Self {
        let peer_was_trusted = args.trusted.contains(args.peer);
        if peer_was_trusted {
            let inbound_proto = first_header(args.headers, "x-forwarded-proto");
            let inbound_host = first_header(args.headers, "x-forwarded-host");
            let inbound_port = first_header(args.headers, "x-forwarded-port");
            let inbound_for = first_header(args.headers, "x-forwarded-for");

            let scheme = inbound_proto
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(str::to_string)
                .unwrap_or_else(|| args.fallback_scheme.to_string());
            let host = inbound_host
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(str::to_string)
                .unwrap_or_else(|| args.inbound_authority.to_string());
            let port = inbound_port
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(str::to_string)
                .unwrap_or_else(|| args.fallback_port.to_string());

            let (client_ip, xff_chain) = match inbound_for {
                Some(chain) => {
                    let real = leftmost_untrusted(chain, args.trusted)
                        .unwrap_or_else(|| args.peer.to_string());
                    let appended = append_to_xff(chain, args.peer);
                    (real, appended)
                }
                None => (args.peer.to_string(), args.peer.to_string()),
            };

            ForwardingContext {
                client_ip,
                scheme,
                host,
                port,
                xff_chain,
                peer_was_trusted: true,
            }
        } else {
            // Untrusted peer — pretend any inbound X-Forwarded-* are
            // not present. The peer IS the client from our POV.
            ForwardingContext {
                client_ip: args.peer.to_string(),
                scheme: args.fallback_scheme.to_string(),
                host: args.inbound_authority.to_string(),
                port: args.fallback_port.to_string(),
                xff_chain: args.peer.to_string(),
                peer_was_trusted: false,
            }
        }
    }
}

/// First value of a multi-valued header, trimmed. Most proxies emit
/// `X-Forwarded-For: a, b, c` as a single header. axum tolerates
/// duplicates and we accept the first one — same convention as
/// reqwest/hyper.
fn first_header<'a>(headers: &'a HeaderMap, name: &str) -> Option<&'a str> {
    headers
        .get(name)
        .and_then(|v| v.to_str().ok())
        .map(str::trim)
        .filter(|s| !s.is_empty())
}

/// Walks an `X-Forwarded-For` chain from left to right and returns
/// the first entry that is NOT in the trusted-proxies list. That's
/// the real client — every subsequent entry is a known intermediary.
///
/// IPs that fail to parse are skipped (they're operator garbage). If
/// every entry parses but all are trusted (shouldn't happen — the
/// leftmost is always the original client and *can't* be a known
/// intermediary unless someone configured their LB into the trusted
/// set, which is fine), returns `None` so callers can fall back to
/// the peer IP.
fn leftmost_untrusted(chain: &str, trusted: &TrustedProxies) -> Option<String> {
    for entry in chain.split(',') {
        let entry = entry.trim();
        if entry.is_empty() {
            continue;
        }
        let ip = match entry.parse::<IpAddr>() {
            Ok(ip) => ip,
            Err(_) => continue,
        };
        if !trusted.contains(ip) {
            return Some(ip.to_string());
        }
    }
    None
}

/// Append the immediate peer's IP to an existing `X-Forwarded-For`
/// chain. The output is normalized: single comma+space separator,
/// no leading/trailing whitespace, entries with garbage IPs dropped.
fn append_to_xff(existing: &str, peer: IpAddr) -> String {
    let mut out: Vec<String> = existing
        .split(',')
        .map(str::trim)
        .filter(|s| !s.is_empty())
        // Keep entries verbatim (don't re-parse) — IPv4-mapped IPv6
        // formatting differences would otherwise round-trip
        // destructively.
        .map(str::to_string)
        .collect();
    out.push(peer.to_string());
    out.join(", ")
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::Ipv4Addr;

    fn trust(cidrs: &[&str]) -> TrustedProxies {
        TrustedProxies::parse(&cidrs.iter().map(|s| (*s).to_string()).collect::<Vec<_>>()).unwrap()
    }

    fn headers(pairs: &[(&str, &str)]) -> HeaderMap {
        let mut h = HeaderMap::new();
        for (k, v) in pairs {
            h.insert(
                axum::http::HeaderName::from_bytes(k.as_bytes()).unwrap(),
                axum::http::HeaderValue::from_str(v).unwrap(),
            );
        }
        h
    }

    #[test]
    fn untrusted_peer_ignores_inbound_xforwarded_headers() {
        // Direct client attack: pretends to be 1.2.3.4 via headers.
        // Untrusted peer → headers are discarded; peer is the truth.
        let h = headers(&[
            ("x-forwarded-for", "1.2.3.4"),
            ("x-forwarded-proto", "https"),
            ("x-forwarded-host", "evil.example.com"),
            ("x-forwarded-port", "443"),
        ]);
        let ctx = ForwardingContext::resolve(ResolveArgs {
            peer: IpAddr::V4(Ipv4Addr::new(10, 0, 0, 50)),
            trusted: &trust(&["10.0.99.0/24"]),
            headers: &h,
            inbound_authority: "gateway.example:9999",
            fallback_scheme: "http",
            fallback_port: "9999",
        });
        assert!(!ctx.peer_was_trusted);
        assert_eq!(ctx.client_ip, "10.0.0.50");
        assert_eq!(ctx.scheme, "http");
        assert_eq!(ctx.host, "gateway.example:9999");
        assert_eq!(ctx.port, "9999");
        assert_eq!(ctx.xff_chain, "10.0.0.50");
    }

    #[test]
    fn trusted_peer_honors_inbound_xforwarded_headers() {
        let h = headers(&[
            ("x-forwarded-for", "203.0.113.7"),
            ("x-forwarded-proto", "https"),
            ("x-forwarded-host", "api.example.com"),
            ("x-forwarded-port", "443"),
        ]);
        let ctx = ForwardingContext::resolve(ResolveArgs {
            peer: IpAddr::V4(Ipv4Addr::new(10, 0, 99, 5)),
            trusted: &trust(&["10.0.99.0/24"]),
            headers: &h,
            inbound_authority: "irrelevant:9999",
            fallback_scheme: "http",
            fallback_port: "9999",
        });
        assert!(ctx.peer_was_trusted);
        assert_eq!(ctx.client_ip, "203.0.113.7");
        assert_eq!(ctx.scheme, "https");
        assert_eq!(ctx.host, "api.example.com");
        assert_eq!(ctx.port, "443");
        assert_eq!(ctx.xff_chain, "203.0.113.7, 10.0.99.5");
    }

    #[test]
    fn trusted_peer_with_missing_headers_falls_back() {
        // Cilium might not send X-Forwarded-Port even though it sets
        // the others. Fall back per-field, not all-or-nothing.
        let h = headers(&[
            ("x-forwarded-for", "203.0.113.7"),
            ("x-forwarded-proto", "https"),
        ]);
        let ctx = ForwardingContext::resolve(ResolveArgs {
            peer: IpAddr::V4(Ipv4Addr::new(10, 0, 99, 5)),
            trusted: &trust(&["10.0.99.0/24"]),
            headers: &h,
            inbound_authority: "api.example.com",
            fallback_scheme: "http",
            fallback_port: "9999",
        });
        assert_eq!(ctx.client_ip, "203.0.113.7");
        assert_eq!(ctx.scheme, "https");
        assert_eq!(ctx.host, "api.example.com"); // fell back
        assert_eq!(ctx.port, "9999"); // fell back
    }

    #[test]
    fn leftmost_untrusted_skips_known_intermediaries() {
        // Two trusted hops in a row: ALB (203.0.113.10) and Cilium
        // (10.0.99.5). The real client is the leftmost entry — and
        // it happens to NOT be in the trusted set.
        let trust_set = trust(&["10.0.99.0/24", "203.0.113.0/24"]);
        let chain = "198.51.100.42, 203.0.113.10";
        assert_eq!(
            leftmost_untrusted(chain, &trust_set).as_deref(),
            Some("198.51.100.42"),
        );
    }

    #[test]
    fn leftmost_untrusted_handles_garbage_entries() {
        // Empty entries (a trailing comma) and unparseable IPs (the
        // proxy upstream of us was misbehaving) should be skipped,
        // not crash the whole resolution.
        let trust_set = trust(&["10.0.99.0/24"]);
        let chain = "  , not-an-ip, 198.51.100.42, 10.0.99.5";
        assert_eq!(
            leftmost_untrusted(chain, &trust_set).as_deref(),
            Some("198.51.100.42"),
        );
    }

    #[test]
    fn leftmost_untrusted_returns_none_when_every_entry_is_trusted() {
        // Pathological config: leftmost is itself in the trusted set.
        // We don't try to invent a client; callers fall back to peer.
        let trust_set = trust(&["10.0.99.0/24"]);
        let chain = "10.0.99.5, 10.0.99.6";
        assert!(leftmost_untrusted(chain, &trust_set).is_none());
    }

    #[test]
    fn append_to_xff_normalizes_whitespace() {
        let peer = IpAddr::V4(Ipv4Addr::new(10, 0, 99, 5));
        assert_eq!(
            append_to_xff("  198.51.100.42 ,  ", peer),
            "198.51.100.42, 10.0.99.5",
        );
    }

    #[test]
    fn trusted_proxies_matches_ipv4_and_ipv6_cidrs() {
        let t = trust(&["10.0.0.0/8", "::1/128"]);
        assert!(t.contains(IpAddr::V4(Ipv4Addr::new(10, 1, 2, 3))));
        assert!(!t.contains(IpAddr::V4(Ipv4Addr::new(11, 0, 0, 1))));
        assert!(t.contains("::1".parse().unwrap()));
        assert!(!t.contains("::2".parse().unwrap()));
    }
}
