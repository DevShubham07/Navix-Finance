#!/usr/bin/env bash
#
# Live-test the Signzy "PAN 206AB Compliance (Individual Search)" API.
#
#   POST {BASE}/api/v3/pan/compliance-206-individual-search
#   body: {"panNumber": "<PAN>"}
#   auth: header  Authorization: <raw opaque token>   (NOT "Bearer <t>")
#
# Usage:
#   SIGNZY_TOKEN=<token> ./test-pan-206ab.sh <PAN> [prod|uat]
#   ./test-pan-206ab.sh ABCDE1234F                 # token from $SIGNZY_TOKEN, env=prod
#   ./test-pan-206ab.sh ABCDE1234F uat
#
# See docs/signzy/signzy-apis.json (api[1]) and SignzyGuide.md.
set -euo pipefail

PAN="${1:-}"
ENVIRON="${2:-prod}"
TOKEN="${SIGNZY_TOKEN:-}"

if [[ -z "$PAN" ]]; then
  echo "usage: SIGNZY_TOKEN=<token> $0 <PAN> [prod|uat]" >&2
  exit 2
fi
if [[ -z "$TOKEN" ]]; then
  echo "error: SIGNZY_TOKEN is not set (raw Signzy opaque token, not 'Bearer ...')" >&2
  exit 2
fi

case "$ENVIRON" in
  prod) BASE="https://api.signzy.app" ;;
  uat)  BASE="https://api-preproduction.signzy.app" ;;
  *) echo "error: env must be 'prod' or 'uat' (got '$ENVIRON')" >&2; exit 2 ;;
esac

URL="$BASE/api/v3/pan/compliance-206-individual-search"
BODY=$(printf '{"panNumber":"%s"}' "$PAN")

echo "POST $URL"
echo "body: $BODY"
echo "---"

# -s silent, -S show errors, -w prints the HTTP status on its own trailing line
RESP=$(curl -sS -w $'\n%{http_code}' \
  --location "$URL" \
  --header "Authorization: $TOKEN" \
  --header "Content-Type: application/json" \
  --data "$BODY")

STATUS=$(printf '%s' "$RESP" | tail -n1)
JSON=$(printf '%s' "$RESP" | sed '$d')

echo "HTTP $STATUS"
if command -v jq >/dev/null 2>&1; then
  printf '%s' "$JSON" | jq . 2>/dev/null || printf '%s\n' "$JSON"
else
  printf '%s\n' "$JSON"
fi

[[ "$STATUS" == 2* ]]
