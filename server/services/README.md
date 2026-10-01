This directory contains the local service Compose stack used by the Bosca server.
From the workspace root, run:

```bash
cd server/services
docker compose up -d
```

Optional services, including the file server, use Compose profiles. The
[server README](../README.md) describes the server and runner tasks.
