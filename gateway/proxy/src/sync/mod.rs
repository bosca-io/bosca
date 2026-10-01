use std::sync::Arc;

use arc_swap::ArcSwap;
use rand::Rng;

use crate::config::{BootstrapConfig, GatewayConfig};
use crate::health::HealthSupervisor;
use crate::metrics::Metrics;

pub async fn initial_load(
    bootstrap: &BootstrapConfig,
    http_client: &reqwest::Client,
    metrics: &Metrics,
) -> anyhow::Result<GatewayConfig> {
    match fetch_config(bootstrap, http_client, None).await {
        Ok(Some(config)) => {
            metrics.config_poll(true);
            persist_cache(bootstrap, &config).await;
            tracing::info!(
                version = %config.version,
                services = config.services.len(),
                routes = config.routes.len(),
                "config loaded from Bosca"
            );
            Ok(config)
        }
        Ok(None) => unreachable!("initial load with no etag always returns Some"),
        Err(e) => {
            metrics.config_poll(false);
            tracing::warn!(error = %e, "Bosca API unreachable, trying disk cache");
            match load_cache(bootstrap).await? {
                Some(cached) => Ok(cached),
                None => {
                    // Cold boot: no API, no cache. Rather than crash
                    // the proxy (which would leave the orchestrator
                    // with nothing to healthcheck and no way to
                    // recover without intervention), come up with an
                    // empty config. Every routed request will 404
                    // until the poll loop succeeds and swaps in the
                    // real config. The `config_poll` failure metric
                    // and the warn above are what make this loud —
                    // silent empty startup would mask a misconfigured
                    // `api_url`.
                    tracing::warn!(
                        "no cached config available; starting with empty config — \
                         all routed requests will 404 until the next successful poll"
                    );
                    Ok(empty_config())
                }
            }
        }
    }
}

fn empty_config() -> GatewayConfig {
    GatewayConfig {
        version: String::new(),
        services: Vec::new(),
        routes: Vec::new(),
        compiled_routes: Vec::new(),
        gateway_map: std::collections::HashMap::new(),
    }
    .compile()
}

pub async fn poll_loop(
    config_handle: Arc<ArcSwap<GatewayConfig>>,
    bootstrap: BootstrapConfig,
    http_client: reqwest::Client,
    metrics: Metrics,
    health_supervisor: Arc<HealthSupervisor>,
) {
    let base_interval = bootstrap.bosca.poll_interval_secs;
    loop {
        // Add ±10% jitter so N proxies polling on the same schedule
        // don't stampede the server every interval.
        let jittered = jittered_interval(base_interval);
        tokio::time::sleep(jittered).await;

        let current_version = config_handle.load().version.clone();
        match fetch_config(&bootstrap, &http_client, Some(&current_version)).await {
            Ok(Some(new_config)) => {
                metrics.config_poll(true);
                tracing::info!(
                    old_version = %current_version,
                    new_version = %new_config.version,
                    services = new_config.services.len(),
                    routes = new_config.routes.len(),
                    "config updated"
                );
                persist_cache(&bootstrap, &new_config).await;
                // Reconcile health probes against the new gateway set
                // BEFORE swapping the routing config in. Order matters
                // only weakly — both calls are idempotent — but
                // probing the new shape first means a brand-new
                // gateway gets its first probe issued before traffic
                // can be routed to it.
                health_supervisor.apply_config(&new_config).await;
                config_handle.store(Arc::new(new_config));
            }
            Ok(None) => {
                metrics.config_poll(true);
                tracing::debug!("config unchanged (version {})", current_version);
            }
            Err(e) => {
                metrics.config_poll(false);
                tracing::warn!(error = %e, "config poll failed, continuing with current config");
            }
        }
    }
}

fn jittered_interval(base_secs: u64) -> std::time::Duration {
    let base = base_secs as f64;
    let factor = rand::thread_rng().gen_range(0.9..=1.1);
    std::time::Duration::from_secs_f64((base * factor).max(1.0))
}

async fn fetch_config(
    bootstrap: &BootstrapConfig,
    http_client: &reqwest::Client,
    current_version: Option<&str>,
) -> anyhow::Result<Option<GatewayConfig>> {
    let url = format!(
        "{}/api/v1/gateway/config",
        bootstrap.bosca.api_url.trim_end_matches('/')
    );
    let mut req = http_client
        .get(&url)
        .bearer_auth(&bootstrap.bosca.api_token);

    if let Some(version) = current_version {
        req = req.header("If-None-Match", version);
    }

    let resp = req.send().await?;

    if resp.status() == reqwest::StatusCode::NOT_MODIFIED {
        return Ok(None);
    }

    if !resp.status().is_success() {
        anyhow::bail!("Bosca API returned status {} from {}", resp.status(), url);
    }

    let config: GatewayConfig = resp.json().await?;
    Ok(Some(config.compile()))
}

async fn persist_cache(bootstrap: &BootstrapConfig, config: &GatewayConfig) {
    let path = std::path::Path::new(&bootstrap.bosca.cache_file);
    if let Some(parent) = path.parent() {
        let _ = tokio::fs::create_dir_all(parent).await;
    }
    let tmp_path = format!("{}.tmp", bootstrap.bosca.cache_file);
    match serde_json::to_string(config) {
        Ok(json) => match tokio::fs::write(&tmp_path, &json).await {
            Ok(()) => {
                if let Err(e) = tokio::fs::rename(&tmp_path, &bootstrap.bosca.cache_file).await {
                    tracing::warn!(error = %e, "failed to rename config cache");
                }
            }
            Err(e) => tracing::warn!(error = %e, "failed to write config cache"),
        },
        Err(e) => tracing::warn!(error = %e, "failed to serialize config for cache"),
    }
}

/// Reads the on-disk cache. Distinguishes the *expected* failure (no
/// file yet — first boot, fresh deploy) from real failures (file
/// exists but is corrupted, schema-incompatible, or unreadable).
///
///  - `Ok(Some(config))` — cache found and parsed.
///  - `Ok(None)` — file not found. Caller decides how to proceed; for
///    `initial_load` this means "cold boot, come up with empty config".
///  - `Err(_)` — file exists but is unusable. Data integrity issue;
///    propagate so the operator notices.
async fn load_cache(bootstrap: &BootstrapConfig) -> anyhow::Result<Option<GatewayConfig>> {
    let path = &bootstrap.bosca.cache_file;
    let data = match tokio::fs::read_to_string(path).await {
        Ok(data) => data,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
            tracing::info!(path = %path, "no on-disk cache yet");
            return Ok(None);
        }
        Err(e) => return Err(e.into()),
    };
    let config: GatewayConfig = serde_json::from_str(&data)?;
    tracing::info!(version = %config.version, "loaded config from disk cache");
    Ok(Some(config.compile()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::{Gateway, GatewayAuthMethod, GatewayRoute};
    use std::collections::HashMap;

    fn bootstrap_with_cache(cache_file: &str) -> BootstrapConfig {
        let toml_text = format!(
            r#"
[gateway]
bind = "0.0.0.0:9999"

[bosca]
api_url = "http://127.0.0.1:1"
api_token = "test-token"
poll_interval_secs = 30
cache_file = "{}"
"#,
            cache_file.replace('\\', "\\\\")
        );
        toml::from_str(&toml_text).unwrap()
    }

    fn small_config(version: &str) -> GatewayConfig {
        GatewayConfig {
            version: version.into(),
            services: vec![Gateway {
                id: "g1".into(),
                name: "trino".into(),
                url: "http://trino.internal".into(),
                health_check_path: None,
                health_check_interval_secs: 30,
                connect_timeout_secs: 5,
                request_timeout_secs: 300,
                pool_max_idle: 10,
                pool_idle_timeout_secs: 90,
                enabled: true,
            }],
            routes: vec![GatewayRoute {
                id: "r1".into(),
                gateway_id: "g1".into(),
                path_pattern: "/trino/**".into(),
                hosts: vec![],
                auth_method: GatewayAuthMethod::Jwt,
                strip_prefix: false,
                read_groups: vec![],
                write_groups: vec![],
                inject_headers: HashMap::new(),
                sort_order: 0,
                enabled: true,
            }],
            compiled_routes: vec![],
            gateway_map: HashMap::new(),
        }
    }

    #[tokio::test]
    async fn persist_and_load_cache_roundtrip() {
        let tmp = tempfile::tempdir().unwrap();
        let cache_path = tmp.path().join("config-cache.json");
        let bootstrap = bootstrap_with_cache(cache_path.to_str().unwrap());

        let config = small_config("v1");
        persist_cache(&bootstrap, &config).await;

        // The file must exist with proper contents and be parseable.
        assert!(cache_path.exists());
        let loaded = load_cache(&bootstrap)
            .await
            .expect("cache load")
            .expect("present cache must return Some");
        assert_eq!(loaded.version, "v1");
        assert_eq!(loaded.services.len(), 1);
        assert_eq!(loaded.routes.len(), 1);
        // The loaded config must be compiled so it's usable for
        // routing without a second compile pass.
        assert!(
            !loaded.compiled_routes.is_empty(),
            "load_cache must compile"
        );
    }

    #[tokio::test]
    async fn persist_cache_atomic_via_rename() {
        // After persist completes, there must be no `.tmp` file left
        // behind — the rename should have moved it into place.
        let tmp = tempfile::tempdir().unwrap();
        let cache_path = tmp.path().join("config-cache.json");
        let bootstrap = bootstrap_with_cache(cache_path.to_str().unwrap());

        persist_cache(&bootstrap, &small_config("v1")).await;

        let tmp_path = tmp.path().join("config-cache.json.tmp");
        assert!(cache_path.exists());
        assert!(!tmp_path.exists(), ".tmp file should be renamed away");
    }

    #[tokio::test]
    async fn persist_cache_creates_parent_directory() {
        // The cache file path may be in a directory that doesn't yet
        // exist on first run — persist must create it rather than
        // silently fail.
        let tmp = tempfile::tempdir().unwrap();
        let nested = tmp.path().join("a").join("b").join("c").join("cache.json");
        let bootstrap = bootstrap_with_cache(nested.to_str().unwrap());

        persist_cache(&bootstrap, &small_config("v1")).await;
        assert!(nested.exists(), "parent directories should be created");
    }

    #[tokio::test]
    async fn load_cache_returns_none_when_file_missing() {
        // A missing cache file is the expected state on a fresh boot
        // and MUST be distinguishable from a corrupt-file error so
        // `initial_load` can fall through to empty-config startup.
        let tmp = tempfile::tempdir().unwrap();
        let bootstrap = bootstrap_with_cache(tmp.path().join("nope.json").to_str().unwrap());
        let result = load_cache(&bootstrap)
            .await
            .expect("missing file is Ok(None)");
        assert!(result.is_none(), "missing cache file must yield Ok(None)");
    }

    #[tokio::test]
    async fn load_cache_errors_when_file_is_corrupt() {
        // A file that exists but isn't a valid GatewayConfig is a
        // real problem (disk corruption, schema drift, etc.) — must
        // not silently swallow into Ok(None).
        let tmp = tempfile::tempdir().unwrap();
        let cache_path = tmp.path().join("corrupt.json");
        tokio::fs::write(&cache_path, "not valid json")
            .await
            .unwrap();
        let bootstrap = bootstrap_with_cache(cache_path.to_str().unwrap());
        assert!(load_cache(&bootstrap).await.is_err());
    }

    #[tokio::test]
    async fn initial_load_starts_with_empty_config_when_api_down_and_no_cache() {
        // Cold boot survivability: when both the API and the cache
        // are unavailable, the proxy must still come up. Routed
        // requests will 404 against the empty config until the next
        // successful poll, but the process stays alive for the
        // orchestrator to healthcheck.
        let tmp = tempfile::tempdir().unwrap();
        let bootstrap = bootstrap_with_cache(tmp.path().join("nope.json").to_str().unwrap());
        let metrics = Metrics::new().unwrap();
        let client = reqwest::Client::new();
        let config = initial_load(&bootstrap, &client, &metrics).await.unwrap();
        assert_eq!(config.version, "");
        assert!(config.services.is_empty());
        assert!(config.routes.is_empty());
    }

    #[tokio::test]
    async fn initial_load_falls_back_to_disk_cache_when_api_unreachable() {
        // Seed a cache file the load can fall back to.
        let tmp = tempfile::tempdir().unwrap();
        let cache_path = tmp.path().join("config-cache.json");
        let bootstrap = bootstrap_with_cache(cache_path.to_str().unwrap());
        persist_cache(&bootstrap, &small_config("from-cache")).await;

        // The configured api_url points at a refused-connection
        // address, so the fetch must fail and the loader must
        // fall back to the cache.
        let metrics = Metrics::new().unwrap();
        let client = reqwest::Client::new();
        let config = initial_load(&bootstrap, &client, &metrics).await.unwrap();
        assert_eq!(config.version, "from-cache");
    }

    #[tokio::test]
    async fn initial_load_propagates_when_cache_file_is_corrupt() {
        // A corrupted cache (file present but unparseable) is a data
        // integrity problem — the proxy must NOT silently swallow it
        // into an empty-config startup, because that would mask the
        // real problem from the operator.
        let tmp = tempfile::tempdir().unwrap();
        let cache_path = tmp.path().join("corrupt.json");
        tokio::fs::write(&cache_path, "not valid json")
            .await
            .unwrap();
        let bootstrap = bootstrap_with_cache(cache_path.to_str().unwrap());
        let metrics = Metrics::new().unwrap();
        let client = reqwest::Client::new();
        let result = initial_load(&bootstrap, &client, &metrics).await;
        assert!(
            result.is_err(),
            "expected failure when API is down and cache is corrupt",
        );
    }

    #[test]
    fn jittered_interval_stays_within_plus_minus_10_percent() {
        // Run many samples — the bound has to hold for every one.
        // We're verifying the property, not the distribution.
        for _ in 0..1000 {
            let d = jittered_interval(30);
            let secs = d.as_secs_f64();
            assert!(
                (27.0..=33.0).contains(&secs),
                "jittered interval {secs} out of ±10% bound",
            );
        }
    }

    #[test]
    fn jittered_interval_floors_at_one_second() {
        // Base = 0 would otherwise produce sub-millisecond sleeps
        // that hammer the server. The `.max(1.0)` floor protects
        // against accidental misconfiguration.
        let d = jittered_interval(0);
        assert!(d >= std::time::Duration::from_secs(1));
    }

    #[tokio::test]
    async fn cache_file_contains_valid_json() {
        let tmp = tempfile::tempdir().unwrap();
        let cache_path = tmp.path().join("c.json");
        let bootstrap = bootstrap_with_cache(cache_path.to_str().unwrap());

        persist_cache(&bootstrap, &small_config("v1")).await;
        let raw = std::fs::read_to_string(&cache_path).unwrap();
        // Roundtrip the JSON to assert it parses.
        let parsed: serde_json::Value = serde_json::from_str(&raw).unwrap();
        assert_eq!(parsed["version"], "v1");
        assert!(parsed["services"].is_array());
    }
}
