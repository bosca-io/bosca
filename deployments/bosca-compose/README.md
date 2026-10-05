# Bosca Compose

A portable installer for a small, Git-focused Bosca installation. It includes the
API and its background workers, Git server, Studio, BML message templates,
PostgreSQL with pgvector, NATS, and Meilisearch. Recommendations, embeddings, and
analytics processing are disabled. Nginx and Certbot run on the host.

The installer copies the package, generates private credentials, prepares storage
directories, and renders nginx configuration. Registry login, certificate issuance,
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
- Two DNS names under a shared domain, such as `studio.example.com` and `git.example.com`.
- Host nginx 1.25.1 or newer with HTTP/2 support and Certbot for public HTTPS.

The container limits total 2880 MiB. This is a small-server baseline, not a measured
memory guarantee for arbitrary repositories or concurrent pushes. Leave memory for
Docker, nginx, and the operating system, and test your workload before importing it.

## Install

Extract the archive on the intended host, then run:

```sh
tar -xzf bosca-compose-0.1.0.tar.gz
cd bosca-compose-0.1.0
sudo sh install.sh --domain example.com --directory /opt/bosca
```

The default hostnames are `studio.<domain>` and `git.<domain>`. To choose different names:

```sh
sudo sh install.sh --domain example.com \
  --studio-domain admin.example.com --git-domain code.example.com \
  --directory /opt/bosca
```

Both names must belong to the shared cookie domain. `--api-port`, `--git-port`,
`--studio-port`, and `--bml-port` set the loopback ports; defaults are 8080, 8091,
3000, and 9093. Container ports remain fixed. `--cert-name` defaults to `bosca`.

The destination must be empty for a new installation. Rerunning the same package
against an existing installation preserves its files, credentials, and data.
The installer does not convert an existing hand-managed deployment or overwrite
configuration from a different package version.

`.env` has mode 600 and `.docker` has mode 700. Each installation gets fresh
database, encryption, signing, NATS, search, and bootstrap administrator credentials.
The `PIPELINE_SECRET_KEY` is a Base64-encoded 32-byte key.

## Configure message artifacts and images

```sh
cd /opt/bosca
sudoedit .env
sudo sh compose.sh config --quiet
sudo sh compose.sh pull
```

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
| `GIT_VERSION` | `6.27.7` |
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

Point both DNS names at the host and allow incoming TCP ports 80 and 443.
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

Nginx routes Studio and Git to loopback ports. Only BML's static assets under
`/bml-messages/assets/` are public; its rendering and administration endpoints remain
internal. WebSocket forwarding and streaming Git requests are configured. Request
bodies are limited to 1 GiB in `config/nginx-proxy.conf`.

To change hostnames or loopback ports later, edit the public settings in `.env`
and rerender the nginx files with `sudo sh install.sh --directory /opt/bosca --render-nginx`.
URLs in `.env` derive from `STUDIO_DOMAIN` and `GIT_DOMAIN`. Obtain a certificate for
the new names, install the rendered files, validate nginx, and restart the affected
containers. Edit `.template` files when customizing generated nginx configuration.

## Start and verify

```sh
cd /opt/bosca
sudo sh compose.sh config --quiet
sudo sh compose.sh up -d --wait
sudo sh compose.sh ps
sudo sh compose.sh logs --tail=100 server git bml-message-server
sudo sh compose.sh stats --no-stream
```

Open `https://studio.example.com`. The initial administrator username is `admin`;
its password is `INIT_ADMIN_PASSWORD` in the private `.env`. Clone URLs use the Git
hostname. Verify a scratch repository push and clone, including LFS if required,
and measure memory under your expected workload.

The application configuration is in `config/server.yaml` and `config/git.yaml`.
Configure a mail provider before enabling email workflows. Image processing, CI
agents, recommendations, model services, and analytics infrastructure are not included.
The existing Git/WorkOps integration and required GraphQL schemas remain enabled.

| Service | Container limit | Heap or indexing limit |
| --- | ---: | ---: |
| API and workers | 768 MiB | 512 MiB native heap |
| Git | 896 MiB | 640 MiB native heap |
| PostgreSQL | 320 MiB | 64 MiB shared buffers |
| Meilisearch | 256 MiB | 64 MB indexing, one thread |
| Studio | 256 MiB | 160 MiB Node old space |
| BML message server | 256 MiB | 128 MiB native heap |
| NATS | 128 MiB | 64 MB JetStream memory store |

## Data, updates, and removal

Persistent state lives under `data/`. API and Git share `data/storage`; PostgreSQL
uses `data/postgres`. Preserve `.env`, registry credentials, and all data together.
For an offline backup, stop the stack before copying the installation directory so
repository metadata and stored objects are consistent. `sh compose.sh down` retains
the bind-mounted files.

For an image update, edit the relevant version in `.env`, pull that service, then
run `sh compose.sh up -d --wait SERVICE`. Keep a backup and review the release's
migration requirements first. To update this package's configuration, unpack the
new archive separately, compare its files with the installation, and apply the
desired changes while preserving `.env` and `data/`; the installer does not perform
automatic upgrades. Stop with `sh compose.sh down` before removing an installation.

## Build the distributable

From `deployments/bosca-compose` in the workspace source checkout:

```sh
python3 -m unittest discover -s tests -v
sh package.sh
```

The builder writes `dist/bosca-compose-0.1.0.tar.gz` and a matching `.sha256` file.
`VERSION` is the installer package version, independent of application image tags.
The archive is reproducible and includes only the public files listed in
`installer.py`. It excludes `.env`, `.docker`, `data`, generated nginx files,
installation metadata, test files, and output archives.
