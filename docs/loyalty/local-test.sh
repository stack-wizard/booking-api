#!/bin/sh
# Lokalni test loyalty faze 1 protiv booking-api na :8082 (scratch baza).
# TOKEN = access token prijavljenog korisnika (BOOKING_ADMIN), TENANT = platform tenant UUID lanca (X-Tenant-Id).
# Koraci: setup (program + pravila + kanali) | members | reservations <propertyTenantId> | eval-status
set -e
API=${API:-http://localhost:8082}
DIR=$(cd "$(dirname "$0")" && pwd)
H_AUTH="Authorization: Bearer $TOKEN"
H_TEN="X-Tenant-Id: $TENANT"
j() { curl -sf -H "$H_AUTH" -H "$H_TEN" -H 'Content-Type: application/json' "$@"; }
pid() { j "$API/api/loyalty/programs" | python3 -c 'import sys,json; p=[x for x in json.load(sys.stdin) if x["code"]=="ARENA_REWARDS"]; print(p[0]["id"] if p else "")'; }

import_file() { # type file [propertyTenantId]
  P=$(pid); PROP=${3:-null}
  R=$(j -X POST "$API/api/loyalty/imports" -d "{\"programId\":$P,\"importType\":\"$1\",\"propertyTenantId\":$PROP,\"fileName\":\"$(basename "$2")\"}")
  ID=$(echo "$R" | python3 -c 'import sys,json; print(json.load(sys.stdin)["batch"]["id"])')
  URL=$(echo "$R" | python3 -c 'import sys,json; print(json.load(sys.stdin)["uploadUrl"])')
  curl -sf -X PUT -H 'Content-Type: text/csv' --data-binary @"$2" "$URL" >/dev/null
  OPTS='{"options":{"asOf":"2026-10-01"}}'
  j -X POST "$API/api/loyalty/imports/$ID/start" -d "$OPTS" >/dev/null
  wait_status "$ID" VALIDATED
  j "$API/api/loyalty/imports/$ID" | python3 -c 'import sys,json; b=json.load(sys.stdin); print("batch",b["id"],b["status"],"total",b["rowsTotal"],"valid",b["rowsValid"],"invalid",b["rowsInvalid"])'
  echo "--- odbijeni redovi:"; curl -sf -H "$H_AUTH" -H "$H_TEN" "$API/api/loyalty/imports/$ID/errors.csv"; echo
  j -X POST "$API/api/loyalty/imports/$ID/apply" >/dev/null
  wait_status "$ID" DONE
  j "$API/api/loyalty/imports/$ID" | python3 -c 'import sys,json; b=json.load(sys.stdin); print("batch",b["id"],b["status"],"applied",b["rowsApplied"])'
}
wait_status() {
  for i in $(seq 1 60); do
    S=$(j "$API/api/loyalty/imports/$1" | python3 -c 'import sys,json; print(json.load(sys.stdin)["status"])')
    [ "$S" = "$2" ] && return 0
    [ "$S" = "FAILED" ] && { j "$API/api/loyalty/imports/$1"; echo; exit 1; }
    sleep 2
  done
  echo "timeout ($S)"; exit 1
}

case "$1" in
  setup)
    P=$(pid)
    if [ -z "$P" ]; then
      P=$(j -X POST "$API/api/loyalty/programs" -d '{"code":"ARENA_REWARDS","name":"Arena Rewards","accrualUnit":"NIGHT","anonymousPricing":"APPLY_ENTRY_TIER_ON_JOIN"}' | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')
    fi
    RS=$(j -X POST "$API/api/loyalty/programs/$P/rule-sets" -d '{"effectiveFrom":"2019-01-01","tierPolicy":"ROLLING_VALIDITY","note":"Arena Rewards 2026","params":{"validity_months":24,"wallet_bonus_nights":2,"claim_window_days":60,"near_next_threshold":3,"unknown_code_policy":"NON_QUALIFYING"}}' | python3 -c 'import sys,json; print(json.load(sys.stdin)["ruleSet"]["id"])')
    j -X PUT "$API/api/loyalty/rule-sets/$RS" -d @"$DIR/samples/arena-rules.json" >/dev/null
    j -X POST "$API/api/loyalty/rule-sets/$RS/activate" >/dev/null
    j -X PUT "$API/api/loyalty/programs/$P/channel-map" -d '[{"codeType":"SOURCE","code":"WEB","qualifying":true},{"codeType":"SOURCE","code":"CC","qualifying":true},{"codeType":"SOURCE","code":"FD","qualifying":true},{"codeType":"SOURCE","code":"BOOKING","qualifying":false}]' >/dev/null
    echo "program $P, rule set $RS aktivan" ;;
  members) import_file MEMBERS "$DIR/samples/members.csv" ;;
  reservations) import_file RESERVATIONS "$DIR/samples/reservations-dh.csv" "$2" ;;
  *) echo "usage: $0 setup|members|reservations <propertyTenantId>"; exit 1 ;;
esac
