#!/usr/bin/env bash
#
# Live-test the Digitap verification APIs NAVIX integrates (the FALLBACK provider).
#
#   Auth:  header  Authorization: Basic base64(client_id:client_secret)
#   Hosts: svc (KYC/Email)         preprod https://svcdemo.digitap.work | prod https://svc.digitap.ai
#          api (Credit/Addr/Face)  preprod https://apidemo.digitap.work | prod https://api.digitap.ai
#          client_id/secret DIFFER between preprod and prod.
#
# Usage:
#   DIGITAP_CLIENT_ID=<id> DIGITAP_CLIENT_SECRET=<secret> ./test-all-digitap.sh <api> [args...] [prod|uat]
#
#   ./test-all-digitap.sh pan BXFPJ0767C
#   ./test-all-digitap.sh email someone@company.com "Kartik Jindal" "Company Pvt Ltd"
#   ./test-all-digitap.sh address 28.6139 77.2090
#   ./test-all-digitap.sh facematch <person_img_url_or_base64> <card_img_url_or_base64>
#   ./test-all-digitap.sh credit 7417682036 kartik jindal BXFPJ0767C <OTP>
#
# Append `uat` (default) or `prod` as the LAST arg to pick the environment.
set -euo pipefail

CID="${DIGITAP_CLIENT_ID:-}"; SECRET="${DIGITAP_CLIENT_SECRET:-}"
[[ -z "$CID" || -z "$SECRET" ]] && { echo "error: set DIGITAP_CLIENT_ID and DIGITAP_CLIENT_SECRET" >&2; exit 2; }
AUTH="Basic $(printf '%s:%s' "$CID" "$SECRET" | base64 | tr -d '\n')"

ENVIRON="uat"; args=("$@")
if [[ ${#args[@]} -gt 0 ]]; then
  last="${args[${#args[@]}-1]}"
  if [[ "$last" == "prod" || "$last" == "uat" ]]; then ENVIRON="$last"; unset 'args[${#args[@]}-1]'; fi
fi
if [[ "$ENVIRON" == "prod" ]]; then SVC="https://svc.digitap.ai"; API="https://api.digitap.ai";
else SVC="https://svcdemo.digitap.work"; API="https://apidemo.digitap.work"; fi

API_NAME="${args[0]:-}"; rest=("${args[@]:1}")
ref="navix-$(date +%s)"

call() { # host path json
  local host="$1" path="$2" body="$3"
  echo "POST $host$path"; echo "body: $body"; echo "---"
  local resp status json
  resp=$(curl -sS -w $'\n%{http_code}' --location "$host$path" \
    --header "Authorization: $AUTH" --header "Content-Type: application/json" --data "$body")
  status=$(printf '%s' "$resp" | tail -n1); json=$(printf '%s' "$resp" | sed '$d')
  echo "HTTP $status"
  if command -v jq >/dev/null 2>&1; then printf '%s' "$json" | jq . 2>/dev/null || printf '%s\n' "$json"
  else printf '%s\n' "$json"; fi
}

case "$API_NAME" in
  pan)
    call "$SVC" "/validation/kyc/v1/pan_details_plus" \
      "$(printf '{"client_ref_num":"%s","pan":"%s"}' "$ref" "${rest[0]}")" ;;
  email)
    call "$SVC" "/cv/email_verification/v1" \
      "$(printf '{"client_ref_num":"%s","email":"%s","individual_name":"%s","establishment_name":"%s"}' "$ref" "${rest[0]}" "${rest[1]:-}" "${rest[2]:-}")" ;;
  address)
    call "$API" "/ent/v1/address-verification" \
      "$(printf '{"uniqueId":"%s","latitude":"%s","longitude":"%s"}' "$ref" "${rest[0]}" "${rest[1]}")" ;;
  facematch)
    call "$API" "/fmfl/v2/face-match" \
      "$(printf '{"person":"%s","card":"%s","clientRefId":"%s"}' "${rest[0]}" "${rest[1]}" "$ref")" ;;
  credit)
    call "$API" "/credit_analytics/request" \
      "$(printf '{"client_ref_num":"%s","mobile_no":"%s","name_lookup":0,"first_name":"%s","last_name":"%s","pan":"%s","consent_message":"I authorize the credit report pull.","consent_acceptance":"Yes","device_type":"web","otp":"%s","timestamp":"%s","device_ip":"0.0.0.0","device_id":"navix"}' "$ref" "${rest[0]}" "${rest[1]}" "${rest[2]}" "${rest[3]}" "${rest[4]:-}" "$(date +%s)000")" ;;
  *)
    echo "unknown api '$API_NAME'. one of: pan email address facematch credit" >&2; exit 2 ;;
esac
