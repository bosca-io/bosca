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
docker compose up
```

Once the services are ready, open [Studio](http://bosca.localhost:3000) and sign in
with username `admin` and password `password`. No `.env` file is required.
See the [getting started guide](https://bosca.io/developers/run-locally) for configuration,
data persistence, and troubleshooting.

To refresh the default Bosca image versions from the public registry (requires `curl` and `jq`):

```bash
scripts/update-compose-versions.sh
docker compose up -d
```

Use `--dry-run` to preview the version changes. The script updates the Compose
defaults and `.env.example`; overrides in your own `.env` still take precedence.

## License

Bosca is licensed under the [Apache License 2.0](LICENSE), except for:

- `bosca-yks`, licensed under the [MIT License](bosca-yks/LICENSE).

Bundled third-party material and its licenses are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
