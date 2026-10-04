# Bosca on a two-VM Docker Swarm

`bosca swarm` generates a reusable private configuration, renders Docker Swarm stacks, and can bootstrap and deploy one manager VM and one or more worker VMs over SSH. The default configuration contains two generic sites; add entries in `sites` as needed. Removing an installed site requires a separate decommissioning procedure. No site identity is built into the CLI or this repository.

## Create and review the configuration

```bash
bosca swarm init --config ./swarm.local.json
bosca swarm render --config ./swarm.local.json
```

After editing the config, encrypt its credential values in place:

```bash
bosca swarm encrypt --config ./swarm.local.json
bosca swarm decrypt --config ./swarm.local.json
```

`encrypt` leaves domains, image tags, and other ordinary JSON settings readable. It encrypts shared and
per-site `secrets`, registry passwords, BML and recommendation tokens, and the backup secret access key.
`decrypt` restores those values in place when you need to edit them. Both commands accept `--output`
to write a separate owner-only copy instead. They prompt for a passphrase without echoing it; for
unattended commands, supply `BOSCA_SWARM_CONFIG_PASSPHRASE` through a secret manager. Keep a separate
backup of the passphrase: the encrypted values cannot be recovered without it. Every Swarm command reads
encrypted values directly, and `setup-tokens` keeps newly created tokens encrypted. The JSON is written
atomically with owner-only permissions. `render` deliberately produces plaintext service configuration,
so protect its output directory. When an encrypted config is deployed without `--output`, the CLI uses
and removes a temporary local render directory. Decrypt before renaming a site `id`; each encrypted site
value is bound to that id so secrets cannot be moved between sites unnoticed.

Edit `swarm.local.json` before deployment:

- Set the manager and worker SSH targets and the manager's private advertise address. Set a worker's optional `advertiseAddress` to its private address when it has multiple network interfaces.
- Set each site's `id`, base `domain`, mail sender and `mailerType` (`sendgrid` or `mailgun`), Redis database number, and BML Artifacts URL/token. Site IDs must be unique lower-case letters and digits. Each site gets `<id>-bosca` and `<id>-warehouse` PostgreSQL databases. A site's public hostnames are derived from its base domain as in the Kubernetes deployment (see [Hostnames](#hostnames)); `gitDomain` is optional and only overrides the `git.` host. Set `rootImage` and optionally `rootPort` to serve a website at the root and `www` hosts.
- If a site's `rootImage` comes from another private registry, set `rootRegistryAuth.server` to the image's registry host. Set `username` to `api_token` and leave `password` blank for the one-time `setup-tokens` command to mint a pull-only token, or provide an existing `username` and `password` in the owner-only config. `passwordEnv` can supply the password during deployment instead. The CLI logs in to both the platform registry and any configured root image registries before deploying stacks.
- The root website service receives `BML_GRAPHQL_ENDPOINT` pointing to that site's Bosca server over the Swarm overlay network, so BML pages can load data and proxy browser GraphQL calls.
- Set `profilesWeb.host` and `notificationsWeb.host` for each site when using custom account or notification hostnames; their defaults are `profiles.<domain>` and `notifications.<domain>`. The separate `messages.<domain>` host serves the BML message server's public assets and tracking paths and continues to accept existing notification links. `profilesWeb.cookieDomain` defaults to the base domain and must cover both the base and Profiles hosts. Set each app's `brandName` to its display name; it defaults to `mailFromName`. Optional `logoUrl`, `primaryColor`, and `accentColor` values become the app's brand environment settings. Colors must be CSS hex values.
- Replace every `SET_NEW_TAG` image tag with images built from the changes in this workspace. Published older images do not contain the site isolation configuration. The `profiles-web` and `notifications-web` images have separate pinned tags in `images`. Set `registryAuth.username` and either `registryAuth.password` in the owner-only config or the named password environment variable if the image registry requires login. The inline value takes precedence.
- To enable nightly off-VM backups, set `backup.repository` to a restic repository (for example `s3:https://sfo3.digitaloceanspaces.com/<bucket>/bosca`) and, for an `s3:` repository, `backup.accessKeyId` and `backup.secretAccessKey`. See [Backups](#backups).
- Keep the generated secrets in the config. The CLI reuses them on later renders and deployments. The config and generated bundle contain credentials and are created with owner-only permissions. Store and back them up securely; `secrets.backupPassword` encrypts the backups, and they cannot be read without it. Each site's first `admin` password is its `secrets.initialAdmin` value; change it after the first login.

`render` writes `bosca-swarm-generated/` beside the config by default. It contains the stack files, Caddy, NATS and PgBouncer configuration, Trino catalogs and rules, the TensorFlow Serving start script, the backup scripts, PostgreSQL bootstrap, and bucket names. You can use `--output <directory>` to choose another location. Inspect the files before deployment.

## Bootstrap and deploy

```bash
bosca swarm bootstrap --config ./swarm.local.json
bosca swarm deploy --config ./swarm.local.json
bosca swarm setup-tokens --config ./swarm.local.json
bosca swarm status --config ./swarm.local.json
```

The CLI runs on the operator's computer and needs `ssh` and `tar`. The VMs need GNU `tar`. SSH access to both VMs must work without prompts, and the remote account needs passwordless `sudo`. Run `bootstrap` once before the first deployment, and again when adding a worker or repairing node labels. It configures UFW first: the current SSH port, traffic within each node's private subnet on its private interface, and public TCP 80/443 on the manager. It installs Docker Engine from Docker's apt repository on Ubuntu or Debian if needed, initializes Swarm, joins the workers, labels the manager for stateful services, and labels the first worker for recommendation models. On other Linux distributions, install Docker Engine and UFW first.

`deploy` verifies that the nodes and placement labels are ready, then uploads the rendered files, creates the overlay network, deploys infrastructure, creates databases and buckets when needed, and deploys each site and the edge proxy. It does not install Docker or change Swarm membership. Database bootstrap is recorded under the manager's data root and skipped on repeat deployments when its SQL and credentials are unchanged. For an existing installation without that record, the CLI checks its databases, roles, and extensions once and records them without replaying the bootstrap SQL. Re-running deployment keeps the generated credentials and existing database state. The data root on the manager is readable only by root; configuration files that services read are owned by the image's own user and not readable by other accounts. Buckets are directories in S3Proxy's filesystem store, created owner-only so they stay private. Each deployment brings the uploaded configuration in line with the rendered files, updating existing files in place and removing files the render no longer produces, and NATS, Trino, and Caddy restart when their configuration changes.

For an image upgrade, change the relevant image tags in the same config file and run `bosca swarm deploy` again. The CLI resolves changed image tags from the registry and Swarm updates services whose image changed. Use new, immutable tags for releases. To roll back an image, restore its previous tag in the config and deploy again. Database migrations and data rollback need separate review; the CLI does not automatically discover releases or reverse migrations.

If a provider firewall is active, allow Swarm traffic between the VMs there as well (TCP 2377, TCP/UDP 7946, UDP 4789). Point every hostname of each site at the manager VM (eleven records per site, plus `www` when `rootImage` is set, or the base domain plus a `*.<domain>` wildcard); expose TCP 80 and 443 there for Caddy. Other service ports stay on the overlay network. The CLI waits for PostgreSQL and PgBouncer before updating site services, then waits up to 20 minutes for every service to report one healthy replica and finish its Swarm update; the embeddings model downloads on first start, so the first deployment can take several minutes. The CLI reports waiting services and public URLs as it retries. A service that does not become healthy is reported with its latest task error. Finally the CLI checks the public API, Studio, Profiles, and Notifications HTTPS endpoints and, when `rootImage` is set, the root and `www` websites. Use `--skip-public-check` only when DNS or HTTPS is intentionally not yet available.

## Services and isolation

One shared PostgreSQL with pgvector provides separate operational and warehouse databases and roles per site. As in the Kubernetes deployment, the Bosca services connect through PgBouncer in transaction mode (up to 1000 client connections, 25 server connections per database and role), so their connection pools cannot exhaust the shared server; PostgreSQL's connection limit is sized from the number of sites. Trino connects to PostgreSQL directly. PgBouncer logs in to PostgreSQL as the connecting role, so each site still reaches only its own databases. Shared NATS has an account per site. Redis uses distinct logical databases and key namespaces. Meilisearch uses per-site index prefixes. S3Proxy uses separate content and warehouse buckets. Trino has per-site catalogs and file access rules. Image processing and the text-embeddings service are shared; embeddings carry no site data. Each site runs its own Bosca server, runner, Git server, analytics collector, artifacts server, Studio, Profiles, Notifications, BML message server, recommendation trainer, model loader, and TensorFlow Serving. Caddy routes by hostname.

The manager runs the stateful services: PostgreSQL, PgBouncer, NATS, Redis, Meilisearch, S3Proxy, Trino, Caddy and the backups. Application services run on the workers with memory limits but no CPU caps, allowing CPU overprovisioning. The first worker also holds each site's recommendation-model volume, shared by its model loader and TensorFlow Serving. Workers must be x86_64: the embeddings and TensorFlow Serving images are published for amd64 only. Every service rotates its logs (three 10 MB files). Services with a health check restart when it fails, and stateless application services start their replacement before stopping the old task and roll back when it does not become healthy.

PgBouncer keeps prepared statements across pooled transactions. After a migration that changes a column's type, a cached statement can fail with `cached plan must not change result type`; restart the pooler with `sudo docker service update --force infra_pgbouncer` on the manager to clear it.

## Hostnames

Caddy accepts HTTP/1.1 and HTTP/2 over HTTPS and enables full-duplex HTTP/1.1 requests, so a client can continue sending a request body while receiving its response. HTTP/2 supports concurrent reads and writes directly. The overlay connections to application services use HTTP/1.1; WebSocket upgrades and streaming responses pass through the proxy.

Each site's hostnames and routes mirror the Kubernetes Gateway HTTPRoutes, with `<domain>` as the site's base domain:

| Hostname | Service |
| --- | --- |
| `api.<domain>`, `upload.<domain>`, `ws.<domain>` | Bosca server |
| `<domain>`, `www.<domain>` | Website image when `rootImage` is set; otherwise the base domain serves Bosca and `www` is unused |
| `studio.<domain>` | Studio; `/api/v1/events` and `/api/v1/installation` go to the analytics collector, and `/graphql`, `/graphqlws`, `/oauth2`, `/api/v1`, `/files`, and `/content` go to the Bosca server |
| `profiles.<domain>` | Profiles self-service account site |
| `a.<domain>` | Analytics collector |
| `git.<domain>` | Git server |
| `artifacts.<domain>` | Artifacts server (the site's registry) |
| `notifications.<domain>` | Notifications preferences and unsubscribe pages |
| `messages.<domain>` | Message server assets (`/assets`) and engagement tracking (`/c`, `/o`); existing notification links continue to work |

Studio remains the application origin for administration and the collaborative editor at `https://studio.<domain>`. Profiles serves account self-service at `https://profiles.<domain>`. Email unsubscribe and preference links use `https://notifications.<domain>`; `https://messages.<domain>` keeps its `/assets`, `/c`, and `/o` paths on the BML message server and still accepts older preference links. Uploads use `https://upload.<domain>`. There is no `admin.` host, so admin sign-in callbacks use the base domain. When a website image is configured, Caddy still routes `/graphql`, `/oauth2`, `/api/v1`, `/files`, `/content`, and `/graphqlws` at the base domain to Bosca. The server accepts `https://studio.<domain>` as an additional passkey origin so passkeys can be registered from Studio.

The manager holds the stateful bind mounts, so this initial two-VM arrangement has one stateful failure point; configure [backups](#backups) and keep a copy of the private CLI configuration elsewhere. A two-manager arrangement is not a failover pair because Swarm needs a manager majority. Both site stacks and shared services are operated under one trust boundary: S3Proxy and Meilisearch still use shared root credentials, and Trino's internal HTTP user names are not authentication. The catalog rules prevent accidental cross-site queries, but are not an independent security boundary against a compromised container.

## Recommendations and embeddings

Embeddings are enabled: the server and runner use the shared text-embeddings service (EmbeddingGemma through Text Embeddings Inference, as in the Kubernetes deployment). Recommendation training runs on each site's trainer service instead of Kubernetes Jobs. Bosca starts a training run for a model version in the background and polls it, so a long run holds no request open.

The trainer and model loader authenticate with three tokens, as the Kubernetes `recommendation-ml` Secret provides them. After the first deployment, run `bosca swarm setup-tokens --config ./swarm.local.json`. It signs into each site's API as the bootstrap `sa` account, creates the Bosca and scoped artifact push/pull tokens, saves each raw token in the private config, and reapplies the site stacks. The Bosca token carries the service account's broad access; protect the config and service environment accordingly. A retry skips tokens already saved, and it can reapply a stack after an interrupted deployment. Run it before changing the bootstrap `sa` password. Until then, `deploy` prints a notice, and the trainer and loader run but cannot train or load models. Build the trainer image from this workspace: its health check and background runs need the multi-threaded trainer service, and an older single-threaded trainer would fail its health check, and be restarted, while it trains.

When a site's `rootRegistryAuth.username` is `api_token` and its password is blank, the same one-time `setup-tokens` command also creates and saves a token scoped to pull that site's root image repository. Run it after the site API is available and before deploying the private root image. `setup-tokens --skip-public-check` applies the stacks without checking public HTTPS endpoints when unrelated DNS records are still being configured.

## Backups

With `backup.repository` set, the deployment runs two services on the manager. At `backup.hour` (UTC) `backup-dump` writes a `pg_dump` of every site database and the PostgreSQL roles to `<dataRoot>/backups`. Thirty minutes later `backup` takes an encrypted restic snapshot of those dumps, the S3 objects, NATS data, Meilisearch's daily snapshots, Caddy certificates and the rendered configuration, then prunes to `backup.keepDaily`, `keepWeekly` and `keepMonthly` snapshots. A snapshot also runs at start-up when the last success is more than a day old. `bosca swarm status` shows the last successful and failed backup. NATS data is copied while NATS is running. Redis holds caches and is not backed up.

To run a backup now, run the scripts on the manager: `sudo docker exec $(sudo docker ps -q --filter label=com.docker.swarm.service.name=infra_backup-dump) /bin/sh /scripts/backup-dump.sh once`, then the same with `infra_backup` and `/scripts/backup.sh once`.

To restore, use restic with the same repository, `secrets.backupPassword` and credentials: `restic snapshots`, then `restic restore latest --target <directory>`. Load each database with `pg_restore --no-owner -d <database> <directory>/data/postgres-dumps/postgres/<database>.dump` after recreating it with the bootstrap, and copy the S3, NATS and Caddy directories back into the data root with the services stopped. Test a restore before relying on the installation.

## Mail and checks

For Mailgun, create and verify a sending domain for each site in Mailgun and publish the DNS records shown for that domain. In each site's Studio, open **System → Integrations → Mailgun** and save a domain sending API key, the verified sending domain, and the API base URL (`https://api.mailgun.net` for US domains or `https://api.eu.mailgun.net` for EU domains). The credentials are stored in that site's encrypted platform configuration and take effect for new sends without a restart. Set that site's `mailerType` to `mailgun` in the private Swarm config and deploy to select the Mailgun sender. Keep `mailFromEmail` on the same root or sending domain and check its DMARC alignment.

For SendGrid, configure each site's API key and webhook verification key in its Studio system integrations. Verify each sender domain and point its SendGrid Event Webhook at `https://<domain>/api/v1/webhooks/sendgrid`.

Check login, a Git clone, object upload, search, a queued job, an analytics event, and a test email from the selected provider for each site. Check Trino catalog access and perform a backup restore test before relying on the installation. The Kubernetes controller is disabled, and CI agents are not launched by this installer.
