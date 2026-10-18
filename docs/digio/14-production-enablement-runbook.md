# Digio eMandate + NACH debit — production enablement runbook

**Status as of 2026-10-18:** steps 1-4 are DONE on the live stack. All five Digio parameters are in
SSM under `/navix/dev/navix/digio/`, `provider=digio`, the service restarted onto task-def revision
169, and the **`digio-mandate` flag is ON** — borrowers now see the eMandate step. `digio-debit` is
still OFF, so no debit is presented and none ever has been.

Remaining: confirm a real registration (§3 "Confirming which adapter is actually serving"), then
steps 5 and 6.

This file is the ordered list of things that must happen to change that, and what each one
proves. Steps 1–3 are configuration; steps 4–6 are the only way the debit path gets verified,
because **there is no Digio sandbox** and no debit has ever been presented.

> Credential **values** are deliberately not in this file (CLAUDE.md §12 — secrets never
> committed). The Client Id / Client Secret / corporate config id for the live DigiCollect account
> are in the operator's own records; paste them into the commands below.

---

## What is proven, and what is not

| | Evidence |
|---|---|
| ✅ Mandate registration works end to end | One real mandate, UMRN `PUNB7031610261030169`, authorised via Aadhaar, UMRN issued in ~7 minutes (`13-live-verification-log.md`) |
| ❌ Debit presentation | **Never executed.** Every test mocks `MandatePort`. The response shapes, the `failure_code` vocabulary, and whether Digio dedupes a repeated `unique_key` are all unobserved |
| ❌ Webhooks | No receiver exists. Outcomes come from the polling reconcile sweep, 50 rows per pass |
| ❌ UPI AutoPay | Not provisioned — needs its own corporate config id |

---

## 1. Credentials into SSM

Four parameters are required for the app to start with `provider=digio`
(`DigioConfig.require`): `client-id`, `client-secret`, `webhook-secret`, `corporate-config-id`.

```bash
# Run these yourself — writing secrets is not something an agent should do for you.
for k in client-id client-secret corporate-config-id; do
  read -rs -p "value for $k: " v; echo
  AWS_PROFILE=navix-dev aws ssm put-parameter \
    --name "/navix/dev/navix/digio/$k" --type SecureString --value "$v" --overwrite
done

# webhook-secret guards a receiver that does not exist yet, so it is OURS to choose. Generate it;
# do not leave it blank (the app refuses to start) and do not reuse another service's secret.
AWS_PROFILE=navix-dev aws ssm put-parameter \
  --name /navix/dev/navix/digio/webhook-secret --type SecureString \
  --value "$(openssl rand -hex 32)" --overwrite
```

## 2. The settlement account — the one value nobody can guess

`NAVIX_DIGIO_CORPORATE_ACCOUNT_NUMBER` is **the lender's own bank account that every NACH debit
credits**. It is not a Digio value and it is not in any document here. Get it from finance.

Unset, every presentation refuses `DEBIT_NOT_CONFIGURED` and the sweep stops with one ERROR — so
this cannot silently misroute money, but a **wrong** number would. Check it digit by digit.

```bash
AWS_PROFILE=navix-dev aws ssm put-parameter \
  --name /navix/dev/navix/digio/corporate-account-number --type SecureString \
  --value "<the lender's settlement account>" --overwrite
```

## 3. Flip the provider — an SSM parameter, NOT a task-definition change

**There is nothing to change on the task definition.** `application.yml` imports the whole
parameter subtree:

```yaml
spring.config.import: optional:aws-parameterstore:/navix/${NAVIX_ENV:dev}/
```

so `/navix/dev/navix/digio/client-id` binds straight to `navix.digio.client-id`, and an imported
parameter outranks the `${NAVIX_DIGIO_CLIENT_ID:}` default in `application.yml`. That is already how
`spring.datasource.password` and the mandatory `navix.esign.callback-url` reach the app — the live
task definition carries **zero** `secrets` entries and only two env vars (`NAVIX_ENV`, `AWS_REGION`).

An earlier draft of this file had you clone the task definition and add five `secrets` mappings. That
was wrong and strictly worse: it is redundant, and registering a new revision is the step `aws.md`
warns can silently drop env the live one carried.

Set the provider as a parameter and restart on the **same** revision:

```bash
export AWS_PROFILE=navix-dev
aws ssm put-parameter --name /navix/dev/navix/digio/provider \
  --type String --overwrite --value 'digio'

aws ecs update-service --cluster navix-cluster --service navix-backend --force-new-deployment
aws ecs wait services-stable --cluster navix-cluster --services navix-backend
```

**SSM is read at startup only**, so set every parameter — including the settlement account — *before*
this restart, or you will need another one.

If the service does not go stable, the app refused to boot: `DigioConfig.require` throws on a blank
credential by design. Look for `must be set when navix.digio.provider=digio` in
`aws logs tail /ecs/navix-finance`. The old tasks keep serving while it rolls back.

### Confirming which adapter is actually serving

`management.endpoints.web.exposure.include` is `health,info`, so there is no `/actuator/env` to read
the live value from, and **both providers boot cleanly** — a clean start is not proof. The first
registration tells you unambiguously:

| | `provider=digio` | mock still serving |
|---|---|---|
| Auth URL | on `digio.in` | `https://mock.digio.invalid/auth` |
| `mandate.digio_mandate_id` | `ENA26…` | `ENAMOCK<customerId>-<attempt>` |
| UMRN | minutes after the borrower authorises | instantly, prefixed `MOCK` |

The borrower cannot be silently served a fake: the mandate page refuses to navigate anywhere but
`digio.in` (`isDigioUrl`), so a mock URL surfaces as "We could not open your bank's page".

⚠️ `MockMandateAdapter.refuseInProd()` keys off `NAVIX_ENV`, which is **`dev`** on this stack even
though it is the live environment. That safety net is therefore **not armed here** — the mandate-id
prefix above is the real tell.

## 4. Enable mandate registration for a small allowlist

```sql
update feature_flag set enabled = true where flag_key = 'digio-mandate';
```

There is no per-customer flag, so "allowlist" means **enable it, watch the first few borrowers,
and be ready to switch it back off**. Each registration is a real, legally binding NPCI mandate;
switching the flag off afterwards does not unregister anything already filed.

Watch for: UMRNs actually landing (`mandate.umrn` non-null), `BANK_UNSUPPORTED` volume (an IFSC
Digio does not serve parks the borrower for a staff override), and `MANDATE_BANKS_UNAVAILABLE`.

## 5. Present ONE debit by hand — the step that cannot be skipped

**On a staff member's own loan and own bank account**, from Staff → Customer → Mandate tab
("Debit attempts" shows the outcome), or:

```
POST /api/collections/loans/{loanId}/debit   { "amountPaise": 100 }
```

This single transaction is what settles everything the integration still guesses at:

1. **The real `failure_code` / `failure_reason` strings.** `DebitService.TERMINAL_MARKERS` is our
   reading of the NPCI return-reason list, not observed values. Compare the first real failure
   against it. Unlike the allowlist it replaced, a gap here costs one pointless re-presentation
   instead of silently retiring the loan, but it still wants checking.
2. **Whether a repeated `unique_key` is deduped by Digio.** That is the double-charge backstop on
   the `FAILED_TECHNICAL` recovery path, and it is an assumption.
3. **The create/status response shapes** — `txnId` nullability, the `by_crn` 404 body.

Then reconcile it (the sweep runs at 10:00 IST, `NAVIX_DIGIO_DEBIT_CRON`) and confirm the payment
was credited VERIFIED with its UTR.

## 6. Only then, enable the scheduled sweep

```sql
update feature_flag set enabled = true where flag_key = 'digio-debit';
```

Presentations fire at DPD 1, 3, 5 and 9. Check **Staff → Collections → Refunds owed** in the first
week: a non-empty register means money was taken from borrowers who had already repaid and has to
be returned by hand — there is no automated refund.

---

## Not blockers, but known gaps at go-live

- **No webhook receiver.** Outcomes lag by up to a reconcile pass, and the pass is capped at 50
  rows — above ~50 open debits a day, the queue rotates rather than clears (`updated_at` on an
  open row means "last attempted", not "last status change").
- **No mandate amendment or cancellation.** A borrower who wants their mandate cancelled does it
  at their bank; we only observe it (`revoked` → `AUTHORIZATION_FAILED`).
- **Compliance has not signed the borrower-facing ceiling copy** on `/loan/mandate`. The mandate
  ceiling is 2 × max(eligible limit, sanctioned amount); the screen does not state a figure.
- **`ApplicationFlowIntegrationTest` has not run on this machine.** Colima forwards explicitly
  published ports but not Testcontainers' dynamic range, so every container connection is refused
  at `127.0.0.1:328xx`. Unit suites are green (navix-loan 913, navix-app 217, frontend 870); the
  integration test needs an environment where Docker port forwarding works.
