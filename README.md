[![Bosca — Content, audience, and delivery. One platform.](docs/images/bosca-hero.png)](https://bosca.io)

# Bosca

Content, audience, and delivery. One platform.

Bosca unifies your content, your work, and your audience — then puts intelligence to work on all of it: analytics across the whole platform, built-in AI agents, recommendations that learn what each person cares about, and experiments that
prove what works.

Learn more at [bosca.io](https://bosca.io)

NOTE: CLI tool will be published soon.

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