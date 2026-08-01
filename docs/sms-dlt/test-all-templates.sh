#!/usr/bin/env bash
#
# test-all-templates.sh — fire every DhanBoost _V1 SMS template through UltronSMS and print a
# pass/fail tracker (ErrorCode 000/0 = live, anything else = not sendable yet).
#
# Each template's text is the EXACT registered content from docs/sms-dlt/SMSULTRON.md with the
# {#var#} slots filled by the sample values from docs/sms-dlt/dlt-templates.json. Uses the same
# gateway params as UltronSmsClient.java and test-send-sms.sh (peid is entity-level, constant).
#
# ── DLT Template IDs come from the environment ─────────────────────────────────
# The NAVIX→DhanBoost rebrand changed the brand string AND the URL in every body, which invalidates
# every _V2 id (they were bound to the old content). The DHANBOOST_*_V1 batch is NOT YET REGISTERED,
# so this script has no ids hardcoded. Export the ones you have; unset templates are SKIPPED rather
# than fired at the gateway, since a send without a valid id can only return 006.
#
#   export DHANBOOST_DLT_OTP_LOGIN=17071783xxxxxxxxxx
#   export DHANBOOST_DLT_KYC_APPROVED=...            # etc, one per template below
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
"DHANBOOST_OTP_LOGIN_V1|||${DHANBOOST_DLT_OTP_LOGIN:-}|||Your OTP for DhanBoost login is 123456. It is valid for 5 minutes. Do not share this OTP with anyone. - DhanBoost"
"DHANBOOST_KYC_APPROVED_V1|||${DHANBOOST_DLT_KYC_APPROVED:-}|||Dear Rahul Sharma, your KYC for DhanBoost application 3071 is verified. Check status at https://dhanboost.com/login. - DhanBoost"
"DHANBOOST_KYC_REJECTED_V1|||${DHANBOOST_DLT_KYC_REJECTED:-}|||Dear Rahul Sharma, your KYC for DhanBoost application 3071 could not be verified. Re-submit documents at https://dhanboost.com/login. - DhanBoost"
"DHANBOOST_KYC_REMINDER_V1|||${DHANBOOST_DLT_KYC_REMINDER:-}|||Dear Rahul Sharma, verification steps on your DhanBoost application 3071 are pending. Complete them at https://dhanboost.com/login. - DhanBoost"
"DHANBOOST_LOAN_DISBURSED_V1|||${DHANBOOST_DLT_LOAN_DISBURSED:-}|||Dear Rahul Sharma, DhanBoost has credited Rs. 8,820 to your bank a/c. Repay Rs. 12,700 by 30 Jun 2026 at https://dhanboost.com/login. - DhanBoost"
"DHANBOOST_REPAYMENT_VERIFIED_V1|||${DHANBOOST_DLT_REPAYMENT_VERIFIED:-}|||Your payment of Rs. 5,000 to DhanBoost is confirmed. Outstanding balance is Rs. 7,700. View details at https://dhanboost.com/login. - DhanBoost"
"DHANBOOST_REPAYMENT_REJECTED_V1|||${DHANBOOST_DLT_REPAYMENT_REJECTED:-}|||Your payment of Rs. 5,000 could not be verified by DhanBoost. Log in at https://dhanboost.com/login to check and record it again. - DhanBoost"
"DHANBOOST_PAYMENT_DUE_SOON_V1|||${DHANBOOST_DLT_PAYMENT_DUE_SOON:-}|||Dear Rahul Sharma, repayment of Rs. 12,700 on your DhanBoost loan 1042 is due on 30 Jun 2026. Pay at https://dhanboost.com/login. - DhanBoost"
"DHANBOOST_PAYMENT_OVERDUE_V1|||${DHANBOOST_DLT_PAYMENT_OVERDUE:-}|||Dear Rahul Sharma, repayment of Rs. 12,700 on your DhanBoost loan 1042 is overdue by 5 day(s). Pay at https://dhanboost.com/login. - DhanBoost"
"DHANBOOST_LOAN_CLOSED_V1|||${DHANBOOST_DLT_LOAN_CLOSED:-}|||Your loan with DhanBoost is fully repaid and closed. Thank you. Visit https://dhanboost.com/login to borrow again. - DhanBoost"
"DHANBOOST_APPLICATION_DECLINED_V1|||${DHANBOOST_DLT_APPLICATION_DECLINED:-}|||Dear Rahul Sharma, your DhanBoost loan application 3071 could not be approved at this time. Details at https://dhanboost.com/login. - DhanBoost"
"DHANBOOST_SETTLEMENT_APPROVED_V1|||${DHANBOOST_DLT_SETTLEMENT_APPROVED:-}|||Dear Rahul Sharma, a full and final settlement of Rs. 9,000 is approved on DhanBoost loan 1042. Pay at https://dhanboost.com/login. - DhanBoost"
"DHANBOOST_REBORROW_APPROVED_V1|||${DHANBOOST_DLT_REBORROW_APPROVED:-}|||Dear Rahul Sharma, your DhanBoost application 3071 is approved. Complete the remaining steps at https://dhanboost.com/login. - DhanBoost"
"DHANBOOST_REBORROW_PREAPPROVED_V1|||${DHANBOOST_DLT_REBORROW_PREAPPROVED:-}|||Welcome back to DhanBoost. You can apply for another loan now. Log in at https://dhanboost.com/login to choose your amount. - DhanBoost"
"DHANBOOST_REFERRAL_REWARD_CREDITED_V1|||${DHANBOOST_DLT_REFERRAL_REWARD_CREDITED:-}|||Your DhanBoost referral reward of Rs. 500 is credited with reference TXN123456. Log in at https://dhanboost.com/login to view it. - DhanBoost"
)

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

  RESP="$(curl -sS -G "${BASE_URL}SendSMS" \
    --data-urlencode "user=${USER}" --data-urlencode "password=${PASSWORD}" \
    --data-urlencode "senderid=${SENDER_ID}" --data-urlencode "channel=${CHANNEL}" \
    --data-urlencode "DCS=0" --data-urlencode "flashsms=0" \
    --data-urlencode "number=${NUMBER}" --data-urlencode "text=${TEXT}" \
    --data-urlencode "route=${ROUTE}" --data-urlencode "peid=${PEID}" \
    --data-urlencode "DLTTemplateId=${ID}")"
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
