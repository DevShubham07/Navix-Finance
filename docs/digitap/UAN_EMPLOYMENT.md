# UAN / EPFO employment verification — what actually works on our Digitap account

> **Verified live against production 2026-08-21** (`svc.digitap.ai`, the prod key pair in
> `/navix/dev/navix/digitap/{client-id,client-secret}`).
>
> **PII:** the live call in this doc was made against a real person (a team member's own PAN,
> mobile, DOB and employer, used with their consent). Every identifier below is **replaced with a
> placeholder**. Never paste a real UAN response into this repo — it carries PAN, mobile, DOB,
> gender, Aadhaar verification status and the employer's establishment id.

---

## 1. Which endpoint we use, and why

**Shipped:** `DigitapUanClient` posts to **`/cv/v3/uan_basic/sync`**.

It originally posted to `/cv/v4/uan_advanced/sync`, which returns **412** on our account — so the
EMPLOYMENT check existed end-to-end but had never returned a single row of real data. Probing every
UAN variant with one identity and one key pair found exactly one that works:

| Endpoint | Result |
|---|---|
| `/cv/v4/uan_advanced/sync` — what the code called before | ❌ `412 {"message":"Precondition Failed."}` |
| `/cv/v3/uan_advanced/sync` | ❌ 412 |
| `/cv/v2/uan_advanced/sync` | ❌ 412 |
| `/cv/v5/uan_basic/sync` | ❌ 412 |
| `/cv/v4/uan_basic/sync` | ❌ 412 |
| **`/cv/v3/uan_basic/sync`** — **what we ship** | ✅ **200, `result_code 101`** |
| `/cv/v2/uan_basic/sync` | ❌ 412 |

**412 = the product is not provisioned**, the same code Digitap Email returns (§14 of `CLAUDE.md`).
It is not a credential problem (a sibling endpoint on the same key succeeds), not the IP allow-list
(that answers **403 "IP not allowed"**), and not a malformed request (that answers **400**).

**Exactly one UAN product is enabled: Basic V3.**

---

## 2. The working call

**`POST https://svc.digitap.ai/cv/v3/uan_basic/sync`**
Auth: `Authorization: Basic base64(client_id:client_secret)` · `Content-Type: application/json`

### Request payload

```json
{
  "client_ref_num": "navix-<uuid>",
  "pan": "AAAPA0000A",
  "mobile": "9000000000",
  "dob": "2000-01-31",
  "employee_name": "FIRSTNAME LASTNAME"
}
```

`client_ref_num` is mandatory (max 45 chars). Everything else is *conditionally* mandatory — you must
satisfy **one lookup method**:

| Method | Send |
|---|---|
| 1 | `pan` |
| 2 | `mobile` |
| 3 | `uan` (12 digits, direct) |
| 4 | `pan` + `mobile` |
| 5 | `pan`/`mobile` + `dob` + `employee_name` (fallback) |

Optional: `employer_name` (**only** valid alongside `employee_name` — sending it alone is rejected),
`name_match_method` (`fuzzy` default \| `exact`), `run_alternate_pan_flow` (`0`\|`1`, picks the
UAN-mapping source; **enterprise-gated — returns 400 "Alternate PAN flow is not enabled for your
enterprise" if the account lacks it**, so leave it unset).

`dob` is `yyyy-mm-dd`. PAN pattern is `^[A-Za-z]{3}[pP][A-Za-z]\d{4}[A-Za-z]$`.

### Response (real 200, identifiers replaced)

```json
{
  "http_response_code": 200,
  "client_ref_num": "navix-<uuid>",
  "mobile": "9000000000",
  "request_id": "120529d5-d1a6-4a7d-8aad-4331901aa622",
  "result_code": 101,
  "employee_name": "FIRSTNAME LASTNAME",
  "employer_name": null,
  "result": {
    "uan": ["100000000000"],
    "summary": {
      "recent_employer_data": {
        "establishment_name": "EXAMPLE EMPLOYER PVT LTD",
        "establishment_id": "XXXXX0000000000",
        "member_id": "XXXXX00000000000000000",
        "date_of_exit": "",
        "date_of_joining": "2024-07-29",
        "leave_reason": "",
        "employer_confidence_score": null,
        "matching_uan": "100000000000"
      },
      "matching_uan": "100000000000",
      "is_employed": true,
      "employee_name_match": true,
      "employer_name_match": null,
      "uan_count": 1,
      "date_of_exit_marked": false
    },
    "uan_details": {
      "100000000000": {
        "basic_details": {
          "gender": "MALE",
          "date_of_birth": "2000-01-31",
          "employee_confidence_score": 1.0,
          "name": "FIRSTNAME LASTNAME",
          "mobile": "",
          "aadhaar_verification_status": 1
        },
        "employment_details": {
          "establishment_name": "EXAMPLE EMPLOYER PVT LTD",
          "establishment_id": "XXXXX0000000000",
          "member_id": "XXXXX00000000000000000",
          "date_of_exit": "",
          "date_of_joining": "2024-07-29",
          "leave_reason": "",
          "employer_confidence_score": null
        }
      }
    },
    "uan_source": [{ "uan": "100000000000", "source": "pan and mobile" }],
    "name_dob_filtering_score": null
  },
  "input_data": { "pan": "AAAPA0000A", "mobile": "9000000000", "dob": "2000-01-31",
                  "employee_name": "FIRSTNAME LASTNAME" }
}
```

### Reading it

- **`date_of_exit: ""` means still employed** — an empty string, not null, not a date. Paired with
  `is_employed: true` and `date_of_exit_marked: false`. Normalise `""` → `null` before it reaches
  domain code or every caller has to special-case it.
- **`mobile` inside `basic_details` is empty** even when the lookup matched on mobile. The mobile you
  get back is the top-level echo of what you sent, not EPFO's record.
- **`employer_name_match` is null unless you send `employer_name`.** Null is "not asked", not "no match".
- **`employer_confidence_score` is null on Basic.** It is an Advanced-tier field.
- `uan_source[].source` tells you which identifiers actually resolved the UAN (`"pan and mobile"`
  here) — useful when debugging a wrong-person match.
- `aadhaar_verification_status: 1` = the UAN's Aadhaar is EPFO-verified.

### Outcome codes — read `result_code`, not the HTTP status

All of these arrive as **HTTP 200**; only `101` is billable.

| `result_code` | Meaning |
|---|---|
| `101` | Record resolved |
| `103` | No record found (a valid answer — "EPFO has nothing on this identity") |
| `104` | Identity maps to more than five UANs; nothing resolved |

Transport-level failures carry `http_response_code` in the body:

| Code | Meaning |
|---|---|
| `505` | Source website down — *"Source is busy or unavailable. Try again later"*. Retryable. |
| `506` | *"No result found in source."* — error at source. **Do not retry.** |
| `400` | Bad request, e.g. `run_alternate_pan_flow` on an account without it |
| `412` | Product not provisioned on the account (see §1) |

---

## 3. What we lose by using Basic V3 instead of Advanced V4

The response shape is **almost identical** — `summary.recent_employer_data`, `summary.is_employed`
and `uan_details.<uan>.basic_details` all sit exactly where the client already read them, so the
mapping needed no changes at all. Only the endpoint constant moved.

Two mapped fields go null, and they are the reason Advanced was originally chosen:

| Field the client reads | Basic V3 |
|---|---|
| `summary.recent_employer_data.epfo.is_recent` | absent → null |
| `summary.recent_employer_data.epfo.has_pf_filings_details` | absent → null |

That is the **EPFO PF-filing cross-check** — what catches an employer that has gone quiet, or a
borrower who left without the exit being marked. The sample above is exactly that shape:
`date_of_exit: ""` with `date_of_exit_marked: false`. Basic V3 tells us EPFO *has* a live employment
row; it cannot tell us the employer is still filing against it.

Advanced also carries `employment_history[]` per UAN (each stint's tenure, `is_recent`,
`matched_name`), `partial_output`, and voluntarily-updated `additional_details` (bank, Aadhaar, PAN,
relative name). None of those are mapped today — see the client's javadoc.

**Still worth asking Digitap to enable UAN Advanced V4.** If they do, only `DigitapUanClient.ENDPOINT`
changes and those three fields start arriving — the staff card already renders them (as “—” today) and
the DTO already carries them.

---

## 3a. How it is wired

| Piece | Where |
|---|---|
| HTTP client | `DigitapUanClient` (`navix-verification`) |
| Provider-neutral record | `VerificationPort.EmploymentCheck` (`navix-common`) — Signzy throws `CapabilityNotSupportedException`, so the router always lands on Digitap |
| The check itself | `ApplicationVerificationService.verifyEmployment` — writes an `EMPLOYMENT` row |
| Borrower endpoint | `POST /api/applications/{id}/verify/employment` (no body — every input comes off the profile) |
| Auto-trigger | `signup/consent/page.tsx`, immediately after the bureau pull |
| Staff re-run | the Verifications tab “Retry API” button (`verification:retry` — ADMIN + credit roles) |
| Staff display | `EpfoEmploymentCard` on the customer **Employment & salary** tab; an “EPFO” chip on `/staff/verifications` |
| Backfill | `scripts/backfill-employment-checks.ps1` (dry run by default) |

**It is advisory and gates nothing.** `EMPLOYMENT` is deliberately absent from
`ApplicationVerificationService.REQUIRED`, so a no-record result can never block `submit-kyc`. It is
equally excluded from the `/staff/verifications` bucket maths (`ADVISORY_CHECKS`), because it can
never PASS for a borrower the EPFO has no record of and would otherwise drag clean files out of
“All checks passed” into “Awaiting borrower steps”.

**Status ladder:** PASS only when EPFO shows current employment and the employer does not contradict
the declared one. Everything else is REVIEW — no record, too many UANs, an exit on file, or an
employer mismatch. It never returns FAIL.

**The full UAN is staff-only.** It is stored in `derived.uan` and stripped from borrower reads of
`/verify/summary` (`withoutStaffOnlyFields`), which see `derived.uanMasked` instead.

**Reborrow carries the result forward** via `ApplicationFlowService.CARRIED_CHECKS`, re-stamped
“Carried over from application N” like every other carried check.

---

## 4. Reproducing it

```bash
CID=$(aws ssm get-parameter --name /navix/dev/navix/digitap/client-id \
        --with-decryption --profile navix-dev --region ap-south-1 \
        --query Parameter.Value --output text)
CSEC=$(aws ssm get-parameter --name /navix/dev/navix/digitap/client-secret \
        --with-decryption --profile navix-dev --region ap-south-1 \
        --query Parameter.Value --output text)

curl -sS -X POST https://svc.digitap.ai/cv/v3/uan_basic/sync \
  -H "Authorization: Basic $(printf '%s:%s' "$CID" "$CSEC" | base64 -w0)" \
  -H 'Content-Type: application/json' \
  -d '{"client_ref_num":"navix-probe-1","pan":"<PAN>","mobile":"<10 digits>"}'
```

On Git Bash, scope `MSYS_NO_PATHCONV=1` to the `aws` calls only — it mangles the `/navix/...`
parameter names into Windows paths and you get `ParameterNotFound`, which then presents as empty
credentials rather than an error.

> ⚠️ **Do not run this through the local admin provider workbench to test it.** As of 2026-08-21
> `POST /api/admin/provider-apis/execute` hangs indefinitely on a local backend — every provider,
> not just Digitap — well past the 35s cap `ProviderApiWorkbenchService.within()` claims to enforce,
> and stuck calls exhaust the connection pool until even `GET /history` stops answering. Curl the
> provider directly. That hang is a separate open defect.

---

## 5. Source documents

- `docs/digitap/digitap-apis.json` — `/apis/27` (Basic V3, working) and `/apis/28` (Advanced V4, 412)
- `docs/digitap/DigitapGuide.md` §29 (Advanced V4), rows 21–29 of the endpoint table
- `v3.pdf` at the repo root — **untracked**, 18 pages (printed pp. 123–140) of the vendor guide:
  all of §10 *UAN-Advanced V3*, and §11 *UAN-Advanced V4* introduction only. It has no text layer,
  so grep will not find anything in it. It does **not** cover Basic V3, the variant that works.
