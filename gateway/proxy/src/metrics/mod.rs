use prometheus::{
    Encoder, HistogramOpts, HistogramVec, IntCounterVec, Opts, Registry, TextEncoder,
};

use crate::config::GatewayAuthMethod;

#[derive(Clone)]
pub struct Metrics {
    registry: std::sync::Arc<Registry>,
    requests: IntCounterVec,
    duration: HistogramVec,
    auth_attempts: IntCounterVec,
    config_polls: IntCounterVec,
}

impl Metrics {
    pub fn new() -> anyhow::Result<Self> {
        let registry = Registry::new_custom(Some("bosca".into()), None)?;

        let requests = IntCounterVec::new(
            Opts::new(
                "gateway_requests_total",
                "Total proxied requests by upstream and status bucket",
            ),
            &["upstream", "status"],
        )?;
        registry.register(Box::new(requests.clone()))?;

        let duration = HistogramVec::new(
            HistogramOpts::new(
                "gateway_request_duration_seconds",
                "Request latency by upstream",
            )
            .buckets(vec![
                0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0,
            ]),
            &["upstream"],
        )?;
        registry.register(Box::new(duration.clone()))?;

        let auth_attempts = IntCounterVec::new(
            Opts::new(
                "gateway_auth_attempts_total",
                "Auth attempts by method and result",
            ),
            &["method", "result"],
        )?;
        registry.register(Box::new(auth_attempts.clone()))?;

        let config_polls = IntCounterVec::new(
            Opts::new(
                "gateway_config_polls_total",
                "Config poll attempts by result",
            ),
            &["result"],
        )?;
        registry.register(Box::new(config_polls.clone()))?;

        Ok(Self {
            registry: std::sync::Arc::new(registry),
            requests,
            duration,
            auth_attempts,
            config_polls,
        })
    }

    pub fn requests_total(&self, upstream: &str, status: &str) {
        self.requests.with_label_values(&[upstream, status]).inc();
    }

    pub fn request_duration(&self, upstream: &str, seconds: f64) {
        self.duration
            .with_label_values(&[upstream])
            .observe(seconds);
    }

    pub fn auth_attempts(&self, method: &GatewayAuthMethod, success: bool) {
        let method_str = match method {
            GatewayAuthMethod::Oauth2 => "oauth2",
            GatewayAuthMethod::Jwt => "jwt",
            GatewayAuthMethod::Basic => "basic",
            GatewayAuthMethod::None => "none",
        };
        let result = if success { "success" } else { "failure" };
        self.auth_attempts
            .with_label_values(&[method_str, result])
            .inc();
    }

    pub fn config_poll(&self, success: bool) {
        let result = if success { "success" } else { "failure" };
        self.config_polls.with_label_values(&[result]).inc();
    }

    pub fn render(&self) -> Vec<u8> {
        let mut buf = Vec::with_capacity(4096);
        let encoder = TextEncoder::new();
        let families = self.registry.gather();
        encoder.encode(&families, &mut buf).expect("encode metrics");
        buf
    }
}
