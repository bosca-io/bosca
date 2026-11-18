[![Bosca — Content, audience, and delivery. One platform.](docs/images/bosca-hero.png)](https://bosca.io)

# Bosca

Content, audience, and delivery. One platform.

Bosca unifies your content, your work, and your audience — then puts intelligence to work on all of it: analytics across the whole platform, built-in AI agents, recommendations that learn what each person cares about, and experiments that
prove what works.

Learn more at [bosca.io](https://bosca.io)

## Install the CLI

Install the latest Bosca CLI release on macOS (Apple Silicon) or Linux (x86_64):

```bash
curl -fsSL https://bosca.io/cli/install.sh | sh
bosca --version
bosca --help
```

The installer downloads the package for your platform and checks it against the
release's `SHA256SUMS` using `shasum` or `sha256sum`. macOS uses a signed, notarized
package; Linux uses a tarball. Both install to `/usr/local/bin` by default and may
prompt for your password through `sudo`. The installed binary does not require Java.

On Linux, you can choose a directory you own:

```bash
curl -fsSL https://bosca.io/cli/install.sh | BOSCA_INSTALL_DIR="$HOME/.local/bin" sh
export PATH="$HOME/.local/bin:$PATH"
```

Add that `PATH` setting to your shell configuration to keep it for future sessions.

Check public GitHub releases for updates with `bosca version --check`. To upgrade,
rerun the installation command above, or use the command printed by the update
check. Interactive commands also check for updates at most once a day and print a
notice when a newer release is available. Set `BOSCA_NO_UPDATE_CHECK=1` to disable
automatic checks; explicit `bosca version --check` requests still run.

See the [CLI README](cli/README.md) for installation overrides, server login,
profiles, and commands.

## Run Bosca locally

The root Docker Compose stack runs Studio, the API, background jobs, content storage,
search, Git hosting, artifacts, and analytics. From the repository root:

```bash
: "${BOSCA_ATLAS_VERSION:?Set a published image release containing Atlas}"
export BOSCA_ATLAS_VERSION
docker compose up
```

Once the services are ready, open [Studio](http://bosca.localhost:3000) and sign in
with username `admin` and password `password`. No `.env` file is required.
See the [getting started guide](https://bosca.io/developers/run-locally) for configuration,
data persistence, and troubleshooting.

For a portable single-host deployment with combined Atlas or separate Git,
artifacts, and UI services, use `bosca compose init`; see the
[Compose deployment guide](deployments/bosca-compose/README.md). The root stack
keeps separate Git and artifacts services for local development.

The root stack exposes Atlas on `http://bosca.localhost:8094` (override
`BOSCA_ATLAS_PORT`). Its UI/API share that proxy port; Git remains available on
8091 and the separate registry on 8084. Set `BOSCA_ATLAS_VERSION` to a published
Atlas image release before rendering or starting the stack.
Use `BOSCA_ATLAS_IMAGE_REGISTRY` when pulling Atlas from a mirror; its default is
`ghcr.io/bosca-io/bosca`, independent of the other local images' registry setting.

To refresh the default Bosca image versions from the public registry (requires `curl` and `jq`):

```bash
scripts/update-compose-versions.sh
docker compose up -d
```

Use `--dry-run` to preview the version changes. The script updates the Compose
defaults and `.env.example`; overrides in your own `.env` still take precedence.

The `dragonfly` service runs Dragonfly `v2.0.2` in standalone mode, using its
own `dragonfly` volume. Clients use the `dragonfly` endpoint. It writes `dump.rdb`
every minute and on clean shutdown; crash recovery uses the last completed
snapshot.

When switching a local stack from Redis with disposable cache and lock data,
restart the clients together so they all use the new lock store:

```bash
docker compose stop
docker compose up -d --remove-orphans
```

Dragonfly starts with an empty volume; no data import is needed. The old Redis
container is removed, and its volume is left untouched.

## License

Bosca is licensed under the [Apache License 2.0](LICENSE), except for:

- `bosca-yks`, licensed under the [MIT License](bosca-yks/LICENSE).

Bundled third-party material and its licenses are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
