#!/usr/bin/env bash
#
# Live-test Signzy's Contract eSign APIs — the Aadhaar eSign (eMudhra) of the sanction letter.
#
#   Auth:    header  Authorization: <raw token>   (NOT "Bearer")
#            x-client-unique-id is NOT required by these two endpoints (unlike the rest of Signzy),
#            but it is sent anyway when SIGNZY_UID is set, which is harmless.
#   Base:    PRODUCTION ONLY — https://api.signzy.app
#            Preproduction answers 403 {"message":"You cannot consume this service"}: the contract
#            product is not entitled there. Verified 2026-08-11.
#
# Usage:
#   SIGNZY_TOKEN=<prod-token> ./test-contract-esign.sh pull <contractId>
#   SIGNZY_TOKEN=<prod-token> ./test-contract-esign.sh initiate <pdfUrl> [signerName]
#
# ⚠️  `initiate` MINTS A REAL, BILLABLE, LEGALLY BINDING Aadhaar eSign contract. There is no sandbox.
#     Prefer `pull` against an existing contract id for a smoke test — it costs nothing.
#     A known-good contract to pull:  8160860a-32eb-4114-83ee-b0346c98c63f
#
# Fetch the production token with:
#   MSYS_NO_PATHCONV=1 aws ssm get-parameter --name "/navix/dev/navix/signzy/prod-token" \
#     --with-decryption --profile navix-dev --region ap-south-1 --query Parameter.Value --output text
set -euo pipefail

TOKEN="${SIGNZY_TOKEN:-}"
UID_HDR="${SIGNZY_UID:-}"
BASE="${SIGNZY_BASE_URL:-https://api.signzy.app}"
[[ -z "$TOKEN" ]] && { echo "error: SIGNZY_TOKEN not set (must be the PRODUCTION token)" >&2; exit 2; }

API="${1:-}"
shift || true

call() {
  local endpoint="$1" body="$2"
  local -a headers=(-H "Authorization: $TOKEN" -H "Content-Type: application/json")
  [[ -n "$UID_HDR" ]] && headers+=(-H "x-client-unique-id: $UID_HDR")
  echo "POST ${BASE}${endpoint}" >&2
  # The body can carry a base64 PDF, which blows the argv limit — pass it via a temp file.
  local tmp
  tmp="$(mktemp)"
  printf '%s' "$body" >"$tmp"
  curl -sS --http1.1 --location -w '\nHTTP %{http_code}\n' \
    "${headers[@]}" --data "@$tmp" "${BASE}${endpoint}"
  rm -f "$tmp"
}

case "$API" in
  pull)
    CONTRACT_ID="${1:?usage: pull <contractId>}"
    call /api/v3/contract/pullData "{\"contractId\":\"$CONTRACT_ID\"}"
    ;;

  initiate)
    PDF="${1:?usage: initiate <pdfUrl> [signerName]}"
    SIGNER="${2:-Test Borrower}"
    read -r -p "This mints a REAL billable eSign contract. Type 'yes' to continue: " confirm
    [[ "$confirm" == "yes" ]] || { echo "aborted" >&2; exit 1; }
    call /api/v3/contract/initiate "$(cat <<JSON
{
  "pdf": "$PDF",
  "contractName": "DhanBoost Loan Agreement — live test",
  "contractExecuterName": "NAVIX Finance Private Limited",
  "successRedirectUrl": "https://dhanboost.com/kyc/esign/callback?outcome=success",
  "failureRedirectUrl": "https://dhanboost.com/kyc/esign/callback?outcome=failure",
  "callbackUrl": "https://dhanboost.com/api/webhooks/signzy/contract",
  "eSignProvider": "EMUDHRA",
  "signerdetail": [
    {
      "signerName": "$SIGNER",
      "signatureType": "AADHAARESIGN-OTP",
      "signatures": [{ "pageNo": ["All"], "signaturePosition": ["BottomLeft"] }]
    }
  ]
}
JSON
)"
    ;;

  *)
    sed -n '2,22p' "$0"
    exit 2
    ;;
esac
