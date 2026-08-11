#!/usr/bin/env bash
#
# Live-test the Signzy "eSign Using Doc Signer" API.
#
#   POST {BASE}/api/v3/esign/docSigner
#   auth: header  Authorization: <raw opaque token>   (NOT "Bearer <t>")
#
# ⚠️ This is a Signzy "REQUEST API" — it must be explicitly enabled for your account by
#    Signzy support/CSM before it will work. If every call below 401s/403s (or 400s with a
#    message like "API not enabled"), that's the account, not this script — ask Signzy to
#    turn it on. See docs/signzy/SignzyGuide.md §9 and the "Notes & caveats" section.
#
# You also need a certificateId, minted separately via Signzy's UploadDocSignConfig API
# (not implemented in this repo) for the Class 2 / Class 3 signer cert you want to use, and
# a reachable logoUrl (png/jpg/jpeg) for the signature stamp.
#
# Usage:
#   SIGNZY_TOKEN=<token> SIGNZY_CERTIFICATE_ID=<certId> SIGNZY_LOGO_URL=<logoUrl> \
#     ./test-esign-docsigner.sh <pdfUrlOrLocalPath> [docSignerClass2|docSignerClass3] [prod|uat]
#
#   ./test-esign-docsigner.sh https://example.com/sample.pdf                     # class2, prod
#   ./test-esign-docsigner.sh https://example.com/sample.pdf docSignerClass3 uat
#   ./test-esign-docsigner.sh ./sample.pdf                                       # local file -> base64
#
set -euo pipefail

PDF_ARG="${1:-}"
SIG_OPTION="${2:-docSignerClass2}"
ENVIRON="${3:-prod}"

TOKEN="${SIGNZY_TOKEN:-}"
CERT_ID="${SIGNZY_CERTIFICATE_ID:-}"
LOGO_URL="${SIGNZY_LOGO_URL:-}"

[[ -z "$PDF_ARG" ]] && { echo "usage: SIGNZY_TOKEN=<t> SIGNZY_CERTIFICATE_ID=<id> SIGNZY_LOGO_URL=<url> $0 <pdfUrlOrLocalPath> [docSignerClass2|docSignerClass3] [prod|uat]" >&2; exit 2; }
[[ -z "$TOKEN" ]] && { echo "error: SIGNZY_TOKEN not set" >&2; exit 2; }
[[ -z "$CERT_ID" ]] && { echo "error: SIGNZY_CERTIFICATE_ID not set (mint via Signzy's uploadDocSignConfig API first)" >&2; exit 2; }
[[ -z "$LOGO_URL" ]] && { echo "error: SIGNZY_LOGO_URL not set (png/jpg/jpeg for the signature stamp)" >&2; exit 2; }

# A local path (exists on disk) is base64-encoded inline; anything else is passed through as a URL.
if [[ -f "$PDF_ARG" ]]; then
  echo "encoding local file as base64: $PDF_ARG"
  PDF_VALUE=$(base64 -w0 "$PDF_ARG" 2>/dev/null || base64 "$PDF_ARG" | tr -d '\n')
else
  PDF_VALUE="$PDF_ARG"
fi

case "$ENVIRON" in
  prod) BASE="https://api.signzy.app" ;;
  uat)  BASE="https://api-preproduction.signzy.app" ;;
  *) echo "error: env must be 'prod' or 'uat', got '$ENVIRON'" >&2; exit 2 ;;
esac

case "$SIG_OPTION" in
  docSignerClass2) EXTRA_FIELDS="" ;;
  docSignerClass3) EXTRA_FIELDS='"reason":"Test","location":"Bangalore",' ;;
  *) echo "error: signatureOptions must be docSignerClass2 or docSignerClass3" >&2; exit 2 ;;
esac

# Large base64 bodies blow past the OS argv limit if passed inline (curl --data "$BODY"), so the
# request body and response both go through temp files instead of shell variables/command args.
BODY_FILE=$(mktemp)
RESP_FILE=$(mktemp)
HDR_FILE=$(mktemp)
trap 'rm -f "$BODY_FILE" "$RESP_FILE" "$HDR_FILE"' EXIT

cat >"$BODY_FILE" <<JSON
{
  "pdf": "$PDF_VALUE",
  "signatureOptions": "$SIG_OPTION",
  "certificateId": "$CERT_ID",
  $EXTRA_FIELDS
  "signatures": [
    {
      "pageNo": ["ALL"],
      "signaturePosition": ["BOTTOMRIGHT"],
      "height": 70,
      "width": 130
    }
  ],
  "logoUrl": "$LOGO_URL",
  "fileTtl": "2 days"
}
JSON

echo "POST $BASE/api/v3/esign/docSigner"
if [[ ${#PDF_VALUE} -gt 200 ]]; then
  echo "body: (pdf field is ${#PDF_VALUE} chars of base64, omitted from log)"
else
  cat "$BODY_FILE"
fi
echo "---"

VERBOSE_FILE=$(mktemp)
trap 'rm -f "$BODY_FILE" "$RESP_FILE" "$HDR_FILE" "$VERBOSE_FILE"' EXIT

status=$(curl -sS -v -w '%{http_code}' -o "$RESP_FILE" -D "$HDR_FILE" --http1.1 --location "$BASE/api/v3/esign/docSigner" \
  --header "Authorization: $TOKEN" \
  --header "Content-Type: application/json" \
  --data @"$BODY_FILE" 2>"$VERBOSE_FILE")
curl_rc=$?

echo "HTTP $status  (curl exit code: $curl_rc, body: $(wc -c < "$RESP_FILE") bytes, headers: $(wc -c < "$HDR_FILE") bytes)"
if [[ -s "$RESP_FILE" ]]; then
  if command -v jq >/dev/null 2>&1; then jq . "$RESP_FILE" 2>/dev/null || cat "$RESP_FILE"; echo
  else cat "$RESP_FILE"; echo
  fi
else
  echo "(empty response body/headers — dumping curl -v wire log instead)"
  cat "$VERBOSE_FILE"
fi

if [[ "$status" == "200" ]]; then
  echo "---"
  echo "PASS: eSign docSigner is working for this account."
else
  echo "---"
  echo "FAIL: HTTP $status — if the message mentions the API not being enabled, this is a"
  echo "'REQUEST API' per SignzyGuide.md and needs Signzy to turn it on for your account."
fi
