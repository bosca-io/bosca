#!/bin/sh
# Install the PostgreSQL 18 clients used by native backup and restore operations.
set -eu

. /etc/os-release
case "$ID" in
    debian|ubuntu) ;;
    *) echo "Unsupported PostgreSQL client runtime distribution: $ID" >&2; exit 1 ;;
esac
: "${VERSION_CODENAME:?The runtime distribution must declare VERSION_CODENAME}"

apt-get update
apt-get install -y --no-install-recommends ca-certificates curl
install -d /usr/share/postgresql-common/pgdg
curl --fail --silent --show-error --location \
    https://www.postgresql.org/media/keys/ACCC4CF8.asc \
    --output /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc
printf 'deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] https://apt.postgresql.org/pub/repos/apt %s-pgdg main\n' \
    "$VERSION_CODENAME" > /etc/apt/sources.list.d/pgdg.list
apt-get update
apt-get install -y --no-install-recommends postgresql-client-18
rm -rf /var/lib/apt/lists/*
