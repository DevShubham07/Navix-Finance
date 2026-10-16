# 04 — Authentication, environments and configuration

## 1. Authentication

**VERIFIED** from every spec's `components.securitySchemes`:

```json
"api_auth": { "type": "http", "in": "header", "scheme": "basic" }
```

Every one of the 19 operations carries `security: [{ "api_auth": [] }]`. So:

- **HTTP Basic**, i.e. `Authorization: Basic base64(<client_id>:<client_secret>)`.
- No OAuth, no bearer token, no request signing, no per-request nonce. **VERIFIED** — no spec declares
  any other scheme and no signing parameter appears in any request schema.
- The `access_token` object in the create-mandate response is **not** API authentication. It is a
  customer-facing token that lets the **SDK** skip first-factor auth on the borrower's device. Do not
  confuse the two.

**UNKNOWN — and it blocks implementation (`11-` Q1):** how client credentials are provisioned. The
enterprise console's profile has no API-keys section (`01-` §1); Digio's docs say only "Use Sandbox
client id/client secret" / "Use Production client id/client secret" without saying where they come
from. Nothing in this exploration produced a credential, and none was sought. Expect to raise it with
Digio support (`support@digio.in`, or the console's sales/support line `080-69489510`).

**UNKNOWN:** credential rotation. No self-serve rotation UI, no documented procedure.

## 2. Environments

**VERIFIED** — https://documentation.digio.in/digienvironments/

| | Sandbox | Production |
|---|---|---|
| **API base URL** (whitelist outbound) | `https://ext.digio.in:444` | `https://api.digio.in` |
| Customer-facing / gateway host (whitelist for in-app opening) | `https://ext.digio.in` | `https://app.digio.in` |
| Enterprise console | `https://ext-enterprise.digio.in` | `https://enterprise.digio.in` |
| Credentials | Sandbox client id/secret | Production client id/secret |
| **Webhook source IPs** (whitelist inbound) | `35.154.20.28` | `13.126.198.236` and `52.66.66.81` |

Notes that matter operationally:

- **The sandbox API runs on a non-standard port, 444.** Egress from ECS and any corporate proxy must
  allow it, or every sandbox call fails as a connect timeout that looks like a Digio outage.
- **Sandbox and production are fully independent environments** — separate credentials, separate
  webhook accounts, separate webhook secrets. **VERIFIED** (webhook FAQ 1 and 11).
- **CONFLICT, resolved:** the webhook FAQ lists only `13.126.198.236` for production while the
  environments page lists `13.126.198.236` **and** `52.66.66.81`. The environments page is the more
  specific, later source — **allow both**, and treat an IP allowlist as defence in depth behind the
  HMAC check (`05-`), never as the primary control.
- The published OpenAPI specs ship `servers: [{"url":"http://localhost:8080"}]` — a build artefact.
  Our mirrored copies in `api-reference/schemas/` have this replaced with the two hosts above, recorded
  in each file's `info.x-provenance`.
- **UNKNOWN:** whether Digio requires *our* egress IPs to be allowlisted on their side, TLS/mTLS
  requirements beyond standard HTTPS, and request timeouts. Nothing documented. `11-` Q8.

## 3. API versioning

Version lives in the **path**: every endpoint is `/v3/…`. **VERIFIED.** No `Accept-version` header,
no version query parameter, no date-based versioning. We found **no changelog and no deprecation
policy** — `11-` Q12. Practically: pin nothing, but diff the specs on every change (README,
"Maintaining this").

## 4. Configuration template — names are provisional

These follow the repo's existing `NAVIX_<PROVIDER>_*` convention (`NAVIX_ESIGN_*`, `SIGNZY_*`,
`DIGITAP_*`, `FINTRIX_*`). **Values below are placeholders. Do not populate with real secrets; secrets
belong in SSM SecureString `/navix/<env>/…` exactly as the other providers' do.**

```bash
# --- Digio eMandate (DigiCollect / NACH) ---
NAVIX_DIGIO_ENABLED=false                 # kill switch; see also the feature flag below
NAVIX_DIGIO_BASE_URL=                     # https://ext.digio.in:444 (sandbox) | https://api.digio.in (prod)
NAVIX_DIGIO_CLIENT_ID=                    # SSM SecureString /navix/<env>/navix/digio/client-id
NAVIX_DIGIO_CLIENT_SECRET=                # SSM SecureString /navix/<env>/navix/digio/client-secret
NAVIX_DIGIO_WEBHOOK_SECRET=               # SSM SecureString; validates X-Digio-Checksum
NAVIX_DIGIO_CORPORATE_CONFIG_ID=          # TSE2610161956…  (account config, not a secret — but keep it with the rest)
NAVIX_DIGIO_CORPORATE_ACCOUNT_NUMBER=     # settlement account for debit presentation (C1)
NAVIX_DIGIO_MANAGEMENT_CATEGORY=L001      # must match what NPCI enabled for utility NACH00000000067052
NAVIX_DIGIO_NARRATION=DhanBoost           # <=20 chars, ^[0-9A-Za-z& .]+$ — appears on the borrower's statement
NAVIX_DIGIO_MANDATE_EXPIRE_DAYS=7         # 1..90, how long the auth link stays valid
NAVIX_DIGIO_TIMEOUT_MS=15000              # UNKNOWN what Digio's own ceiling is
```

Two things are deliberately **not** in this list:

- **A `NAVIX_DIGIO_UTILITY_CODE`.** The utility code and sponsor bank are carried by
  `corporate_config_id` and auto-populated by Digio; duplicating them in our config creates two places
  to be wrong.
- **A mock/stub flag.** The repo's `NAVIX_SMS_MOCK` / `NAVIX_ESIGN_PROVIDER=mock` precedent is a good
  one and `08-` recommends a `DigioMockClient`, but whether it is a boolean or a provider enum should
  match whatever `08-` §3's adapter ends up looking like.

### Startup validation

CLAUDE.md records a live landmine worth repeating: Aadhaar eSign now **refuses to boot** without its
callback parameters, because a silently-missing callback URL failed 156 contracts over four weeks. The
same failure mode exists here — a Digio integration enabled with no `NAVIX_DIGIO_WEBHOOK_SECRET`
would accept forged callbacks or, if written defensively, reject every real one. **Fail fast at
startup**: when `NAVIX_DIGIO_ENABLED=true`, require base URL, client id, client secret, webhook secret
and corporate config id, or throw.
