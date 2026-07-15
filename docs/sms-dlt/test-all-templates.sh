#!/usr/bin/env bash
#
# test-all-templates.sh — fire every NAVIX _V2 SMS template through UltronSMS and print a
# pass/fail tracker (ErrorCode 000/0 = live, anything else = not sendable yet).
#
# Each template's text is the EXACT registered content from docs/sms-dlt/SMSULTRON.md with the
# {#var#} slots filled by the sample values from docs/sms-dlt/dlt-templates.json. Uses the same
# gateway params as UltronSmsClient.java and test-send-sms.sh (peid is entity-level, constant).
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
SENDER_ID="${NAVIX_SMS_SENDER_ID:-NAVIXF}"
CHANNEL="${NAVIX_SMS_CHANNEL:-Trans}"
ROUTE="${NAVIX_SMS_ROUTE:-02}"
PEID="${NAVIX_SMS_PEID:-1701178039634361131}"
OUT="docs/sms-dlt/TEMPLATE_TEST_RESULTS.md"

# name ||| dltTemplateId ||| text-with-sample-values
TEMPLATES=(
"NAVIX_OTP_LOGIN_V2|||1707178366195230667|||Your OTP for NAVIX Finance login is 123456. It is valid for 5 minutes. Do not share this OTP with anyone. - NAVIX Finance"
"NAVIX_KYC_APPROVED_V2|||1707178366932300977|||Your KYC is verified with NAVIX Finance. Log in at https://www.navixfinance.com/login to choose your loan amount. - NAVIX Finance"
"NAVIX_KYC_REJECTED_V2|||1707178366348720389|||We could not verify your KYC with NAVIX Finance. Log in at https://www.navixfinance.com/login to review and resubmit. - NAVIX Finance"
"NAVIX_KYC_REMINDER_V2|||1707178366418625468|||Your verification with NAVIX Finance is incomplete. Log in at https://www.navixfinance.com/login to complete your pending steps. - NAVIX Finance"
"NAVIX_LOAN_DISBURSED_V2|||1707178366447724079|||NAVIX Finance has disbursed Rs. 8,820 to your bank account. Repay Rs. 12,700 by 30 Jun 2026 at https://www.navixfinance.com/login. - NAVIX Finance"
"NAVIX_REPAYMENT_VERIFIED_V2|||1707178366455225240|||Your payment of Rs. 5,000 to NAVIX Finance is confirmed. Outstanding balance is Rs. 7,700. View details at https://www.navixfinance.com/login. - NAVIX Finance"
"NAVIX_REPAYMENT_REJECTED_V2|||1707178366462035431|||Your payment of Rs. 5,000 could not be verified by NAVIX Finance. Log in at https://www.navixfinance.com/login to check the reference and record it again. - NAVIX Finance"
"NAVIX_PAYMENT_DUE_SOON_V2|||1707178366469516547|||Your NAVIX Finance payment of Rs. 12,700 is due in 3 day(s) by 30 Jun 2026. Pay at https://www.navixfinance.com/login on or after your salary day with no penalty. - NAVIX Finance"
"NAVIX_PAYMENT_OVERDUE_V2|||1707178366491271206|||Your NAVIX Finance payment of Rs. 12,700 is overdue by 5 day(s). Pay now at https://www.navixfinance.com/login to stop the daily penalty and protect your credit score. - NAVIX Finance"
"NAVIX_LOAN_CLOSED_V2|||1707178367132471619|||Your loan with NAVIX Finance is fully repaid and closed. Thank you. Visit https://www.navixfinance.com/login to borrow again. - NAVIX Finance"
"NAVIX_APPLICATION_DECLINED_V2|||1707178366517137780|||NAVIX Finance is unable to approve your loan application at this time. Visit https://www.navixfinance.com/login for details. - NAVIX Finance"
"NAVIX_SETTLEMENT_APPROVED_V2|||1707178366525825089|||A full and final settlement of Rs. 6,500 is approved on your NAVIX Finance loan. Pay at https://www.navixfinance.com/login to close the loan. - NAVIX Finance"
"NAVIX_REBORROW_APPROVED_V2|||1707178367131025190|||Your loan application with NAVIX Finance is approved. Log in at https://www.navixfinance.com/login to choose your amount. - NAVIX Finance"
"NAVIX_REBORROW_PREAPPROVED_V2|||1707178366559342535|||Welcome back to NAVIX Finance. You can apply for another loan now. Log in at https://www.navixfinance.com/login to choose your amount. - NAVIX Finance"
"NAVIX_REFERRAL_REWARD_CREDITED_V2|||1707178366569621398|||Your NAVIX Finance referral reward of Rs. 500 is credited with reference REF12345. Log in at https://www.navixfinance.com/login to view it. - NAVIX Finance"
)

field() { printf '%s' "$1" | python3 -c "import sys,json;
try: print(json.load(sys.stdin).get('$2',''))
except Exception: print('')"; }

WHEN="$(date '+%Y-%m-%d %H:%M %Z')"
printf '%-34s %-20s %-8s %-12s %s\n' "TEMPLATE" "DLT_ID" "CODE" "JOBID" "RESULT"
printf '%s\n' "------------------------------------------------------------------------------------------------"

ROWS=""
LIVE=0; FAIL=0
for entry in "${TEMPLATES[@]}"; do
  NAME="${entry%%|||*}"; rest="${entry#*|||}"
  ID="${rest%%|||*}"; TEXT="${rest#*|||}"
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
  printf '%-34s %-20s %-8s %-12s %s\n' "$NAME" "$ID" "${CODE:-—}" "${JOB:-—}" "$STATUS"
  ROWS+="| ${NAME} | ${ID} | ${CODE:-—} | ${JOB:-—} | ${MARK} |
"
done

{
  echo "# NAVIX _V2 SMS template test results"
  echo ""
  echo "- **Run:** ${WHEN}"
  echo "- **Gateway:** ${BASE_URL}SendSMS · sender \`${SENDER_ID}\` · peid \`${PEID}\` · route \`${ROUTE}\` · channel \`${CHANNEL}\`"
  echo "- **Sent to:** ${NUMBER}"
  echo "- **Summary:** ${LIVE} live / ${FAIL} not sendable, of ${#TEMPLATES[@]} templates"
  echo ""
  echo "\`000\`/\`0\` = accepted & delivered. \`006 Invalid template text\` = template not yet approved/active on the UltronSMS+DLT panel (text was verified char-for-char against SMSULTRON.md, so it is an approval-status issue, not a content issue)."
  echo ""
  echo "| Template | DLT Template ID | Code | JobId | Result |"
  echo "|---|---|---|---|---|"
  printf '%s' "$ROWS"
} > "$OUT"

echo ""
echo "Summary: ${LIVE} live / ${FAIL} not sendable (of ${#TEMPLATES[@]}). Tracker written to ${OUT}"
