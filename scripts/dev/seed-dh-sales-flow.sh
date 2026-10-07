#!/usr/bin/env bash
# Manual / local Docker fallback for the DH demo seed.
# On AWS (and normally everywhere) prefer the Spring runner:
#   DhSalesFlowSeedRunner on profile dev|local — same datasource as application-*.yaml / SSM.
# This script is NOT called by Flyway or deploy. Missing DH → exit 0.
#
# Usage (from repo root, with local Postgres container running):
#   bash scripts/dev/seed-dh-sales-flow.sh
# Optional:
#   PGCONTAINER=booking-postgres PGUSER=booking_user PGDATABASE=booking_db bash scripts/dev/seed-dh-sales-flow.sh

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CONTAINER="${PGCONTAINER:-booking-postgres}"
PGUSER="${PGUSER:-booking_user}"
PGDATABASE="${PGDATABASE:-booking_db}"

psql_q() {
  docker exec -i "$CONTAINER" psql -U "$PGUSER" -d "$PGDATABASE" -v ON_ERROR_STOP=1 "$@"
}

if ! docker inspect "$CONTAINER" >/dev/null 2>&1; then
  echo "Postgres container '$CONTAINER' is not running — skipping DH seed (ok)."
  exit 0
fi

PLATFORM_TENANT="${PLATFORM_TENANT_ID:-11111111-1111-1111-1111-111111111111}"
TENANT="$(psql_q -tAc "select tenant_id from platform_tenant_mapping where platform_tenant_id = '${PLATFORM_TENANT}'::uuid limit 1" | tr -d '[:space:]')"

if [[ -z "$TENANT" ]]; then
  echo "Platform tenant ${PLATFORM_TENANT} is not mapped — skipping (log into admin once, then re-run)."
  exit 0
fi

echo "Seeding demo sales flow for platform=${PLATFORM_TENANT} → tenant_id=$TENANT"

# Catalog (spaces, packages, teams) — idempotent; same script used for the CRM demo tenant.
psql_q -v tenant="$TENANT" < "$ROOT/scripts/dev/seed-events.sql" >/dev/null

# One end-to-end won deal story for that tenant.
psql_q -v tenant="$TENANT" < "$ROOT/scripts/dev/seed-dh-sales-flow.sql"

echo "DH sales-flow seed done."
