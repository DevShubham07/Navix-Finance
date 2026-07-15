#!/usr/bin/env bash
#
# Live-test the Signzy verification APIs NAVIX integrates.
#
#   Auth:    header  Authorization: <raw token>   (NOT "Bearer")
#            header  x-client-unique-id: <your account unique id>
#   Base:    prod  https://api.signzy.app   |   preprod  https://api-preproduction.signzy.app
#
# Usage:
#   SIGNZY_TOKEN=<tok> SIGNZY_UID=<uid> ./test-all-signzy.sh <api> [args...] [prod|uat]
#
#   ./test-all-signzy.sh digilocker-create                 # zero input
#   ./test-all-signzy.sh liveness-create                   # zero input
#   ./test-all-signzy.sh pan ABCPE1234Z
#   ./test-all-signzy.sh bank <account> <ifsc> [name]
#   ./test-all-signzy.sh experian <phone> <firstName> <lastName> [pan]
#   ./test-all-signzy.sh crif <phone> <pan> <first> <last> <dob yyyy-mm-dd> <gender> <address> <pincode>
#   ./test-all-signzy.sh eaadhaar <requestId>              # from a completed consent
#   ./test-all-signzy.sh liveness-data <token>             # from a completed session
#
# Append `uat` (or `prod`) as the LAST arg to pick the environment (default: uat/preproduction).
set -euo pipefail

TOKEN="${SIGNZY_TOKEN:-}"
UID_HDR="${SIGNZY_UID:-}"
[[ -z "$TOKEN" ]] && { echo "error: SIGNZY_TOKEN not set" >&2; exit 2; }

# Trailing env selector
ENVIRON="uat"
args=("$@")
if [[ ${#args[@]} -gt 0 ]]; then
  last="${args[${#args[@]}-1]}"
  if [[ "$last" == "prod" || "$last" == "uat" ]]; then
    ENVIRON="$last"; unset 'args[${#args[@]}-1]'
  fi
fi
case "$ENVIRON" in
  prod) BASE="https://api.signzy.app" ;;
  uat)  BASE="https://api-preproduction.signzy.app" ;;
esac

API="${args[0]:-}"; rest=("${args[@]:1}")
ts="$(date +%s)000"

call() { # path json
  local path="$1" body="$2"
  echo "POST $BASE$path"
  echo "body: $body"
  echo "---"
  local resp status json
  resp=$(curl -sS -w $'\n%{http_code}' --location "$BASE$path" \
    --header "Authorization: $TOKEN" \
    --header "x-client-unique-id: $UID_HDR" \
    --header "Content-Type: application/json" \
    --data "$body")
  status=$(printf '%s' "$resp" | tail -n1)
  json=$(printf '%s' "$resp" | sed '$d')
  echo "HTTP $status"
  if command -v jq >/dev/null 2>&1; then printf '%s' "$json" | jq . 2>/dev/null || printf '%s\n' "$json"
  else printf '%s\n' "$json"; fi
}

case "$API" in
  digilocker-create)
    call "/api/v3/digilocker/createUrl" \
      '{"signup":true,"callbackUrl":"https://navixfinance.com/kyc/callback","docType":["ADHAR"],"purpose":"kyc","getScope":true,"getEAadhaarPdf":true,"getEAadhaarJpeg":true}'
    ;;
  liveness-create)
    call "/api/v3/liveness-secure/createUrl" \
      '{"languageCode":"en","faceMatchThreshold":0.6}'
    ;;
  pan)
    call "/api/v3/pan/compliance-206-individual-search" \
      "$(printf '{"panNumber":"%s"}' "${rest[0]}")"
    ;;
  bank)
    call "/api/v3/bankaccountverification/bankaccountverifications" \
      "$(printf '{"beneficiaryAccount":"%s","beneficiaryIFSC":"%s","beneficiaryName":"%s","nameFuzzy":"true"}' "${rest[0]}" "${rest[1]}" "${rest[2]:-}")"
    ;;
  experian)
    call "/api/v3/bureau/experian-lite" \
      "$(printf '{"phoneNumber":"%s","firstName":"%s","lastName":"%s","pan":"%s","consent":{"consentFlag":true,"consentTimestamp":%s,"consentIpAddress":"0.0.0.0","consentMessageId":"CM_1"}}' "${rest[0]}" "${rest[1]}" "${rest[2]}" "${rest[3]:-}" "$ts")"
    ;;
  crif)
    call "/api/v3/bureau/crif" \
      "$(printf '{"phoneNumber":"%s","pan":"%s","firstName":"%s","lastName":"%s","dateOfBirth":"%s","gender":"%s","address":"%s","pincode":"%s","consent":{"consentFlag":true,"consentTimestamp":%s,"consentIpAddress":"0.0.0.0","consentMessageId":"CM_1"}}' "${rest[0]}" "${rest[1]}" "${rest[2]}" "${rest[3]}" "${rest[4]}" "${rest[5]}" "${rest[6]}" "${rest[7]}" "$ts")"
    ;;
  eaadhaar)
    call "/api/v3/digilocker/geteaadhaarwithxml" \
      "$(printf '{"requestId":"%s","getEAadhaarPdf":true,"getEAadhaarJpeg":true}' "${rest[0]}")"
    ;;
  liveness-data)
    call "/api/v3/liveness-secure/getData" \
      "$(printf '{"token":"%s"}' "${rest[0]}")"
    ;;
  *)
    echo "unknown api '$API'. one of: digilocker-create liveness-create pan bank experian crif eaadhaar liveness-data" >&2
    exit 2
    ;;
esac
