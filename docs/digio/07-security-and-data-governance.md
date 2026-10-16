# 07 — Security, compliance and data handling

## 1. Credentials

| Item | Handling |
|---|---|
| `client_id` / `client_secret` | HTTP Basic (`04-`). **SSM SecureString `/navix/<env>/navix/digio/…`**, as every other provider's are. Never in source, never in a task-def env var, never logged |
| Webhook secret | SSM SecureString. Account-level; differs between sandbox and production |
| `corporate_config_id` | Account configuration, **not a credential** — useless without the client secret. Still belongs in SSM with the rest, not hardcoded |
| Rotation | **UNKNOWN.** No self-serve rotation UI and no documented procedure for the API credentials. The webhook secret *is* rotatable from profile → Webhooks. `11-` Q1 |
| Separation | Sandbox and production are fully independent (`04-`). A sandbox credential must never reach a production config path, and the reverse must be impossible |
| MFA on the console | **Not enabled** on `info@dhanboost.com` (OBSERVED). The console can create, amend and cancel real mandates — enabling 2FA is a cheap, obvious control |

**No credential value was read, copied or recorded during this exploration**, and no value appears
anywhere in this knowledge base.

## 2. Sensitive data in the request/response surface

A mandate carries more bank data than anything Navix sends a provider today.

| Field | Sensitivity | Note |
|---|---|---|
| `customer_account_number` | **High** — full account number, 8–18 digits | Sent in the request body (over TLS). ⚠ **Returned IN FULL, not masked** — the spec claims masking, a live `A3` read returned all 16 digits ([`13-`](13-live-verification-log.md) §9). Our own redaction is the only control |
| `destination_bank_id` (IFSC/MICR) | Medium | |
| `customer_name`, `customer_mobile`, `customer_email` | PII | |
| Aadhaar | **Highest** | Not a field we send. But `auth_mode: ESIGN` and the Aadhaar sub-mode route the borrower through an Aadhaar journey, and `AP48`/`AP51` reveal whether their Aadhaar is linked to that account. Treat that signal as Aadhaar-adjacent data |
| `umrn` | Medium | The durable handle for a live debit authority. Leaking it does not permit a debit (that needs our credentials) but it is a strong customer identifier |
| `access_token` | **High, short-lived** | Lets a device skip first-factor auth in the SDK. Must reach **only** the borrower's own session, never logs, never a staff screen, never an analytics event |

**Logging rules that follow.** Log `mandate_id`/`ENA…`, `NSP…`, `NPT…`, state, reject code and the
`details` exception id — those are what support investigations need. **Never** log a request body
containing `customer_account_number`, never log `access_token` or `authentication_url` (it carries the
token), never log the `Authorization` header. The repo's existing `ProviderApiExecutionWriter` records
provider calls — any Digio traffic routed through it must be redacted before it is written, or we
build a searchable table of customers' bank account numbers.

## 3. Consent and borrower-facing obligations

- A mandate is a **standing authority to debit a customer's bank account**. It is a legal act, closer
  to Navix's eSign than to a verification check — which is why `08-` keeps it out of
  `VerificationPort` (`00-` §3).
- The borrower must understand, before authorising: who debits, the **maximum amount per
  instruction**, the frequency, the start and end dates, and how to cancel. A `MAXIMUM`-type mandate
  with generous headroom (`06-` §5) is operationally right and makes that disclosure **more**
  important, not less — the borrower is authorising a ceiling, not the expected figure.
- **VERIFIED, and material:** for UPI AutoPay, Digio documents that lending mandates **cannot be
  cancelled or revoked by the borrower**. NACH has no such protection and `C001` ("cancellation on
  customer request") exists. Either way, Navix must provide its own cancellation path and honour it —
  a borrower who has repaid and closed their loan should not be left with a live debit authority.
- **The Key Fact Statement must say so.** Navix already renders a KFS and takes a fresh eSign per
  advance (`SanctionLetterPdfRenderer`). If a mandate is taken, its terms belong in that disclosure.
  **Flag for compliance** — `11-` Q14.

## 4. Compliance questions — not answerable from a product feature

Recorded as questions precisely because a documented capability is not evidence of regulatory
coverage. None of these were confirmed by any source in this exploration:

- Which NPCI **management category** our utility code `NACH00000000067052` is actually approved for.
  Sending `L001` when NPCI enabled something else fails at the NPCI gateway (`156`/`155`), and the
  console's category list is not proof of entitlement. **`11-` Q5.**
- Whether Navix's lending licence and its agreement with **Yes Bank** as sponsor bank cover
  salary-advance collections at this ticket size and frequency.
- Mandate amount ceiling: the API caps `collection_amount`/`maximum_amount` at **₹1,00,00,000** per
  NACH circular `NACH/2023-24/NACH/003` (15 Sep 2023). Far above Navix's ticket size — not a
  constraint, but the citation belongs on file.
- **Data retention**: how long Digio holds mandate images, bank details and PKI signature details.
  **UNKNOWN** — nothing documented. Ask Digio.
- **Deletion**: whether a customer erasure request can be honoured for data held at Digio, and what
  survives at NPCI and the sponsor bank regardless. **UNKNOWN.**
- DPDP Act obligations for sharing bank account data with a new processor, and whether Digio is
  covered by an existing DPA. *(Digio markets a consent platform, "CoTrust" — their having one says
  nothing about our obligations.)*

## 5. Audit

Navix's existing posture should extend unchanged:

- Every mandate state change appended to an append-only trail, as `application_event` is for the loan
  aggregate — who/what caused it, when, from which webhook or reconciliation read.
- Every debit presentation attributable to a staff actor or a named scheduled job, with the
  `unique_key` recorded, so "why was this customer debited" has a single answer.
- Maker-checker: CLAUDE.md's standing rule is that *"wherever an actor can approve, the counter-action
  reject exists too, SoD-checked and audited."* A manually-triggered debit is an actor moving a
  borrower's money. It must sit behind a role, be audited, and have a cancel path (`C4`) — `08-` §7.
- Least privilege on the Digio side: Digio supports **sub-users**, and `sub_user_identifier` scopes
  mandates and debits to one. Worth using rather than every staff action landing under one shared
  corporate login.

## 6. What was deliberately not done

For the record, since a later reader may wonder why the evidence stops where it does:

- No mandate created, amended, cancelled, shared or debited. The New Mandate form was **opened and
  never submitted**; "Configure Webhook" was **not** clicked.
- No API call made against `api.digio.in` or `ext.digio.in` — every endpoint claim comes from the
  published specs.
- No credential, cookie, session token or `Authorization` header read, copied or stored.
- No customer PII extracted; the Mandate Book was empty in any case.
- No authentication, CAPTCHA, MFA or tenant boundary probed or bypassed.
