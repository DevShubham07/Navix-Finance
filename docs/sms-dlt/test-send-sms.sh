#!/usr/bin/env bash
#
# test-send-sms.sh — send ONE live SMS through the UltronSMS gateway and report the result.
#
# Mirrors the exact request the app builds in
#   backend/navix-app/src/main/java/com/navix/sms/UltronSmsClient.java
# (GET /api/mt/SendSMS with user/password/senderid/channel/DCS/flashsms/number/text/route/peid/
#  DLTTemplateId). A green run here means the same values will work once they go into
# navix.sms.* env / SSM.
#
# ── Usage ──────────────────────────────────────────────────────────────────────
#   ./test-send-sms.sh <number> [text] [dltTemplateId]
#     <number>          full MSISDN incl. country code, e.g. 917417682036   (REQUIRED)
#     [text]            message body; must match the registered template char-for-char
#                       (only the {#var#} slots substituted).  Default = the working OTP text.
#     [dltTemplateId]   DLT Template ID the text is registered under.       Default = working OTP id.
#
# REQUIRED env (credentials — never hardcoded / never committed):
#   NAVIX_SMS_USER   NAVIX_SMS_PASSWORD
# Optional env (non-secret; default to the sample-curl values):
#   NAVIX_SMS_BASE_URL  NAVIX_SMS_SENDER_ID  NAVIX_SMS_CHANNEL  NAVIX_SMS_ROUTE
#   NAVIX_SMS_PEID   DLT_TEMPLATE_ID   TEXT
# Args fall back to env, which falls back to the non-secret defaults below.
#
# ── Notes ──────────────────────────────────────────────────────────────────────
#   * Success envelope: JSON {ErrorCode, ErrorMessage, JobId}.  ErrorCode "0" or "000" == sent
#     (same check as UltronSmsClient); anything else is surfaced and this script exits non-zero.
#   * Template rules: sent text must equal the registered template EXACTLY (only variable slots
#     filled); use "Rs." not "₹" (₹ forces costly UCS-2); any URL must be portal-whitelisted.
#   * The _V2 template IDs for every NotificationType live in docs/sms-dlt/SMSULTRON.md — pass one
#     via DLT_TEMPLATE_ID + its exact content via TEXT to test that template.
#   * DISCREPANCY: the working defaults below (peid 1701178039634361131, OTP template
#     1707178288705285901) are an earlier approved pair; they differ from the _V2 batch in
#     SMSULTRON.md (OTP _V2 = 1707178366195230667). Defaults use the proven-working pair.
#
set -euo pipefail

# ── Credentials — REQUIRED from env, never hardcoded (repo rule: no committed secrets) ──
#   export NAVIX_SMS_USER=... NAVIX_SMS_PASSWORD=...    (or `source` a gitignored env file)
USER="${NAVIX_SMS_USER:?set NAVIX_SMS_USER (UltronSMS gateway username)}"
PASSWORD="${NAVIX_SMS_PASSWORD:?set NAVIX_SMS_PASSWORD (UltronSMS gateway password)}"

# ── Non-secret defaults (the sample curl) ───────────────────────────────────────
BASE_URL="${NAVIX_SMS_BASE_URL:-https://ultronsms.com/api/mt/}"
SENDER_ID="${NAVIX_SMS_SENDER_ID:-NAVIXF}"
CHANNEL="${NAVIX_SMS_CHANNEL:-Trans}"
ROUTE="${NAVIX_SMS_ROUTE:-02}"
PEID="${NAVIX_SMS_PEID:-1701178039634361131}"

DEFAULT_TEXT="Your OTP for NAVIX login is 123432. Do not share this code with anyone. Valid for 5 minutes. Regards, Navix Finance"
DEFAULT_DLT_ID="1707178288705285901"

# ── Resolve args → env → defaults ───────────────────────────────────────────────
NUMBER="${1:-}"
TEXT="${2:-${TEXT:-$DEFAULT_TEXT}}"
DLT_ID="${3:-${DLT_TEMPLATE_ID:-$DEFAULT_DLT_ID}}"

if [[ -z "$NUMBER" ]]; then
  echo "usage: $0 <number> [text] [dltTemplateId]" >&2
  echo "  <number> is required — full MSISDN incl. country code, e.g. 917417682036" >&2
  exit 2
fi

# ── Show the resolved request (password masked) ─────────────────────────────────
mask() { local s="$1"; if [[ ${#s} -le 2 ]]; then echo "**"; else echo "${s:0:1}***${s: -1}"; fi; }

echo "── UltronSMS SendSMS ───────────────────────────────────────────────"
echo "  URL          : ${BASE_URL}SendSMS"
echo "  user         : ${USER}"
echo "  password     : $(mask "$PASSWORD")"
echo "  senderid     : ${SENDER_ID}"
echo "  channel      : ${CHANNEL}   DCS=0  flashsms=0"
echo "  route        : ${ROUTE}"
echo "  peid         : ${PEID}"
echo "  DLTTemplateId: ${DLT_ID}"
echo "  number       : ${NUMBER}"
echo "  text         : ${TEXT}"
echo "────────────────────────────────────────────────────────────────────"

# ── Fire (curl -G + --data-urlencode encodes text safely) ───────────────────────
RESPONSE="$(curl -sS -G "${BASE_URL}SendSMS" \
  --data-urlencode "user=${USER}" \
  --data-urlencode "password=${PASSWORD}" \
  --data-urlencode "senderid=${SENDER_ID}" \
  --data-urlencode "channel=${CHANNEL}" \
  --data-urlencode "DCS=0" \
  --data-urlencode "flashsms=0" \
  --data-urlencode "number=${NUMBER}" \
  --data-urlencode "text=${TEXT}" \
  --data-urlencode "route=${ROUTE}" \
  --data-urlencode "peid=${PEID}" \
  --data-urlencode "DLTTemplateId=${DLT_ID}")"

echo "raw response : ${RESPONSE}"

# ── Parse {ErrorCode, ErrorMessage, JobId} without extra deps ───────────────────
get_field() {
  # $1 = JSON field name; prints the value (python3 if present, else a grep/sed fallback)
  if command -v python3 >/dev/null 2>&1; then
    printf '%s' "$RESPONSE" | python3 -c "import sys,json;
try:
    print(json.load(sys.stdin).get('$1',''))
except Exception:
    print('')"
  else
    printf '%s' "$RESPONSE" | grep -o "\"$1\"[[:space:]]*:[[:space:]]*\"\?[^,\"}]*" \
      | head -1 | sed -E "s/.*:[[:space:]]*\"?//"
  fi
}

ERROR_CODE="$(get_field ErrorCode)"
ERROR_MSG="$(get_field ErrorMessage)"
JOB_ID="$(get_field JobId)"

echo "────────────────────────────────────────────────────────────────────"
if [[ "$ERROR_CODE" == "0" || "$ERROR_CODE" == "000" ]]; then
  echo "✅ SENT   ErrorCode=${ERROR_CODE}  JobId=${JOB_ID}"
  exit 0
else
  echo "❌ FAILED ErrorCode=${ERROR_CODE:-<none>}  ErrorMessage=${ERROR_MSG:-<none>}"
  exit 1
fi
