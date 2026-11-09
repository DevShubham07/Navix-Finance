# Bank Statement Analysis (BSA)

How DhanBoost uses Signzy's Bank Statement Analysis: the vendor API, our implementation, current
status, known gaps and how to test it.

Sources: Signzy API portal → API Catalog → Bank Statement Analysis (read 2026-11-09), and the code
listed in [Where the code lives](#where-the-code-lives). If this page and the code disagree, the code
wins.

> **Status (re-checked 2026-11-09): the feature cannot complete in production.**
> On the production credential `navixfinance_prod_v3`, `analyze-statement` is available, but
> `retrieve-work-order` and `download-report` both show **Access Not Available**. A run is sent to
> Signzy and is billable, but its result can never be read back.
> **Do not press "Run analysis" until Signzy enables both calls.** See
> [Test record](#test-record) and [Going live](#going-live).

## What it does

Credit staff submit a customer's uploaded bank statement PDFs to Signzy. Signzy extracts the accounts
and transactions, scores the statements, checks them for tampering, and produces a JSON and Excel
report.

It runs only on demand. A Credit Executive, Credit Head or ADMIN triggers it per customer from
**Customer → Banking tab**. Nothing in the loan lifecycle calls it, because Signzy bills per statement.
The result is stored, so later readers see it without paying again.

## Flow

```
Staff clicks "Run analysis" (Banking tab, after a confirm dialog)
  │  POST /api/staff/bank-analysis/customers/{customerId}
  ▼
BankStatementAnalysisService.run
  │  refuses if the customer's latest run is still PENDING (BSA_IN_PROGRESS)
  │  collects every S3-backed BANK_STATEMENT across all the customer's applications
  │  presigns each one and attaches its file password
  ▼
Signzy analyze-statement ─► reportId, IN PROGRESS ─► row saved as PENDING
  ▼
Banking tab polls every 15s while PENDING (each read throttled to one Signzy poll per 10s)
  │  GET /api/staff/bank-analysis/customers/{customerId}
  ▼
Signzy retrieve-work-order ─► ANALYSED ─► Signzy download-report ─► JSON + Excel URLs stored
```

We pass no `callbackUrl`, since that would need a new public endpoint. Our database row is the source
of truth, and a staff read moves it forward.

## Signzy API reference

All three are `POST`, with the header token in `Authorization`.

| Environment | Host | Account state |
|---|---|---|
| Production | `https://api.signzy.app` | `analyze-statement` only (see the status above) |
| Preproduction | `https://api-preproduction.signzy.app` | All three entitled, but `403 "No remaining API credits"` when last tried on 2026-11-09 |

The portal's API runner displays `api.signzy.app` even in Test mode, when it really posts to
preproduction through `cors.signzy.app`. Read the Network panel to see which account answered.

### 1. `/api/v3/statementanalysis/analyze-statement`

| Field | Required | Notes | We send |
|---|---|---|---|
| `authToken` | Yes, per the docs | JWT from Signzy's Authenticate API, in the body | Only if `SIGNZY_BSA_AUTH_TOKEN` is set |
| `files[].fileUrl` | Yes | Public or presigned URL | Presigned S3 URL |
| `files[].password` | No | PDF password | When the borrower gave one |
| `customerName` | No | Matched against the name on the statement | Profile full name |
| `statementPeriod` | No | For example `6m`, `1y` | Always `6m` |
| `recencyDate` | No | `DD/MM/YYYY` | Not sent |
| `recencyPeriod` | No | For example `3m` | Not sent |
| `callbackUrl` | No | Webhook; Signzy recommends it for production | Not sent |
| `templateId` | No | One of 4 output templates, issued by support@signzy.com | Not sent |

Signzy also accepts a multipart upload (`files` + `data`). We use only the URL form.

**Rejected inputs:** several statements merged into one PDF, cheque book images, statements without a
running balance after each transaction, and statements without the issuing bank's name.

**Response:** `report` (`reportId`, `reportStatus`, `reportStatusDescription`, …) and `reportAccounts[]`.
Each entry holds `account` plus `fileDetails.success[]` and `fileDetails.failed[]`. A failed file carries
`errorMessage`: `Password required`, `Incorrect password` or `Incorrect bank statement`.

### 2. `/api/v3/statementanalysis/retrieve-work-order`

Input: `authToken`, and `reportId` or `borrowerReferenceId`.

Returns the same envelope, filled in further. Per-file details are under `reportAccounts[].files[]`
rather than `fileDetails.success[]`:

- Report: `report.bankScore`, `reportStatus`, `reportCamStatus`.
- File: `status`, `fromDate`, `tillDate`, `fraudScore`, `authenticity`, `authenticityDetails[]`,
  `pageCount`, `txnCount`.

### 3. `/api/v3/statementanalysis/download-report`

| Field | Required | Notes | We send |
|---|---|---|---|
| `authToken` | Yes, per the docs | | If configured |
| `reportId` or `borrowerReferenceId` | One of them | | `reportId` |
| `fileType` | Yes | JSON, Excel, or both | `excel and json` |
| `templateId` | No | | Not sent |
| `includeAccountLevelReports` | No | Boolean | Not sent |
| `getTransactions` | No | Boolean; adds transaction JSON links | Not sent |

Response: `result.json`, `result.excel`, `result.transactionsJsonUrl`.

### Status values

| Level | Values |
|---|---|
| Report | `IN PROGRESS`, `ANALYSED`, `ANALYSIS ON HOLD` |
| Report description | `ANALYSED (X ACCOUNTS)`, `ANALYSED (X OF Y ACCOUNTS)` |
| CAM report | `CAM IN PROGRESS`, `CAM READY`, `CAM FAILED` |
| Account | `INITIALISING`, `ANALYSING`, `ANALYSED`, `IN REVIEW`, `NO TRANSACTION`, `ANALYSIS ON HOLD` |
| File | `INITIALISING`, `PARSING`, `PARSED`, `IN REVIEW`, `NOT PARSABLE`, `NO TRANSACTION` |

Other calls in the same catalog group, which we don't use: Preanalysis (403 on 2026-10-16), Tampering
Analysis (404 on 2026-10-16), and BSA Journey web link.

## Our API

| Endpoint | Who | Effect |
|---|---|---|
| `POST /api/staff/bank-analysis/customers/{id}` | `CREDIT_EXECUTIVE`, `CREDIT_HEAD`, `ADMIN` | **Billable** run |
| `GET /api/staff/bank-analysis/customers/{id}` | Any staff except DSA | Latest run, or `null`; advances a running report |
| `GET /api/staff/bank-analysis/customers/{id}/history` | Any staff except DSA | All runs, newest first; no vendor call |

| Error code | Meaning |
|---|---|
| `BSA_IN_PROGRESS` | The latest run is still `PENDING` and under 24 hours old |
| `BSA_NO_STATEMENTS` | No S3-backed statement on file |
| `BSA_FAILED` | Signzy refused the submission; a `FAILED` row is saved |
| `FORBIDDEN_ROLE` | The caller isn't on the credit desk or ADMIN |

## Our status mapping

| Signzy `reportStatus` | Our `status` |
|---|---|
| `ANALYSED` | `ANALYSED` |
| `ANALYSIS ON HOLD` | `FAILED` |
| Anything else, or missing | `PENDING` |

A row still `PENDING` 24 hours after it started becomes `FAILED`, keeping the last error in `message`, so
the customer can be run again.

## Stored data

Table `bank_statement_analysis` (V81), one row per run, keyed by `customer_id`:

- `status`, `report_id`, `report_status`, `report_status_desc`, `bank_score`, `message`
- `request_json`: object keys, customer name and period, never presigned URLs or passwords
- `response_json`: the latest Signzy envelope, with credentials such as the PDF password masked
- `report_json_url`, `report_excel_url`, `last_polled_at`, `duration_ms`
- `run_by_staff_id`, `run_by_name`, `run_by_role`

The Banking tab and the Third-party tab read this row. BRE rule 9 reads it for salary.

## Known gaps

Ordered by impact. All of these come from reading the code and Signzy's spec. None has been confirmed
against a finished report, because none exists yet.

1. **Fixed, not yet deployed: a stuck run locked the customer out of re-running.** A run still `PENDING`
   after 24 hours now becomes `FAILED`, on the next read or run attempt. A re-run is billed again, so
   don't re-run until read-back access is enabled.
2. **Fixed, not yet deployed: the PDF password was stored in `response_json`.** Signzy echoes it in
   `fileDetails.success[].password` and `files[].password`. The client now masks credentials before the
   row is written, and migration V100 cleans existing rows. The column still holds the full account
   number and any PAN, email and address found on the statement.
3. **Files Signzy rejects aren't shown.** `accountsOf` reads only `fileDetails.success[]`, so staff
   never see `Password required` or `Incorrect bank statement`.
4. **Old statements get billed again.** `statementsOf` sends every statement across all of a customer's
   applications, so a returning borrower pays for earlier uploads too.
5. **`ANALYSIS ON HOLD` is treated as final.** The spec doesn't say whether a held report can resume.
6. **Salary isn't mapped.** It's in the report JSON, whose schema depends on the template and is still
   unseen. `salary` is always `null`, so the Banking tab and BRE rule 9 show "not identified". Opening
   and closing balances are also `null`.
7. **The body `authToken` is unresolved.** The docs mark it mandatory. A 2026-10-16 probe without it
   passed validation, and a real analyze call succeeded, but the read-back calls have never been reached.

## Test record

**2026-11-09: sample run in Test mode failed for lack of credits.** The fictional
[`samples/specimen-bank-statement.pdf`](samples/specimen-bank-statement.pdf) was submitted through the
portal runner on `navixfinance_preprod_v3`. `analyze-statement` returned
`403 "No remaining API credits. Contact support for assistance."`, so no analysis exists. Signzy's own
sample links are also dead: the statement returns 404 and the sample report 410 (expired).

The specimen uses a fictional bank, so Signzy may reject it even with credits. A correct analysis
would find: 51 transactions, opening balance ₹18,450.00, closing balance ₹1,48,915.95, 6 salary credits
totalling ₹2,70,000.00, 7 EMI debits, and 1 EMI bounce (September 2026).

**2026-11-09: live test not run, on purpose.** Requested: analyse one random production statement.
Portal runner in Live mode, credential `navixfinance_prod_v3`, opening the forms only and submitting
nothing:

| Call | Live-mode result |
|---|---|
| `analyze-statement` | Form available, marked "API requests made in LIVE mode will be billed" |
| `retrieve-work-order` | **Access Not Available** |
| `download-report` | **Access Not Available** |

A run would have sent a real customer's statement to Signzy and been billable, with nothing to verify
afterwards, and it would have triggered gap 1 on that customer.

**2026-10-16 (earlier):** a real borrower statement was submitted. `analyze-statement` returned 200,
created report `PR1218783855987358`, identified an ICICI savings account and unlocked the PDF. Both
read-back calls returned `403 "You cannot consume this service"`.

The unit test `BankStatementAnalysisMappingTest` covers envelope mapping only.

## Going live

1. Ask Signzy to enable `retrieve-work-order` and `download-report` on the production account (the
   portal's **REQUEST API** button, or your account manager).
2. Confirm both runners in Live mode no longer show "Access Not Available".
3. Deploy the fixes for gaps 1 and 2, including migration V100.
4. Run the test below.
5. Map salary and balances from the real JSON report (gap 6), and ask Signzy for a `templateId` if needed.

### Test procedure

Use one consenting customer with one recent, unmerged statement on file. Log in as a Credit Executive,
Credit Head or ADMIN.

| # | Step | Expected |
|---|---|---|
| 1 | Open Customer → Banking; check the statement is listed | Statement and any password shown |
| 2 | Click **Run analysis**, confirm once | Toast "Statements submitted", status `PENDING` |
| 3 | Try to run again while pending | Button disabled |
| 4 | Leave the tab open | Row advances every 15s or so |
| 5 | Wait for `ANALYSED` | Bank chip, masked account number, transaction count, bank score |
| 6 | Compare with the PDF | Bank, last 4 digits, IFSC, period and transaction count match |
| 7 | Check authenticity | `ORIGINAL`, `REVIEW` or `FRAUD SUSPECTED`; the worst verdict across files is shown |
| 8 | Download Excel and JSON | Both links open |
| 9 | Save the JSON schema with values removed | Input for salary mapping |
| 10 | Inspect `response_json` for the row | Every `password` is `[REDACTED]` |

Negative cases: a wrong PDF password, a merged PDF, and a customer with no statement
(`BSA_NO_STATEMENTS`).

Record results here with no customer name, full account number, amounts or report URLs.

## Where the code lives

| Concern | File |
|---|---|
| Rules and polling | `backend/navix-app/src/main/java/com/navix/app/bsa/BankStatementAnalysisService.java` |
| REST | `backend/navix-app/.../bsa/BankStatementAnalysisController.java` |
| Signzy client | `backend/navix-verification/.../client/SignzyBankStatementClient.java` |
| Schema | `backend/navix-app/src/main/resources/db/migration/V81__bank_statement_analysis.sql` |
| Staff UI | `frontend/src/components/staff/customer-360/banking-tab.tsx` |
| BFF | `frontend/src/app/api/staff/bank-analysis/[[...path]]/route.ts` |
| Config | `navix.signzy.bsa-auth-token`, `SIGNZY_BSA_AUTH_TOKEN`, SSM `/navix/<env>/navix/signzy/bsa-auth-token` |

Further history: the BSA section of [`INTEGRATIONS.md`](INTEGRATIONS.md).
