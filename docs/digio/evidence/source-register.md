# Evidence source register

All sources accessed **2026-10-16**. Product/API version observed: **DigiCollect NACH, API `/v3/`,
OpenAPI 3.0.1** (`info.title: "Digio Apis"`, contact `support@digio.in`). No version string beyond
the path was published anywhere.

## Authoritative — Digio's own OpenAPI specs

| ID | Source | Type | Findings | Confidence | Limitations |
|---|---|---|---|---|---|
| S1 | `documentation.digio.in/swagger/final_post_createMandateForm.json` | OpenAPI 3.0.1 | `POST /v3/client/mandate/create_form`; full `mandate_data` schema; mandate `state` + `bank_details.state` enums; HTTP Basic | **VERIFIED** | `servers[]` shipped `http://localhost:8080` — replaced in our mirror, recorded in `x-provenance` |
| S2 | `…/final_post_amendMandateForm.json` | OpenAPI | Amend via the same path, `mandate_type: AMEND`; `A001`/`M036` | **VERIFIED** | |
| S3 | `…/final_get_getMandateInfo.json` | OpenAPI | `GET /v3/client/mandate/{mandate_id}`; `ack_report`/`res_report`; NPCI 4-letter frequencies | **VERIFIED** | |
| S4 | `…/final_post_registrationCancelForm.json` | OpenAPI | `POST /v3/client/mandate/registration_cancel`; `C001`–`C004`; the extra `complete` state | **VERIFIED** | No required fields and no precondition stated — `11-` Q9/Q16 |
| S5 | `…/get_getMandateInfoByCrn.json` | OpenAPI | `GET /v3/client/mandate/by_crn/{crn}`, returns an **array** | **VERIFIED** | |
| S6 | `…/get_getBanksList.json` | OpenAPI | `GET /v3/client/mandate/banks`; per-bank sub-mode capability flags | **VERIFIED** | |
| S7 | `…/post_createScheduledPlan.json` | OpenAPI | `POST /v3/client/nach_debit/scheduled/register`; `amount_in_paise` (int32); `unique_key`; txn states | **VERIFIED** | |
| S8 | `…/post_createScheduledPlanMulti.json` | OpenAPI | `register_multiple`, **max 200 entries** | **VERIFIED** | |
| S9 | `…/post_scheduledPlanAction.json` | OpenAPI | `cancel｜pause｜resume`; 12-value txn status enum | **VERIFIED** | |
| S10 | `…/post_transactionAction.json` | OpenAPI | `cancel｜reattempt｜update｜approve`; cancel before initiation, reattempt after failure | **VERIFIED** | `update`/`approve` undocumented — Q10 |
| S11 | `…/get_getScheduledDetail.json`, `…/get_getScheduledDetailByCoreperateRef.json`, `…/get_getTxnById.json` | OpenAPI | Read-backs by plan id, by `unique_key`, by txn id | **VERIFIED** | |
| S12 | `…/get_getRequesterSettlementDetails.json` | OpenAPI | Consolidated settlement; fees; `settlement_utr_number` | **VERIFIED** | **Digio PA users only** — Q11 |
| S13 | `…/post_generateMandateFile.json`, `…/post_submitSignedMandateFile{,Raw}.json`, `…/get_downloadMandate.json`, `…/post_shareMandate.json` | OpenAPI | Physical-NACH file operations; mandate sharing | **VERIFIED** | Out of scope for v1 |

All 19 are mirrored, pruned to their own referenced schemas, in `../api-reference/schemas/`.

## Authoritative — Digio documentation (prose)

| ID | Source | Findings | Confidence |
|---|---|---|---|
| S14 | `documentation.digio.in/digienvironments/` | Sandbox `https://ext.digio.in:444` · prod `https://api.digio.in` · frontend hosts · **webhook IPs** 35.154.20.28 / 13.126.198.236 + 52.66.66.81 | **VERIFIED** |
| S15 | `…/webhooks/structure/` | Envelope `{id, created_at, event, entities, payload}`; **"at least once delivery"**; `id` resolves idempotency | **VERIFIED** |
| S16 | `…/webhooks/faq/` | `X-Digio-Checksum` = **HmacSHA256 hex of payload**, account-level secret; retry ladder 5/10/15/30 min then hourly to 24h; **10 s timeout**; disablement after 24h; queue is per account (head-of-line blocking); ~5 s prod / ~5 min sandbox; events queued during an outage; rotate the secret in profile | **VERIFIED** |
| S17 | `…/digicollect/webhooks/` | Full event catalog — `MANDATE`, `API_MANDATE`, `NACH_DEBIT`, `UPI_MANDATE`, `UPI_DEBIT`, `CONSOLIDATED_SETTLEMENT`; sample mandate entity (lowercase `event` value) | **VERIFIED** |
| S18 | `…/digicollect/nach/nach_presentation/` | **T-1 late-night cutoff**; file built 00:00 on T; **NPCI-holiday auto-reschedule**; status by EOD on settlement date; three trigger channels | **VERIFIED** |
| S19 | `…/digicollect/nach/nach_registration/error_codes/` | `AP01`–`AP51`, `000` = success; NPCI ONMAGS technical errors `151`–`197` | **VERIFIED** |
| S20 | `…/digicollect/nach/nach_presentation/error_codes/` | NPCI debit **Return** vs **Reject** lists with "Allow RPN" and monthly allowed count | **VERIFIED** |
| S21 | `…/digicollect/upi/` | UPI AutoPay overview; **lending mandates cannot be cancelled/revoked by the borrower**; 24-hour pre-debit notification; no-PIN up to ₹15,000 | **VERIFIED** |
| S22 | `…/digicollect/sdk/` | Mandate SDKs: Android, iOS, Web, Flutter; separate UPI/penny-drop SDK | **VERIFIED** |

*Method note:* `documentation.digio.in` is a Next.js SPA — `WebFetch` returns only the `<title>`.
Content was obtained from the site's own RSC payloads (`<path>/index.txt`) and from the `swagger/*.json`
files the API-reference widget fetches, both public and unauthenticated. Spec URLs were discovered by
inspecting the docs page's own network requests.

## Our account — authenticated console (OBSERVED / VERIFIED-for-us)

| ID | Source | Findings | Confidence |
|---|---|---|---|
| S23 | `enterprise.digio.in/#/mandates/book/bank-profiles` | `corporate_config_id` `TSE261016195601220U17VMUFQVFG2GW`; utility `NACH00000000067052`; sponsor **Yes Bank** `YESB0000001`; service provider `NAVIX FINANCE PRIVATE LIMITED` | **VERIFIED** (for this account) |
| S24 | `…/#/profile` | Sections present; **no API-credentials UI**; **webhooks not configured**; 2FA not enabled | **OBSERVED** |
| S25 | `…/#/mandates/book/generate` | Enabled auth modes (Aadhaar/Physical/API); management categories incl. `O001` **not in the API enum**; 39 live banks; SMS/WhatsApp notification; FIXED/MAXIMUM; account types | **OBSERVED** — opened, **never submitted** |
| S26 | `…/#/mandates/book` | Mandate Book **empty**; search by UMRN/file name/NACH ID/customer identifier/sub-user/CRN; operator-facing state vocabulary | **OBSERVED** |

No credential, cookie, token or `Authorization` header was read or recorded. No customer PII was
present. No private dashboard endpoint is treated as an integration API anywhere in this knowledge
base.

## Navix repository (read-only)

| ID | Source | Findings |
|---|---|---|
| S27 | repo-wide search for `digio｜emandate｜enach｜nach｜autopay｜mandate` | **No mandate implementation exists** |
| S28 | `frontend/src/components/staff/customer-360/mandate-tab.tsx` | Deliberate placeholder: "UPI Autopay / NACH mandates are not available yet… Repayment is manual." |
| S29 | `backend/navix-loan/.../controller/SignzyContractWebhookController.java` | The provider-callback pattern to copy: `permitAll`, self-authenticating on a shared secret, always 200, **re-reads from the provider** |
| S30 | `backend/navix-app/.../config/SecurityConfig.java` | `/api/**` authenticated; provider callbacks explicitly `permitAll` |
| S31 | `backend/navix-loan/.../service/{LoanMath,RepaymentService}.java`, `entity/Payment.java` | Integer-paise money; `PaymentStatus.PENDING_VERIFICATION`; outstanding computed on read |
| S32 | `backend/navix-app/.../scheduler/` | `PaymentReminderScheduler`, `EmploymentRetryScheduler` — the cron precedent |
| S33 | `backend/navix-app/src/main/resources/db/migration/` | Head is **V80** (`V80__bureau_phone.sql`) — CLAUDE.md's "V1..V78" is stale |
| S34 | `CLAUDE.md` | Product economics, conventions, feature-flag policy, SSM paths, the eSign-callback landmine |

## Non-authoritative, consulted and not cited as fact

Web search surfaced third-party API catalogues (apis.io, apitracker.io), a Digio support-desk
knowledge base, and an unrelated competitor's eNACH docs. They were used only to locate
`documentation.digio.in`. Nothing in this knowledge base rests on them — where they agreed with
Digio's own material the latter is cited instead; where they disagreed they were discarded.
