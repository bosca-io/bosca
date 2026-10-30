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
- Set each site's `serverMemory` when its Bosca server needs a different memory limit. It defaults to `768M`; for example, `1536M` allows 1.5 GiB. CPU remains uncapped.
- Set each site's `id`, base `domain`, mail sender and default `mailerType` (`sendgrid`, `mailgun`, or `smtp`), Redis database number, and BML Artifacts URL/token. Studio's **System → Integrations → Choose email provider** setting overrides this default without redeployment. Site IDs must be unique lower-case letters and digits. Each site gets `<id>-bosca` and `<id>-warehouse` PostgreSQL databases. A site's public hostnames are derived from its base domain as in the Kubernetes deployment (see [Hostnames](#hostnames)); `gitDomain` overrides the Git transport hostname; `atlas.domain` overrides the Atlas hostname within the shared cookie domain. Set `rootImage` and optionally `rootPort` to serve a website at the root and `www` hosts.
- If a site's `rootImage` comes from another private registry, set `rootRegistryAuth.server` to the image's registry host. Set `username` to `api_token` and leave `password` blank for the one-time `setup-tokens` command to mint a pull-only token, or provide an existing `username` and `password` in the owner-only config. `passwordEnv` can supply the password during deployment instead. The CLI logs in to both the platform registry and any configured root image registries before deploying stacks.
- The root website service receives `BML_GRAPHQL_ENDPOINT` pointing to that site's Bosca server over the Swarm overlay network, so BML pages can load data and proxy browser GraphQL calls.
- Set a site's `rootEnv` map for additional website runtime environment variables. For the Bosca Nuxt website, set `rootEnv.NUXT_CLI_INSTALL_SCRIPT_URL` to `https://<artifacts-host>/raw/bosca/bosca-cli/<cli-version>/install.sh`. The website then configures that raw repository as the installer's package source. Use the CLI artifact version, which is independent of the website image tag. These settings apply only to that site's `root-web` service; run `bosca swarm deploy` after changing them.
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

If a provider firewall is active, allow Swarm traffic between the VMs there as well (TCP 2377, TCP/UDP 7946, UDP 4789). Point every hostname of each site at the manager VM (twelve records per site, including Atlas, plus `www` when `rootImage` is set, or the base domain plus a `*.<domain>` wildcard); expose TCP 80 and 443 there for Caddy. Other service ports stay on the overlay network. The CLI waits for PostgreSQL, PgBouncer, and Dragonfly before updating site services, then waits up to 20 minutes for every service to report one healthy replica and finish its Swarm update; the embeddings model downloads on first start, so the first deployment can take several minutes. The CLI reports waiting services and public URLs as it retries. A service that does not become healthy is reported with its latest task error. Finally the CLI checks the public API, Studio, Profiles, Notifications, and Atlas HTTPS endpoints and, when `rootImage` is set, the root and `www` websites. Atlas checks include its login page and API readiness. Use `--skip-public-check` only when DNS or HTTPS is intentionally not yet available.

## Dragonfly

The `dragonfly` service runs Dragonfly `v2.0.2`, configured through
`images.dragonfly`. Loading an existing Swarm configuration adds this image
setting automatically; an old `images.redis` entry is unused. Clients connect to
`dragonfly` using the existing `redis` backend selector and each site's
`redisDatabase`. Dragonfly runs in standalone mode with separate storage at
`<dataRoot>/dragonfly`.

For installations using Redis only for disposable cache and lock data, this
starts fresh without a data import. On the first switch, pause traffic and stop
all clients of the old Redis service, including site applications and Studio,
then run `bosca swarm deploy --config ./swarm.local.json` using the updated CLI.
Deployment waits for Dragonfly readiness and starts the sites with the new
endpoint. Stopping clients together prevents old and new processes from using
separate lock stores during cutover. Resume traffic after verifying the sites.

Swarm retains services omitted from a stack update. After all clients have
switched, remove the old service on the manager with
`sudo docker service rm infra_redis`. Its `<dataRoot>/redis` directory is left
untouched. This fresh-start procedure assumes no jobs or counters need to be
retained from the old store.

Dragonfly writes a single `dump.rdb` snapshot every minute and on clean shutdown,
with 60 seconds allowed for shutdown. Crash recovery uses the last completed
snapshot. Backups include `<dataRoot>/dragonfly` when the `redis` backend is
selected for jobs or counters. For later Dragonfly upgrades, change
`images.dragonfly` and deploy again; `swarm update-images` updates Bosca application
images.

## Services and isolation

Each site's runner uses the application configuration bundled in its image,
including `fumsQueueRunner` for API.Bible usage reports. Swarm supplies the site's
database and queue credentials through the runner's environment.

Each site's `fumsEnabled` setting controls API.Bible usage reporting and defaults
to `true`. Set `"fumsEnabled": false` on that site's entry in `swarm.local.json` to
disable it, then run `bosca swarm deploy --config ./swarm.local.json` to apply the
change. The renderer passes this setting as `FUMS_TRANSFORM_ENABLED` only to that
site's collector. The runner's transform remains disabled; delivery jobs use its
separate `fumsQueueRunner` worker.

One shared PostgreSQL with pgvector provides separate operational and warehouse databases and roles per site. As in the Kubernetes deployment, the Bosca services connect through PgBouncer in transaction mode (up to 1000 client connections, 25 server connections per database and role), so their connection pools cannot exhaust the shared server; PostgreSQL's connection limit is sized from the number of sites. Trino connects to PostgreSQL directly. PgBouncer logs in to PostgreSQL as the connecting role, so each site still reaches only its own databases. Shared NATS has an account per site. Dragonfly uses distinct logical databases and Pub/Sub namespaces. Each site selects NATS or Redis independently for caches, pub/sub, job queues, locks, and counters (see [Backend selection](#backend-selection)). Meilisearch uses per-site index prefixes. S3Proxy uses separate content and warehouse buckets. Trino has per-site catalogs and file access rules. Image processing and the text-embeddings service are shared; embeddings carry no site data. Each site runs its own Bosca server, runner, Atlas, analytics collector, Studio, Profiles, Notifications, BML message server, recommendation trainer, model loader, and TensorFlow Serving. Caddy routes by hostname.

The manager runs the stateful services: PostgreSQL, PgBouncer, NATS, Dragonfly, Meilisearch, S3Proxy, Trino, Caddy and the backups. Application services run on the workers with memory limits but no CPU caps, allowing CPU overprovisioning. The first worker also holds each site's recommendation-model volume, shared by its model loader and TensorFlow Serving. Workers must be x86_64: the embeddings and TensorFlow Serving images are published for amd64 only. Every service rotates its logs (three 10 MB files). Services with a health check restart when it fails, and stateless application services start their replacement before stopping the old task and roll back when it does not become healthy.

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
| `atlas.<domain>` | Atlas Git/artifact UI on 8095, API/transports on 8094, selected by path |
| `git.<domain>` | Git transport/API alias; Git UI pages redirect to Atlas by default |
| `artifacts.<domain>` | Native registry URLs; file protocols use Atlas’s private route prefix |
| `notifications.<domain>` | Notifications preferences and unsubscribe pages |
| `messages.<domain>` | Message server assets (`/assets`) and engagement tracking (`/c`, `/o`); existing notification links continue to work |

Studio remains the application origin for administration and the collaborative editor at `https://studio.<domain>`. Profiles serves account self-service at `https://profiles.<domain>`. Email unsubscribe and preference links use `https://notifications.<domain>`; `https://messages.<domain>` keeps its `/assets`, `/c`, and `/o` paths on the BML message server and still accepts older preference links. Uploads use `https://upload.<domain>`. There is no `admin.` host, so admin sign-in callbacks use the base domain. When a website image is configured, Caddy still routes `/graphql`, `/oauth2`, `/api/v1`, `/files`, `/content`, and `/graphqlws` at the base domain to Bosca. The server accepts `https://studio.<domain>` as an additional passkey origin so passkeys can be registered from Studio.

The manager holds the stateful bind mounts, so this initial two-VM arrangement has one stateful failure point; configure [backups](#backups) and keep a copy of the private CLI configuration elsewhere. A two-manager arrangement is not a failover pair because Swarm needs a manager majority. Both site stacks and shared services are operated under one trust boundary: S3Proxy and Meilisearch still use shared root credentials, and Trino's internal HTTP user names are not authentication. The catalog rules prevent accidental cross-site queries, but are not an independent security boundary against a compromised container.

## Backend selection

Choose `nats` or `redis` independently for each site's infrastructure backends in `swarm.local.json`. For Redis caching with NATS pub/sub:

```json
"cacheBackend": "redis",
"pubsubBackend": "nats",
"jobQueueBackend": "nats",
"distributedLockBackend": "nats",
"counterBackend": "nats"
```

Every setting defaults to `nats`. Each selection applies consistently across the site's server, runner, Atlas, analytics collector and optional separate Git/registry services, while different sites can have different selections:

| Site setting | Application environment |
| --- | --- |
| `cacheBackend` | `CACHE_TYPE` |
| `pubsubBackend` | `PUBSUB_TYPE` |
| `jobQueueBackend` | `JOB_QUEUE_FACTORY` |
| `distributedLockBackend` | `DISTRIBUTED_LOCK_TYPE` |
| `counterBackend` | `COUNTER_TYPE` |

Dragonfly keeps the site's existing `redisDatabase` and Pub/Sub namespace; NATS uses its existing account. Use images built with these configuration changes, including the Git server's selectable pub/sub binding and the collector's Redis connection settings.

Both infrastructure services stay deployed. Chat and collaboration use NATS directly, the analytics event pipeline stores and consumes batches through NATS JetStream, and Studio uses Dragonfly for editor collaboration. Backups include Dragonfly data whenever a site uses Redis for job queues or counters.

For a new installation, choose the backends before the first `bosca swarm deploy`. To change an existing site's selections in either direction:

1. Pause incoming writes and CI agents for the site. When changing `jobQueueBackend`, also stop scheduling new work and finish or explicitly resolve its queued, running, and suspended jobs on the current backend.
2. Stop the site's Bosca processes together on the manager to keep their backend selections consistent during the change. For a site named `site1`:

   ```bash
   sudo docker service scale site1_server=0 site1_runner=0 site1_atlas=0 site1_collector=0
   ```

3. Change the desired site settings, review `bosca swarm render --config ./swarm.local.json`, and run `bosca swarm deploy --config ./swarm.local.json`. Resume traffic and CI agents after readiness checks pass.

Changing a setting does not copy its queued jobs, cached entries, or counter values between backends. Changing only `cacheBackend` leaves jobs, pub/sub, locks, and counters on their selected backends; caches populate as the site serves requests again. The broker data directories remain in place.

Atlas replaces the Git and artifacts processes by default. Set `images.atlas` to
an `atlas-server` release built from these changes; `bosca swarm update-images`
recognizes both `atlas` and `atlas-ui`. Existing database/storage, tokens and the
pipeline key are reused. Add DNS for `atlas.<domain>` and register
`https://atlas.<domain>/oauth2/google/callback` with the site's Google client.
The default public origin is `https://atlas.<domain>`; clone URLs retain
`https://git.<domain>`. The existing platform runner continues consuming jobs.
Set `atlas.runnerEnabled: true` to add Atlas queue consumers; the platform runner
continues owning event listeners and scheduling.

Each site's optional `atlas` settings select independent processes:

```json
"atlas": {
  "domain": "build.example.com",
  "separateUi": true,
  "separateGit": false,
  "separateArtifacts": false,
  "runnerEnabled": false
}
```

`separateUi` starts `atlas-ui-server` with the same Atlas origin and a private
GraphQL endpoint. `separateGit` starts the backend-only Git server;
`separateArtifacts` starts the existing registry service. Both the Atlas hostname
and their companion hostnames send protocol requests to the selected backend.
When a backend is off, those routes use Atlas. UI pages always stay on Atlas;
Git-host UI entry points redirect there. Shared `/graphql` remains on Atlas. These options also use
`images.atlas-ui`, `images.git` and `images.artifacts`, respectively. After new
services and ingress become healthy and Atlas's API passes readiness, deployment retires only the replaced managed
Git/registry/UI services; their data and other services remain intact.

## OAuth redirects

Set each site's `oauthRedirects` to the URLs users may return to after sign-in:

```json
"oauthRedirects": [
  "https://studio.example.com",
  "https://profiles.example.com",
  "https://www.example.com"
]
```

Include the Atlas origin in an explicit list when users should return to Atlas.
The server checks the destination's origin (scheme, host, and port), so paths on an allowed origin are also accepted. Use HTTP URLs for local development when needed. Omitting the setting or using `null` keeps the server/runner defaults (`https://studio.<domain>` and `http://localhost:3000`) and Atlas’s own origin. A configured list replaces those defaults, and `[]` disables redirects. Include Studio in the list if users should return there after sign-in.

Run `bosca swarm render --config ./swarm.local.json` to review the generated stack, then `bosca swarm deploy --config ./swarm.local.json` to apply it. For an explicit list, the CLI generates `config/sites/<site>/server.yaml` and `runner.yaml` with the standard `oauth2.redirects` YAML list, mounts each file as a Swarm config, and starts the application with `-config=/config/application.yaml`. Other settings come from application defaults bundled with the CLI; Atlas also receives the site’s initialization password bindings. Use matching application images. Omitting the list uses the server/runner images’ packaged configuration and retains Atlas’s generated initialization configuration. Server/runner provider callbacks remain at the base domain. Atlas’s Google callback uses the Atlas origin.

## Recommendations and embeddings

Embeddings are enabled: the server and runner use the shared text-embeddings service (EmbeddingGemma through Text Embeddings Inference, as in the Kubernetes deployment). Recommendation training runs on each site's trainer service instead of Kubernetes Jobs. Bosca starts a training run for a model version in the background and polls it, so a long run holds no request open.

The trainer and model loader authenticate with three tokens, as the Kubernetes `recommendation-ml` Secret provides them. After the first deployment, run `bosca swarm setup-tokens --config ./swarm.local.json`. It signs into each site's API as the bootstrap `sa` account, creates the Bosca and scoped artifact push/pull tokens, saves each raw token in the private config, and reapplies the site stacks. The Bosca token carries the service account's broad access; protect the config and service environment accordingly. A retry skips tokens already saved, and it can reapply a stack after an interrupted deployment. Run it before changing the bootstrap `sa` password. Until then, `deploy` prints a notice, and the trainer and loader run but cannot train or load models. Build the trainer image from this workspace: its health check and background runs need the multi-threaded trainer service, and an older single-threaded trainer would fail its health check, and be restarted, while it trains.

When a site's `rootRegistryAuth.username` is `api_token` and its password is blank, the same one-time `setup-tokens` command also creates and saves a token scoped to pull that site's root image repository. Run it after the site API is available and before deploying the private root image. `setup-tokens --skip-public-check` applies the stacks without checking public HTTPS endpoints when unrelated DNS records are still being configured.

## Backups

With `backup.repository` set, the deployment runs two services on the manager. At `backup.hour` (UTC) `backup-dump` writes a `pg_dump` of every site database and the PostgreSQL roles to `<dataRoot>/backups`. Thirty minutes later `backup` takes an encrypted restic snapshot of those dumps, the S3 objects, NATS data, Meilisearch's daily snapshots, Caddy certificates and the rendered configuration, then prunes to `backup.keepDaily`, `keepWeekly` and `keepMonthly` snapshots. It also includes Dragonfly data when any site uses Redis for job queues or counters. A snapshot runs at start-up when the last success is more than a day old. `bosca swarm status` shows the last successful and failed backup. NATS and selected Dragonfly data are copied while those services are running.

To run a backup now, run the scripts on the manager: `sudo docker exec $(sudo docker ps -q --filter label=com.docker.swarm.service.name=infra_backup-dump) /bin/sh /scripts/backup-dump.sh once`, then the same with `infra_backup` and `/scripts/backup.sh once`.

To restore, use restic with the same repository, `secrets.backupPassword` and credentials: `restic snapshots`, then `restic restore latest --target <directory>`. Load each database with `pg_restore --no-owner -d <database> <directory>/data/postgres-dumps/postgres/<database>.dump` after recreating it with the bootstrap, and copy the S3, NATS, any included Dragonfly, and Caddy directories back into the data root with the services stopped. Test a restore before relying on the installation.

## Mail and checks

For Mailgun, create and verify a sending domain for each site in Mailgun and publish the DNS records shown for that domain. In each site's Studio, open **System → Integrations → Mailgun** and save a domain sending API key, the verified sending domain, and the API base URL (`https://api.mailgun.net` for US domains or `https://api.eu.mailgun.net` for EU domains). The credentials are stored in that site's encrypted platform configuration and take effect for new sends without a restart. Use **Choose email provider** to select Mailgun. Keep `mailFromEmail` on the same root or sending domain and check its DMARC alignment.

For SendGrid, configure each site's API key and webhook verification key in its Studio system integrations. Verify each sender domain and point its SendGrid Event Webhook at `https://<domain>/api/v1/webhooks/sendgrid`.

For SMTP, open **System → Integrations → SMTP** and save the host, port, connection security, and optional username/password. Select **None (plain SMTP)** for an unencrypted relay, **STARTTLS** for a required TLS upgrade, or **TLS** for TLS on connection. Leave both credentials empty for an unauthenticated relay. Use **Choose email provider** to select SMTP. Connection settings and provider changes apply to new sends without a restart.

Check login, a Git clone, object upload, search, a queued job, an analytics event, and a test email from the selected provider for each site. Check Trino catalog access and perform a backup restore test before relying on the installation. The Kubernetes controller is disabled, and CI agents are not launched by this installer.
