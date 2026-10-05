#[cfg(not(target_env = "msvc"))]
#[global_allocator]
static GLOBAL: tikv_jemallocator::Jemalloc = tikv_jemallocator::Jemalloc;

use std::path::PathBuf;

use clap::Parser;

/// Where the binary looks for its TOML config when neither `--config`
/// nor `GATEWAY_CONFIG` is supplied.
///
/// Debug builds resolve to the checked-in `config.dev.toml` next to the
/// source tree (via `CARGO_MANIFEST_DIR`, which is set at *compile*
/// time). That lets an IDE-launched binary "just work" — no flag, no
/// env var, no per-developer local file — while keeping the dev shared
/// secret out of the production code path entirely.
///
/// Release builds resolve to the container/system path. A dev build's
/// embedded manifest dir does NOT leak into a release binary because
/// the `else` branch is the only one compiled in that mode.
const DEFAULT_CONFIG_PATH: &str = if cfg!(debug_assertions) {
    concat!(env!("CARGO_MANIFEST_DIR"), "/config.dev.toml")
} else {
    "/etc/gateway/config.toml"
};

#[derive(Parser, Debug)]
#[command(name = "bosca-gateway", about = "Bosca HTTP Auth Gateway")]
struct Cli {
    #[arg(short, long, env = "GATEWAY_CONFIG", default_value = DEFAULT_CONFIG_PATH)]
    config: PathBuf,
}

fn main() -> anyhow::Result<()> {
    init_tracing();
    let cli = Cli::parse();

    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()?;

    runtime.block_on(run_server(&cli.config))
}

async fn run_server(config_path: &PathBuf) -> anyhow::Result<()> {
    let bootstrap = bosca_gateway::config::BootstrapConfig::load(config_path)?;
    let bind_addr = bootstrap.gateway.bind_addr()?;

    let http_client = reqwest::Client::builder()
        .pool_max_idle_per_host(20)
        .pool_idle_timeout(std::time::Duration::from_secs(90))
        .connect_timeout(std::time::Duration::from_secs(5))
        .redirect(reqwest::redirect::Policy::none())
        .build()?;

    let metrics = bosca_gateway::metrics::Metrics::new()?;

    let gateway_config =
        bosca_gateway::sync::initial_load(&bootstrap, &http_client, &metrics).await?;
    let config_handle = arc_swap::ArcSwap::from_pointee(gateway_config);
    let config_handle = std::sync::Arc::new(config_handle);

    let auth_state = bosca_gateway::auth::AuthState::new(&bootstrap, http_client.clone())?;

    // Health supervisor owns the per-upstream probe tasks. We seed it
    // with the initial config so probes start before the poll loop's
    // first tick; subsequent config refreshes go through
    // `apply_config` from inside the poll loop.
    let health_supervisor =
        bosca_gateway::health::HealthSupervisor::new(http_client.clone(), bootstrap.bosca.clone());
    health_supervisor.apply_config(&config_handle.load()).await;

    // Trusted-proxy CIDRs and the external-scheme fallback. Both are
    // honored per-request: when the inbound peer is in
    // `trusted_proxies`, the proxy reads `X-Forwarded-*` from the
    // request; otherwise it ignores them and uses the fallback values
    // below.
    let trusted_proxies =
        bosca_gateway::proxy::TrustedProxies::parse(&bootstrap.gateway.trusted_proxies)?;
    let fallback_scheme: std::sync::Arc<str> = bootstrap
        .gateway
        .external_scheme
        .clone()
        .unwrap_or_else(|| "http".to_string())
        .into();
    let fallback_port: std::sync::Arc<str> = bind_addr.port().to_string().into();

    let state = bosca_gateway::proxy::AppState {
        config: config_handle.clone(),
        bootstrap: std::sync::Arc::new(bootstrap.clone()),
        http_client: http_client.clone(),
        auth: auth_state,
        metrics: metrics.clone(),
        trusted_proxies,
        fallback_scheme,
        fallback_port,
    };

    let router = bosca_gateway::proxy::build_router(state);

    let (shutdown_tx, shutdown_rx) = tokio::sync::oneshot::channel::<()>();

    let sync_handle = tokio::spawn(bosca_gateway::sync::poll_loop(
        config_handle,
        bootstrap.clone(),
        http_client,
        metrics,
        health_supervisor,
    ));

    tokio::spawn(async move {
        wait_for_shutdown_signal().await;
        let _ = shutdown_tx.send(());
    });

    tracing::info!(%bind_addr, "bosca-gateway listening");
    let listener = tokio::net::TcpListener::bind(bind_addr).await?;
    // `into_make_service_with_connect_info` is what lets the per-request
    // handler extract `ConnectInfo<SocketAddr>` to populate
    // `{{request.clientIp}}` in header templates. Without this, the
    // ConnectInfo extractor would fail at runtime and the proxy would
    // 500 on every request.
    axum::serve(
        listener,
        router.into_make_service_with_connect_info::<std::net::SocketAddr>(),
    )
    .with_graceful_shutdown(async move {
        let _ = shutdown_rx.await;
    })
    .await?;

    sync_handle.abort();
    tracing::info!("bosca-gateway shut down");
    Ok(())
}

#[cfg(unix)]
async fn wait_for_shutdown_signal() {
    use tokio::signal::unix::{SignalKind, signal};
    let mut term = signal(SignalKind::terminate()).expect("install SIGTERM handler");
    let mut intr = signal(SignalKind::interrupt()).expect("install SIGINT handler");
    tokio::select! {
        _ = term.recv() => tracing::info!("SIGTERM received"),
        _ = intr.recv() => tracing::info!("SIGINT received"),
    }
}

#[cfg(not(unix))]
async fn wait_for_shutdown_signal() {
    tokio::signal::ctrl_c()
        .await
        .expect("install Ctrl+C handler");
    tracing::info!("Ctrl+C received");
}

fn init_tracing() {
    use tracing_subscriber::{EnvFilter, fmt, prelude::*};
    let filter = EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info"));
    let layer = if std::io::IsTerminal::is_terminal(&std::io::stderr()) {
        fmt::layer().with_target(false).boxed()
    } else {
        fmt::layer().json().with_current_span(true).boxed()
    };
    tracing_subscriber::registry()
        .with(filter)
        .with(layer)
        .init();
}
