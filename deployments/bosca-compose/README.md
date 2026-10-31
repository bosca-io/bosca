# Bosca Compose

A portable installer for a small Bosca Atlas installation. It includes the
API and its background workers, Atlas Git/artifacts server and UI, Studio, BML message templates,
PostgreSQL with pgvector, NATS, and Meilisearch. Recommendations, embeddings, and
analytics processing are disabled. Nginx and Certbot run on the host.

The installer copies the package, generates private credentials, prepares storage
directories, and renders Compose and nginx configuration. Registry login, certificate issuance,
and starting services are explicit steps below. Installation itself starts no
services and changes no host nginx configuration.

## Requirements

- Linux with Docker Engine and Docker Compose v2 installed system-wide. Use the
  [Docker installation instructions](https://docs.docker.com/engine/install/) for your distribution.
  A Compose plugin installed only under `~/.docker/cli-plugins` is not visible through
  this package's separate Docker configuration directory.
- Python 3.9 or newer to run the installer and archive builder; no Python packages are required.
- An amd64 CPU compatible with the selected native images. The default Bosca images
  require AVX2, BMI1/BMI2, FMA, and F16C; use compatible image builds for other CPUs.
- Access to the Bosca image registry and a token that can read the BML message artifacts.
- Four DNS names under a shared domain: `studio.example.com`, `atlas.example.com`,
  `git.example.com` and `artifacts.example.com`.
- Host nginx 1.25.1 or newer with HTTP/2 support and Certbot for public HTTPS.

The default container limits total 2880 MiB; separate services add to that total.
This is a small-server baseline, not a measured
memory guarantee for arbitrary repositories or concurrent pushes. Leave memory for
Docker, nginx, and the operating system, and test your workload before importing it.

## Install

Extract the archive on the intended host, then run:

```sh
tar -xzf bosca-compose-0.1.2.tar.gz
cd bosca-compose-0.1.2
sudo sh install.sh --domain example.com --directory /opt/bosca
```

With a Bosca CLI built from this workspace, the same installer is bundled:

```sh
sudo bosca compose init --domain example.com --directory /opt/bosca
```

The CLI runs Python 3 on the deployment host and uses its Docker Compose installation.
It prepares the same files as `install.sh`. Follow the image and host nginx/HTTPS
steps below before starting containers.

The default hostnames are `studio.<domain>`, `atlas.<domain>`, `git.<domain>` and
`artifacts.<domain>`. Atlas serves Git/artifact administration and the combined
GraphQL API; the Git hostname retains clone/LFS/API URLs. To choose other names:

```sh
sudo sh install.sh --domain example.com \
  --studio-domain admin.example.com --git-domain code.example.com \
  --atlas-domain build.example.com --artifacts-domain packages.example.com \
  --directory /opt/bosca
```

All four names must belong to the shared cookie domain. `--api-port`, `--atlas-port`,
`--atlas-ui-port`, `--git-port`, `--artifacts-port`, `--studio-port`, and `--bml-port`
set the loopback ports; defaults are 8080, 8094, 8095, 8091, 8084, 3000, and 9093.
These port options and `--cert-name` are available through `install.sh`; CLI users
can edit the public settings in `.env` and rerender. Container ports remain fixed. `--cert-name`
defaults to `bosca`.

The destination must be empty for a new installation. Rerunning the same package
against an existing installation preserves its files, credentials, and data.
The installer does not convert an existing hand-managed deployment or overwrite
configuration from a different package version.

`.env` has mode 600 and `.docker` has mode 700. Each installation gets fresh
database, encryption, signing, NATS, search, and bootstrap administrator credentials.
The `PIPELINE_SECRET_KEY` is a Base64-encoded 32-byte key.

## Select Atlas services

The default runs Git, artifacts, and all Atlas UIs in `atlas`. Each option below
is independent, so mixed configurations are supported:

| Installer/CLI init option | Public `.env` setting | Effect |
| --- | --- | --- |
| `--separate-git` | `SEPARATE_GIT=true` | Start the backend-only `git` service and route Git protocol requests to it |
| `--separate-artifacts` | `SEPARATE_ARTIFACTS=true` | Start `artifacts` and route registry requests and UI uploads to it |
| `--separate-ui` | `SEPARATE_UI=true` | Disable embedded UI and start `atlas-ui` |
| `--atlas-runner-enabled` | `ATLAS_RUNNER_ENABLED=true` | Enable Atlas queue consumers; event listeners and the scheduler stay with the API workers |

For example, prepare all three separate services:

```sh
sudo bosca compose init --domain example.com --directory /opt/bosca \
  --separate-git --separate-artifacts --separate-ui
```

`atlas.<domain>` serves all Atlas pages and routes protocols to the selected
backends. Its shared `/graphql` remains on Atlas. `git.<domain>` retains Git
protocol URLs and redirects UI navigation to Atlas; `artifacts.<domain>` retains
native registry URLs. Separate backends share the database, signing/encryption
keys, and storage with Atlas.

To change an existing topology, edit these settings in `.env`, then run
`sudo bosca compose render --directory /opt/bosca` (or
`sudo sh install.sh --directory /opt/bosca --render`). This generates
`compose.yaml` from `compose.yaml.template` and the selected service fragments.
Edit the templates for persistent customizations. Validate the configuration,
start the selected services, then install and reload the generated nginx files
as described below. CLI `up` removes obsolete containers from the same Compose
project with `--remove-orphans`; bind-mounted data remains in `data/`.
Plan a maintenance window when changing topology.

## Configure message artifacts and images

```sh
cd /opt/bosca
sudoedit .env
sudo sh compose.sh config --quiet
sudo sh compose.sh pull
```

Set `ATLAS_VERSION` to a published Atlas image release built from these changes.
Set `BML_MESSAGE_ARTIFACTS_URL` to your Artifacts server and `BML_MESSAGE_ARTIFACTS_TOKEN`
to a token with read access to its `bml-message` raw repository before validating Compose.

Bosca images are public on `ghcr.io/bosca-io/bosca`. To pull from a private mirror
instead, set `BOSCA_IMAGE_REGISTRY` and log in first with
`sudo docker --config /opt/bosca/.docker login <registry host>`, then
`sudo chmod 600 .docker/config.json`.

Image registry and versions are configurable in `.env`:

| Setting | Packaged default |
| --- | --- |
| `BOSCA_IMAGE_REGISTRY` | `ghcr.io/bosca-io/bosca` |
| `SERVER_VERSION` | `6.28.2` |
| `ATLAS_VERSION` | Required Atlas release tag |
| `GIT_VERSION` | Required when `SEPARATE_GIT=true`; use a backend-only Git release |
| `ARTIFACTS_VERSION` | Required when `SEPARATE_ARTIFACTS=true` |
| `ATLAS_UI_VERSION` | Defaults to `ATLAS_VERSION`; used when `SEPARATE_UI=true` |
| `STUDIO_VERSION` | `6.28.4` |
| `BML_MESSAGE_VERSION` | `6.23.0` |
| `POSTGRES_IMAGE` | `pgvector/pgvector:pg18` |
| `NATS_IMAGE` | `nats:2.12.15-alpine` |
| `MEILISEARCH_IMAGE` | `getmeili/meilisearch:v1.39.0` |

PostgreSQL and NATS major versions must remain compatible with the configuration
and existing data. Version overrides use standard
[Compose interpolation](https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/).
Application changes require updated images; rebuilding this configuration archive
does not rebuild Bosca binaries.

## Set up host nginx and HTTPS

Point all four DNS names at the host and allow incoming TCP ports 80 and 443.
The commands below use the default certificate name and Debian/Ubuntu nginx paths.
Replace the domain names and certificate name with the values chosen at installation.
Install nginx and Certbot using your distribution's packages first.

Enable the generated HTTP configuration for Certbot's
[webroot challenge](https://eff-certbot.readthedocs.io/en/stable/using.html#webroot):

```sh
cd /opt/bosca
sudo install -d /var/www/letsencrypt /etc/nginx/sites-available /etc/nginx/sites-enabled /etc/nginx/snippets
sudo install -m 644 config/nginx-bootstrap.conf /etc/nginx/sites-available/bosca
sudo ln -sfn /etc/nginx/sites-available/bosca /etc/nginx/sites-enabled/bosca
sudo nginx -t
sudo systemctl enable --now nginx
sudo systemctl reload nginx
sudo certbot certonly --webroot -w /var/www/letsencrypt \
  --cert-name bosca -d studio.example.com -d git.example.com \
  -d atlas.example.com -d artifacts.example.com \
  --email admin@example.com --agree-tos --non-interactive
```

Then enable HTTPS and the renewal hook:

```sh
sudo install -m 644 config/nginx-proxy.conf /etc/nginx/snippets/bosca-proxy.conf
sudo install -m 644 config/nginx.conf /etc/nginx/sites-available/bosca
sudo nginx -t
sudo systemctl reload nginx
sudo install -d /etc/letsencrypt/renewal-hooks/deploy
sudo install -m 755 config/certbot-deploy.sh /etc/letsencrypt/renewal-hooks/deploy/bosca
sudo systemctl enable --now certbot.timer
sudo certbot renew --dry-run --run-deploy-hooks
```

Nginx routes Atlas UI pages/actions/assets to 8095 and its shared API to 8094.
Git protocols use 8091 with separate Git, otherwise 8094; registry protocols use
8084 with separate artifacts, otherwise 8094.
Git transport URLs remain on the Git hostname; Git UI pages redirect to Atlas.
The artifacts hostname serves native registry URLs, rewriting file protocols into
Atlas's `/_artifacts` namespace when artifacts are embedded, to avoid Git path conflicts. Studio has its own
hostname. Only message assets under `/bml-messages/assets/` are public; rendering
and administration remain internal. WebSockets and streaming requests use the
shared proxy settings. Request bodies are limited to 1 GiB there.

To change hostnames or loopback ports later, edit the public settings in `.env`
and rerender the deployment files with `sudo sh install.sh --directory /opt/bosca --render`.
URLs in `.env` derive from `STUDIO_DOMAIN`, `ATLAS_DOMAIN`, `GIT_DOMAIN` and `ARTIFACTS_DOMAIN`. Obtain a certificate for
the new names, install the rendered files, validate nginx, and restart the affected
containers. Edit `.template` files when customizing generated nginx configuration.

## Start and verify

```sh
cd /opt/bosca
sudo sh compose.sh config --quiet
sudo sh compose.sh up -d --wait
sudo sh compose.sh ps
sudo sh compose.sh logs --tail=100 server atlas bml-message-server
sudo sh compose.sh stats --no-stream
```

The CLI provides the corresponding lifecycle commands:

```sh
sudo bosca compose config --directory /opt/bosca
sudo bosca compose up --directory /opt/bosca
sudo bosca compose status --directory /opt/bosca
sudo bosca compose down --directory /opt/bosca
```

`up` and `config` rerender from `.env` before invoking Compose. `status` and `down`
use the existing generated files without rerunning the installer.
`config` validates
without printing resolved credentials. `up` waits for health checks, with a
300-second timeout. `down` retains the bind-mounted data. These commands do not
install host nginx files, issue certificates, or reload nginx.

Open `https://studio.example.com`. The initial administrator username is `admin`;
its password is `INIT_ADMIN_PASSWORD` in the private `.env`. Clone URLs use the Git
hostname. Verify a scratch repository push and clone, including LFS if required,
and measure memory under your expected workload.

Open `https://atlas.example.com` for Atlas. With embedded UI, one process serves
both listeners and its health check requires readiness from both. With separate
UI, each process checks its own listener. Atlas's auth return origin is
`ATLAS_URL`; Studio continues to use `APP_URL`, and clone URLs retain `GIT_URL`.
For Google login, set its client credentials in `.env` and register
`https://atlas.example.com/oauth2/google/callback` with the provider.

The application configuration is in `config/server.yaml`, `config/atlas.yaml`,
`config/git.yaml`, and `config/artifacts.yaml`.
Configure a mail provider before enabling email workflows. Image processing, CI
agents, recommendations, model services, and analytics infrastructure are not included.
The existing Git/WorkOps integration and required GraphQL schemas remain enabled.

API.Bible delivery jobs run in the API process through `fumsQueueRunner`. When
updating an existing installation's configuration, include `fumsQueueRunner` in
`config/server.yaml` under `runners.enabled`.

FUMS event collection belongs to the Analytics collector, which is not included
in this package.

| Service | Container limit | Heap or indexing limit |
| --- | ---: | ---: |
| API and workers | 768 MiB | 512 MiB native heap |
| Atlas | 896 MiB | 640 MiB native heap |
| Optional Git backend | 896 MiB | 640 MiB native heap |
| Optional artifacts backend | 896 MiB | 640 MiB native heap |
| Optional Atlas UI | 512 MiB | Native runtime default |
| PostgreSQL | 320 MiB | 64 MiB shared buffers |
| Meilisearch | 256 MiB | 64 MB indexing, one thread |
| Studio | 256 MiB | 160 MiB Node old space |
| BML message server | 256 MiB | 128 MiB native heap |
| NATS | 128 MiB | 64 MB JetStream memory store |

## Data, updates, and removal

Persistent state lives under `data/`. API and Atlas share `data/storage`; PostgreSQL
uses `data/postgres`. Preserve `.env`, registry credentials, and all data together.
For an offline backup, stop the stack before copying the installation directory so
repository metadata and stored objects are consistent. `sh compose.sh down` retains
the bind-mounted files.

The generated PostgreSQL connection limit is 40 with combined Atlas, plus ten
for each separate Git or artifacts backend. This covers the configured pools
while retaining ten slots for maintenance and startup operations.

For an image update, edit the relevant version in `.env`, pull that service, then
run `sh compose.sh up -d --wait SERVICE`. Keep a backup and review the release's
migration requirements first. To update this package's configuration, unpack the
new archive separately, compare its files with the installation, and apply the
desired changes while preserving `.env` and `data/`; the installer does not perform
automatic upgrades. Stop with `sh compose.sh down` before removing an installation.

When upgrading an existing Git-only package, keep the existing `.env`, pipeline
key and data. Compare the new `compose.yaml.template`, service fragments,
`config/atlas.yaml`, installer, package manifest and
nginx templates from a separate unpacked archive. Add `ATLAS_DOMAIN`,
`ARTIFACTS_DOMAIN`, `ATLAS_URL`, `ARTIFACTS_URL`, `ATLAS_PORT=8094`,
`ATLAS_UI_PORT=8095` and `ATLAS_VERSION` to the private `.env`. Obtain a certificate
covering the new domains and rerender with `--render`. Select separate Git and
artifacts if retaining those services; otherwise Atlas replaces them. Stop old
services using the saved old Compose configuration after the replacement and
ingress are healthy. Preserve the shared
storage mount and credentials. The installer does not upgrade files automatically.

## Build the distributable

From `deployments/bosca-compose` in the workspace source checkout:

```sh
python3 -m unittest discover -s tests -v
sh package.sh
```

The builder writes `dist/bosca-compose-0.1.2.tar.gz` and a matching `.sha256` file.
`VERSION` is the installer package version, independent of application image tags.
The archive is reproducible and includes only the public files listed in
`package-files.txt`. The CLI bundles that same list. It excludes `.env`, `.docker`,
`data`, generated Compose and nginx files,
installation metadata, test files, and output archives.
