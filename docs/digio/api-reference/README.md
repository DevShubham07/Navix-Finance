# API reference — mirrored OpenAPI specs

Digio's **own** OpenAPI 3.0.1 specs, fetched **2026-10-16** from
`https://documentation.digio.in/swagger/<name>.json` — public and unauthenticated. These are the
authoritative contract; the prose in [`../03-digio-api-catalog.md`](../03-digio-api-catalog.md)
summarises them and this directory wins on any disagreement.

## Provenance and the two edits we made

Each file carries `info.x-provenance` recording its source URL and date. Two deliberate changes:

1. **`servers[]` replaced.** Every published spec shipped `http://localhost:8080` — a build
   placeholder, not a real host. Replaced with the hosts from Digio's environments page
   (`https://api.digio.in`, `https://ext.digio.in:444`).
2. **`components.schemas` pruned** to those transitively referenced by the file's own path, and the
   unused Swagger-2 `definitions` block dropped. Each spec originally bundled all 186 shared schemas,
   ~7 MB in total; pruned, ~236 KB. No field, enum or constraint on any reachable schema was altered.

Nothing else was touched. To verify, re-fetch an original and diff the `paths` object.

## Files

| File | Operation |
|---|---|
| `final_post_createMandateForm.json` | `POST /v3/client/mandate/create_form` — create |
| `final_post_amendMandateForm.json` | `POST /v3/client/mandate/create_form` — amend |
| `final_get_getMandateInfo.json` | `GET /v3/client/mandate/{mandate_id}` |
| `get_getMandateInfoByCrn.json` | `GET /v3/client/mandate/by_crn/{crn}` |
| `final_post_registrationCancelForm.json` | `POST /v3/client/mandate/registration_cancel` |
| `get_getBanksList.json` | `GET /v3/client/mandate/banks` |
| `post_shareMandate.json` | `POST /v3/client/mandate/share` |
| `post_generateMandateFile.json` | `POST /v3/client/mandate/{id}/generate_file` |
| `post_submitSignedMandateFile.json` | `POST /v3/client/mandate/submit_signed_mandate` |
| `post_submitSignedMandateFileRaw.json` | `POST /v3/client/mandate/submit_signed_mandate_raw` |
| `get_downloadMandate.json` | `GET /v3/client/mandate/download` |
| `post_createScheduledPlan.json` | `POST /v3/client/nach_debit/scheduled/register` |
| `post_createScheduledPlanMulti.json` | `POST /v3/client/nach_debit/scheduled/register_multiple` |
| `post_scheduledPlanAction.json` | `POST /v3/client/nach_debit/scheduled/action` |
| `post_transactionAction.json` | `POST /v3/client/nach_debit/transaction/action` |
| `get_getScheduledDetail.json` | `GET /v3/client/nach_debit/scheduled/{planId}` |
| `get_getScheduledDetailByCoreperateRef.json` | `GET /v3/client/nach_debit/scheduled/by_reference_id/{refId}` |
| `get_getTxnById.json` | `GET /v3/client/nach_debit/transaction/{txnId}` |
| `get_getRequesterSettlementDetails.json` | `GET /v3/client/nach_debit/requester_settlement/{id}` |

`schemas/` holds the files; the directory name is kept for the structure `12-` and the README refer to.

## Caveats

- **No `examples`.** Digio's specs carry schemas and descriptions but no worked request/response
  examples. Any example elsewhere in this knowledge base is **illustrative and synthetic**, labelled
  as such, never a captured payload.
- **These describe the API, not our entitlement.** A field existing in the spec does not mean our
  account may use it — see `../11-` Q5 (management categories) and Q11 (PA-only settlement).
- **No changelog exists.** Re-fetch and diff when touching the integration; a new state enum value is
  the change most likely to break us silently.

## Re-fetching

```bash
for n in final_post_createMandateForm final_get_getMandateInfo post_createScheduledPlan; do
  curl -sS "https://documentation.digio.in/swagger/$n.json" -o "/tmp/$n.json"
done
```

Spec names are discoverable in a docs page's own network requests, or by grepping its RSC payload:
`curl -sS "https://documentation.digio.in/<path>/index.txt" | grep -oE '(post|get)_[A-Za-z0-9_]+'`.

## UPI AutoPay specs (added 2026-10-16)

| File | Operation |
|---|---|
| `final_post_createMandateForm_upi.json` | `POST /v3/client/mandate/create_form` with `auth_mode: upi` — UPI mandate registration |
| `post_createUpiScheduledPlan.json` | **`POST /v3/client/upi_payment/scheduled/register`** — UPI debit |
| `post_createUpiScheduledPlanMulti.json` | `POST /v3/client/upi_payment/scheduled/register_multiple` |

**UPI debit is a different path family from NACH debit** (`/v3/client/upi_payment/…` vs
`/v3/client/nach_debit/…`), even though registration shares `/v3/client/mandate/create_form`. See
[`../13-live-verification-log.md`](../13-live-verification-log.md) §6.
