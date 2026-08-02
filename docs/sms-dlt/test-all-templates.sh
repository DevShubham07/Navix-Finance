#!/usr/bin/env bash
#
# test-all-templates.sh — fire every DhanBoost _V1 SMS template through UltronSMS and print a
# pass/fail tracker (ErrorCode 000/0 = live, anything else = not sendable yet).
#
# Each template's text is the EXACT registered content from docs/sms-dlt/SMSULTRON.md with the
# {#var#} slots filled by the sample values from docs/sms-dlt/dlt-templates.json. Uses the same
# gateway params as UltronSmsClient.java and test-send-sms.sh (peid is entity-level, constant).
#
# ── DLT Template IDs ───────────────────────────────────────────────────────────
# All 15 DHANBOOST_*_V1 templates are Active on the DLT portal (2026-08-01) and their real ids are
# baked in below as defaults — the script runs with no exports. Override any one via
# DHANBOOST_DLT_<NAME> if a template is ever re-registered. An empty override SKIPS that template
# rather than burning a guaranteed-006 send.
#
# The _V2 (NAVIX-era) ids are dead — the rebrand changed the brand string AND the URL in every body,
# and a DLT id is bound to its exact content.
#
# ⚠ REBORROW_PREAPPROVED + REFERRAL_REWARD_CREDITED are registered PROMOTIONAL: they need a
#   promotional route (not route 02) and are not delivered to DND-registered numbers. Expect them to
#   fail on this script's transactional defaults — that is not a content problem.
#
# Usage: ./test-all-templates.sh [number]      (default 917417682036)
#        Writes a markdown tracker to docs/sms-dlt/TEMPLATE_TEST_RESULTS.md
#
set -uo pipefail

NUMBER="${1:-917417682036}"
# Credentials — REQUIRED from env, never hardcoded (repo rule: no committed secrets).
#   export NAVIX_SMS_USER=... NAVIX_SMS_PASSWORD=...    before running.
USER="${NAVIX_SMS_USER:?set NAVIX_SMS_USER (UltronSMS gateway username)}"
PASSWORD="${NAVIX_SMS_PASSWORD:?set NAVIX_SMS_PASSWORD (UltronSMS gateway password)}"
BASE_URL="${NAVIX_SMS_BASE_URL:-https://ultronsms.com/api/mt/}"
SENDER_ID="${NAVIX_SMS_SENDER_ID:-DHANBT}"
CHANNEL="${NAVIX_SMS_CHANNEL:-Trans}"
ROUTE="${NAVIX_SMS_ROUTE:-02}"
PEID="${NAVIX_SMS_PEID:-1701178039634361131}"
OUT="docs/sms-dlt/TEMPLATE_TEST_RESULTS.md"

# name ||| dltTemplateId (from env, blank until registered) ||| text-with-sample-values
TEMPLATES=(
"DHANBOOST_OTP_LOGIN_V1|||${DHANBOOST_DLT_OTP_LOGIN:-1777178551180955540}|||Your OTP for DhanBoost login is 123456. It is valid for 5 minutes. Do not share this OTP with anyone. - DhanBoost"
"DHANBOOST_KYC_APPROVED_V1|||${DHANBOOST_DLT_KYC_APPROVED:-1777178556860826081}|||Dear Rahul Sharma, your KYC for DhanBoost application 3071 is verified. Check status at https://dhanboost.com/login. - DhanBoost"
"DHANBOOST_KYC_REJECTED_V1|||${DHANBOOST_DLT_KYC_REJECTED:-1777178556856596751}|||Dear Rahul Sharma, your KYC for DhanBoost application 3071 could not be verified. Re-submit documents at https://dhanboost.com/login. - DhanBoost"
"DHANBOOST_KYC_REMINDER_V1|||${DHANBOOST_DLT_KYC_REMINDER:-1777178556869196458}|||Dear Rahul Sharma, verification steps on your DhanBoost application 3071 are pending. Complete them at https://dhanboost.com/login. - DhanBoost"
"DHANBOOST_LOAN_DISBURSED_V1|||${DHANBOOST_DLT_LOAN_DISBURSED:-1777178556842056405}|||Dear Rahul Sharma, DhanBoost has credited Rs. 8,820 to your bank a/c. Repay Rs. 12,700 by 30 Jun 2026 at https://dhanboost.com/login. - DhanBoost"
"DHANBOOST_REPAYMENT_VERIFIED_V1|||${DHANBOOST_DLT_REPAYMENT_VERIFIED:-1777178551212221383}|||Your payment of Rs. 5,000 to DhanBoost is confirmed. Outstanding balance is Rs. 7,700. View details at https://dhanboost.com/login. - DhanBoost"
"DHANBOOST_REPAYMENT_REJECTED_V1|||${DHANBOOST_DLT_REPAYMENT_REJECTED:-1777178551218012610}|||Your payment of Rs. 5,000 could not be verified by DhanBoost. Log in at https://dhanboost.com/login to check and record it again. - DhanBoost"
"DHANBOOST_PAYMENT_DUE_SOON_V1|||${DHANBOOST_DLT_PAYMENT_DUE_SOON:-1777178556822213797}|||Dear Rahul Sharma, repayment of Rs. 12,700 on your DhanBoost loan 1042 is due on 30 Jun 2026. Pay at https://dhanboost.com/login. - DhanBoost"
"DHANBOOST_PAYMENT_OVERDUE_V1|||${DHANBOOST_DLT_PAYMENT_OVERDUE:-1777178556852586580}|||Dear Rahul Sharma, repayment of Rs. 12,700 on your DhanBoost loan 1042 is overdue by 5 day(s). Pay at https://dhanboost.com/login. - DhanBoost"
"DHANBOOST_LOAN_CLOSED_V1|||${DHANBOOST_DLT_LOAN_CLOSED:-1777178551234723180}|||Your loan with DhanBoost is fully repaid and closed. Thank you. Visit https://dhanboost.com/login to borrow again. - DhanBoost"
"DHANBOOST_APPLICATION_DECLINED_V1|||${DHANBOOST_DLT_APPLICATION_DECLINED:-1777178556864993650}|||Dear Rahul Sharma, your DhanBoost loan application 3071 could not be approved at this time. Details at https://dhanboost.com/login. - DhanBoost"
"DHANBOOST_SETTLEMENT_APPROVED_V1|||${DHANBOOST_DLT_SETTLEMENT_APPROVED:-1777178556848308137}|||Dear Rahul Sharma, a full and final settlement of Rs. 9,000 is approved on DhanBoost loan 1042. Pay at https://dhanboost.com/login. - DhanBoost"
"DHANBOOST_REBORROW_APPROVED_V1|||${DHANBOOST_DLT_REBORROW_APPROVED:-1777178556873259005}|||Dear Rahul Sharma, your DhanBoost application 3071 is approved. Complete the remaining steps at https://dhanboost.com/login. - DhanBoost"
"DHANBOOST_REBORROW_PREAPPROVED_V1|||${DHANBOOST_DLT_REBORROW_PREAPPROVED:-1777178551273887503}|||Welcome back to DhanBoost. You can apply for another loan now. Log in at https://dhanboost.com/login to choose your amount. - DhanBoost"
"DHANBOOST_REFERRAL_REWARD_CREDITED_V1|||${DHANBOOST_DLT_REFERRAL_REWARD_CREDITED:-1777178551284965972}|||Your DhanBoost referral reward of Rs. 500 is credited with reference TXN123456. Log in at https://dhanboost.com/login to view it. - DhanBoost"
)

# ⚠ Spaces MUST be %20, not '+'. UltronSMS takes a '+' literally, so a '+'-encoded body fails the
# char-for-char template match with `006 Invalid template text` even when the text is correct.
# curl's --data-urlencode emits '+', so encode the query ourselves.
enc() { printf '%s' "$1" | python3 -c "import sys,urllib.parse; print(urllib.parse.quote(sys.stdin.read(), safe=''))"; }

field() { printf '%s' "$1" | python3 -c "import sys,json;
try: print(json.load(sys.stdin).get('$2',''))
except Exception: print('')"; }

WHEN="$(date '+%Y-%m-%d %H:%M %Z')"
printf '%-40s %-20s %-8s %-12s %s\n' "TEMPLATE" "DLT_ID" "CODE" "JOBID" "RESULT"
printf '%s\n' "--------------------------------------------------------------------------------------------------------"

ROWS=""
LIVE=0; FAIL=0; SKIP=0
for entry in "${TEMPLATES[@]}"; do
  NAME="${entry%%|||*}"; rest="${entry#*|||}"
  ID="${rest%%|||*}"; TEXT="${rest#*|||}"

  # No registered id yet → skip rather than burn a guaranteed-006 send.
  if [[ -z "$ID" ]]; then
    printf '%-40s %-20s %-8s %-12s %s\n' "$NAME" "—" "—" "—" "SKIP ⏳ (no DLT id in env)"
    ROWS+="| ${NAME} | — | — | — | ⏳ not registered |
"
    SKIP=$((SKIP+1))
    continue
  fi

  RESP="$(curl -sS "${BASE_URL}SendSMS?user=$(enc "$USER")&password=$(enc "$PASSWORD")\
&senderid=$(enc "$SENDER_ID")&channel=$(enc "$CHANNEL")&DCS=0&flashsms=0\
&number=$(enc "$NUMBER")&text=$(enc "$TEXT")\
&route=$(enc "$ROUTE")&peid=$(enc "$PEID")&DLTTemplateId=$(enc "$ID")")"
  CODE="$(field "$RESP" ErrorCode)"; MSG="$(field "$RESP" ErrorMessage)"; JOB="$(field "$RESP" JobId)"
  if [[ "$CODE" == "0" || "$CODE" == "000" ]]; then
    STATUS="LIVE ✅ ($MSG)"; MARK="✅ LIVE"; LIVE=$((LIVE+1))
  else
    STATUS="FAIL ❌ ($MSG)"; MARK="❌ $MSG"; FAIL=$((FAIL+1))
  fi
  printf '%-40s %-20s %-8s %-12s %s\n' "$NAME" "$ID" "${CODE:-—}" "${JOB:-—}" "$STATUS"
  ROWS+="| ${NAME} | ${ID} | ${CODE:-—} | ${JOB:-—} | ${MARK} |
"
done

{
  echo "# DhanBoost \`_V1\` SMS template test results"
  echo ""
  echo "- **Run:** ${WHEN}"
  echo "- **Gateway:** ${BASE_URL}SendSMS · sender \`${SENDER_ID}\` · peid \`${PEID}\` · route \`${ROUTE}\` · channel \`${CHANNEL}\`"
  echo "- **Sent to:** ${NUMBER}"
  echo "- **Summary:** ${LIVE} live / ${FAIL} not sendable / ${SKIP} not registered, of ${#TEMPLATES[@]} templates"
  echo ""
  echo "\`000\`/\`0\` = accepted by the gateway. \`006 Invalid template text\` = template not yet approved/active on the UltronSMS+DLT panel (verify the text char-for-char against SMSULTRON.md first — if it matches, it is an approval-status issue, not a content issue). \`⏳ not registered\` = no DLT id was exported for that template, so it was skipped."
  echo ""
  echo "| Template | DLT Template ID | Code | JobId | Result |"
  echo "|---|---|---|---|---|"
  printf '%s' "$ROWS"
} > "$OUT"

echo ""
echo "Summary: ${LIVE} live / ${FAIL} not sendable / ${SKIP} not registered (of ${#TEMPLATES[@]}). Tracker written to ${OUT}"
