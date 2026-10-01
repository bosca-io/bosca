//! Active health probing for proxied upstreams.
//!
//! Each gateway with a `health_check_path` gets its own `tokio` task
//! that GETs `{url}{health_check_path}` on the configured interval. A
//! small state machine with hysteresis decides when to flip the
//! reported status — a single failed probe is NOT enough to mark an
//! upstream down, and a single success is enough to mark it up. The
//! thresholds (`DOWN_THRESHOLD`, `UP_THRESHOLD`) tune the noise floor.
//!
//! When the local state machine transitions, the supervisor POSTs to
//! `{bosca}/api/v1/gateway/services/{id}/health` so Bosca can surface
//! the latest aggregate in Studio. Bosca's repository-level
//! `where status <> :status` guard means duplicate POSTs from multiple
//! proxy replicas are cheap no-ops.
//!
//! ## Lifecycle
//!
//! `HealthSupervisor::new` spawns one task per gateway and stores its
//! `JoinHandle`. When the config refreshes and `apply_config` is
//! called, the supervisor:
//!
//!   1. Aborts tasks for gateways that disappeared, had their
//!      `health_check_path` cleared, were disabled, or had their
//!      probe interval changed.
//!   2. Spawns new tasks for gateways that newly appeared, were
//!      re-enabled, or had a fresh interval.
//!   3. Leaves all other tasks running — no churn for unchanged
//!      gateways.
//!
//! The keying uses `(id, health_check_path, interval_secs)` so a
//! pure interval change is recognized as "respawn this task".

use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;

use serde::Serialize;
use tokio::sync::Mutex;
use tokio::task::JoinHandle;

use crate::config::{BoscaSettings, Gateway, GatewayConfig};

/// Wire-format status. UPPERCASE on the wire to match the canonical
/// Kotlin enum name (`UP`, `DOWN`) that Bosca's `GatewayHealthReport`
/// expects. The Kotlin enum has no `@SerialName` annotations, so
/// kotlinx-serialization decodes by the enum name. The DB still stores
/// lowercase via `EnumMapper` — that conversion is owned by the Bosca
/// side, never by the proxy.
#[derive(Copy, Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum HealthStatus {
    Up,
    Down,
}

/// Body of the POST sent to Bosca on every transition.
#[derive(Debug, Serialize)]
struct HealthReport<'a> {
    status: HealthStatus,
    #[serde(skip_serializing_if = "Option::is_none")]
    reason: Option<&'a str>,
}

/// Consecutive failed probes before flipping Up → Down. Picked so a
/// single network blip (DNS hiccup, transient 502) doesn't generate a
/// "Trino is down" notification. With the default 30s probe interval
/// this is a ~90 second detection window.
const DOWN_THRESHOLD: u32 = 3;

/// Consecutive successful probes before flipping Down → Up. One is
/// enough — operators want to know about recovery immediately, and
/// the next genuine outage will re-trigger the down sequence anyway.
const UP_THRESHOLD: u32 = 1;

/// Per-probe HTTP timeout. Capped well below typical probe intervals
/// so a stuck upstream doesn't pin the probe task on a long socket
/// wait. Anything that takes >5s to answer `/health` is, for our
/// purposes, down.
const PROBE_TIMEOUT_SECS: u64 = 5;

/// Composite key identifying a probe task's "shape". A change in any
/// of these means we need to abort + respawn rather than leave the
/// existing task running.
#[derive(Clone, Debug, Hash, PartialEq, Eq)]
struct TaskKey {
    gateway_id: String,
    probe_url: String,
    interval_secs: u64,
}

/// Owns the set of running per-gateway probe tasks and respawns them
/// in response to config changes. Single instance per proxy.
pub struct HealthSupervisor {
    tasks: Mutex<HashMap<TaskKey, JoinHandle<()>>>,
    http_client: reqwest::Client,
    bosca: BoscaSettings,
}

impl HealthSupervisor {
    pub fn new(http_client: reqwest::Client, bosca: BoscaSettings) -> Arc<Self> {
        Arc::new(Self {
            tasks: Mutex::new(HashMap::new()),
            http_client,
            bosca,
        })
    }

    /// Diff the current task set against the new config and reconcile.
    /// Idempotent — calling this with the same config twice spawns
    /// nothing new the second time.
    pub async fn apply_config(self: &Arc<Self>, config: &GatewayConfig) {
        let mut desired: HashMap<TaskKey, Gateway> = HashMap::new();
        for service in &config.services {
            if !service.enabled {
                continue;
            }
            let Some(path) = service
                .health_check_path
                .as_deref()
                .filter(|p| !p.is_empty())
            else {
                continue;
            };
            let probe_url = build_probe_url(&service.url, path);
            let key = TaskKey {
                gateway_id: service.id.clone(),
                probe_url,
                interval_secs: service.health_check_interval_secs,
            };
            desired.insert(key, service.clone());
        }

        let mut tasks = self.tasks.lock().await;

        // Cancel tasks that are no longer wanted (gateway removed,
        // disabled, health_check_path cleared, interval changed, or
        // URL changed).
        let stale: Vec<TaskKey> = tasks
            .keys()
            .filter(|k| !desired.contains_key(*k))
            .cloned()
            .collect();
        for key in stale {
            if let Some(handle) = tasks.remove(&key) {
                handle.abort();
                tracing::debug!(
                    gateway_id = %key.gateway_id,
                    "health probe cancelled — gateway gone, disabled, or shape changed"
                );
            }
        }

        // Spawn tasks for newly-desired shapes.
        for (key, gateway) in desired {
            if tasks.contains_key(&key) {
                continue;
            }
            let handle = spawn_probe(
                self.http_client.clone(),
                self.bosca.clone(),
                gateway,
                key.probe_url.clone(),
            );
            tracing::info!(
                gateway_id = %key.gateway_id,
                probe_url = %key.probe_url,
                interval_secs = key.interval_secs,
                "health probe started"
            );
            tasks.insert(key, handle);
        }
    }
}

/// Resolve the absolute URL the probe hits. We trim a trailing slash
/// off the gateway's base URL and require the path to start with `/`
/// (caller's responsibility — `apply_config` already filtered out
/// empty paths).
fn build_probe_url(base_url: &str, path: &str) -> String {
    let trimmed = base_url.trim_end_matches('/');
    if path.starts_with('/') {
        format!("{trimmed}{path}")
    } else {
        format!("{trimmed}/{path}")
    }
}

fn spawn_probe(
    http_client: reqwest::Client,
    bosca: BoscaSettings,
    gateway: Gateway,
    probe_url: String,
) -> JoinHandle<()> {
    tokio::spawn(async move {
        let mut state = ProbeState::default();
        let interval = Duration::from_secs(gateway.health_check_interval_secs.max(1));
        loop {
            tokio::time::sleep(interval).await;
            let (success, reason) = probe(&http_client, &probe_url).await;
            if let Some((new_status, reason)) = state.observe(success, reason) {
                report(
                    &http_client,
                    &bosca,
                    &gateway.id,
                    new_status,
                    reason.as_deref(),
                )
                .await;
            }
        }
    })
}

/// Issue one probe. Returns `(success, reason)` where `reason` is a
/// short diagnostic string the proxy will forward to Bosca when the
/// state machine flips on this observation.
async fn probe(http_client: &reqwest::Client, probe_url: &str) -> (bool, Option<String>) {
    let timeout = Duration::from_secs(PROBE_TIMEOUT_SECS);
    match tokio::time::timeout(timeout, http_client.get(probe_url).send()).await {
        Ok(Ok(resp)) => {
            let status = resp.status();
            if status.is_success() {
                (true, None)
            } else {
                (false, Some(format!("HTTP {}", status.as_u16())))
            }
        }
        Ok(Err(e)) => (false, Some(format!("transport: {e}"))),
        Err(_) => (false, Some(format!("timeout after {PROBE_TIMEOUT_SECS}s"))),
    }
}

/// Per-upstream state machine. Tracks the last *reported* status and
/// the consecutive run length of the current observation type. A
/// transition is published when (and only when) the run length crosses
/// the threshold for the opposite direction.
#[derive(Debug, Default)]
struct ProbeState {
    /// `None` until the first probe completes — we don't want to flip
    /// to Down on cold start before any observation has been made.
    last_reported: Option<HealthStatus>,
    consecutive_failures: u32,
    consecutive_successes: u32,
    /// The most recent failure reason, retained so the transition
    /// report includes the actual cause (HTTP code, error message)
    /// rather than a generic "down".
    last_failure_reason: Option<String>,
}

impl ProbeState {
    /// Apply one observation. Returns `Some((status, reason))` when
    /// the state machine transitions and a report should be sent;
    /// `None` otherwise.
    fn observe(
        &mut self,
        success: bool,
        reason: Option<String>,
    ) -> Option<(HealthStatus, Option<String>)> {
        if success {
            self.consecutive_successes = self.consecutive_successes.saturating_add(1);
            self.consecutive_failures = 0;
            if self.consecutive_successes >= UP_THRESHOLD
                && self.last_reported != Some(HealthStatus::Up)
            {
                self.last_reported = Some(HealthStatus::Up);
                self.last_failure_reason = None;
                return Some((HealthStatus::Up, None));
            }
        } else {
            self.consecutive_failures = self.consecutive_failures.saturating_add(1);
            self.consecutive_successes = 0;
            if let Some(r) = &reason {
                self.last_failure_reason = Some(r.clone());
            }
            if self.consecutive_failures >= DOWN_THRESHOLD
                && self.last_reported != Some(HealthStatus::Down)
            {
                self.last_reported = Some(HealthStatus::Down);
                return Some((HealthStatus::Down, self.last_failure_reason.clone()));
            }
        }
        None
    }
}

async fn report(
    http_client: &reqwest::Client,
    bosca: &BoscaSettings,
    gateway_id: &str,
    status: HealthStatus,
    reason: Option<&str>,
) {
    let url = format!(
        "{}/api/v1/gateway/services/{}/health",
        bosca.api_url.trim_end_matches('/'),
        gateway_id
    );
    let body = HealthReport { status, reason };
    match http_client
        .post(&url)
        .bearer_auth(&bosca.api_token)
        .json(&body)
        .send()
        .await
    {
        Ok(resp) if resp.status().is_success() => {
            tracing::info!(
                gateway_id,
                status = ?status,
                reason,
                "health transition reported to Bosca"
            );
        }
        Ok(resp) => {
            tracing::warn!(
                gateway_id,
                status = ?status,
                http_status = %resp.status(),
                "Bosca rejected health report"
            );
        }
        Err(e) => {
            tracing::warn!(
                gateway_id,
                status = ?status,
                error = %e,
                "failed to deliver health report to Bosca"
            );
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn single_failure_does_not_flip_to_down() {
        let mut s = ProbeState::default();
        // Cold-start state machine: first observation is a success so
        // we expect a transition to Up (UP_THRESHOLD = 1).
        assert_eq!(s.observe(true, None), Some((HealthStatus::Up, None)));
        // One failure: not enough — must NOT transition.
        assert_eq!(s.observe(false, Some("HTTP 503".into())), None);
        assert_eq!(s.last_reported, Some(HealthStatus::Up));
    }

    #[test]
    fn three_consecutive_failures_flip_to_down() {
        let mut s = ProbeState::default();
        s.observe(true, None);
        s.observe(false, Some("HTTP 503".into()));
        s.observe(false, Some("HTTP 503".into()));
        let t = s.observe(false, Some("HTTP 503".into()));
        assert_eq!(t, Some((HealthStatus::Down, Some("HTTP 503".into()))));
    }

    #[test]
    fn one_success_after_down_flips_back_to_up() {
        let mut s = ProbeState::default();
        s.observe(true, None);
        s.observe(false, Some("HTTP 503".into()));
        s.observe(false, Some("HTTP 503".into()));
        s.observe(false, Some("HTTP 503".into()));
        assert_eq!(s.last_reported, Some(HealthStatus::Down));
        let t = s.observe(true, None);
        assert_eq!(t, Some((HealthStatus::Up, None)));
        // Reason is cleared on recovery so a follow-up failure
        // doesn't carry the old reason.
        assert!(s.last_failure_reason.is_none());
    }

    #[test]
    fn duplicate_observation_at_same_status_does_not_re_report() {
        let mut s = ProbeState::default();
        // First success transitions cold-start → Up.
        s.observe(true, None);
        // Every subsequent success while already Up is a no-op.
        assert_eq!(s.observe(true, None), None);
        assert_eq!(s.observe(true, None), None);
    }

    #[test]
    fn intermittent_failure_resets_failure_run() {
        let mut s = ProbeState::default();
        s.observe(true, None);
        s.observe(false, Some("flap".into()));
        s.observe(false, Some("flap".into()));
        // One success between failures resets the failure count.
        s.observe(true, None);
        // So another two failures should NOT cross the threshold yet —
        // we need three in a row.
        assert_eq!(s.observe(false, Some("flap".into())), None);
        assert_eq!(s.observe(false, Some("flap".into())), None);
    }

    #[test]
    fn cold_start_failure_does_not_immediately_report_down() {
        // A proxy that just booted shouldn't report Down before it has
        // ever seen the upstream succeed — three failures from a fresh
        // state machine still flip to Down, but `last_reported` is None
        // up to the threshold so we never emit a Down→Down redundant
        // report.
        let mut s = ProbeState::default();
        assert_eq!(s.observe(false, Some("dns".into())), None);
        assert_eq!(s.observe(false, Some("dns".into())), None);
        let t = s.observe(false, Some("dns".into()));
        // Crossing the threshold from cold start is a legitimate
        // transition — last_reported was None, now becomes Down.
        assert_eq!(t, Some((HealthStatus::Down, Some("dns".into()))));
    }

    #[test]
    fn build_probe_url_joins_path() {
        assert_eq!(
            build_probe_url("http://trino.internal:8080", "/v1/info"),
            "http://trino.internal:8080/v1/info"
        );
        // Trailing slash on base URL collapses.
        assert_eq!(
            build_probe_url("http://trino.internal:8080/", "/v1/info"),
            "http://trino.internal:8080/v1/info"
        );
        // Path without leading slash gets one inserted.
        assert_eq!(
            build_probe_url("http://trino.internal:8080", "v1/info"),
            "http://trino.internal:8080/v1/info"
        );
    }
}
