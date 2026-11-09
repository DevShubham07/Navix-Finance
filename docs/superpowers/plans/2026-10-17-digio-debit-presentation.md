# Digio NACH Debit Presentation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Collect repayment by presenting NACH debits against registered mandates — on a schedule at DPD 1/3/5/9, and manually by collections staff.

**Architecture:** Extend the existing `MandatePort` seam with the three debit operations, back them in `DigioMandateClient`, and add a `DebitService` in `navix-loan` that owns every guard. A scheduled sweep in `navix-app` presents before the T-1 cutoff and reconciles in-flight debits by polling. Collections reaches the service through a `navix-common` directory, as `CollectionPaymentService` already reaches loans. A successful debit is credited through the existing `LoanDirectory.creditCollectionPayment`, which already records verified in one step.

**Tech Stack:** Spring Boot 3.4.1 / Java 21, Flyway, Postgres, Next.js 15 / React 19, Vitest.

**Spec:** [`../specs/2026-10-17-digio-debit-presentation-design.md`](../specs/2026-10-17-digio-debit-presentation-design.md)

## Global Constraints

- Money is **integer paise (`long`), HALF_UP, never floats**. Use Digio's **`amount_in_paise`** (`int32`), never the rupee `amount` field.
- Debit request fields are fixed: `frequency: "adhoc"` (lowercase on the debit API), `payment_count: 1`.
- **Request by late night T-1**; the file is built at 00:00 on T; NPCI holidays auto-reschedule; the outcome arrives by EOD on the settlement date.
- **DPD is counted from the due date** (not the end of grace), via `DpdCalculator.daysPastDue(dueDate, asOf)`. Attempts at **DPD 1, 3, 5, 9**.
- **Re-presentation is gated on the previous reject code**, never the attempt count. An unrecognised code is **terminal**.
- A successful debit is credited via `LoanDirectory.creditCollectionPayment(loanId, amountPaise, txnRef, proofRef, paidOn)` with `txnRef` = the **UTR**. It records verified in one step — that is the auto-verification decision, already precedented.
- **Never blind-retry a create.** Recover by `unique_key` via `GET /v3/client/nach_debit/scheduled/by_reference_id/{refId}`. Digio has no idempotency key.
- **The `mandate_debit` row commits before the Digio POST.**
- Scheduled and manual attempts use **different `unique_key` namespaces**.
- Dates are IST (`ZoneId.of("Asia/Kolkata")`); a bare `LocalDate.now()` is a bug.
- Store `account_last4` only. Never log a full account number, `authentication_url`, or a credential.
- Feature flag **`digio-debit`**, shipped disabled, read with `defaultWhenMissing = FALSE`.
- Migration head is **V83** — this plan adds **V84** and **V85**.
- `DSA` is explicitly rejected on every new endpoint.
- Java 21. Verify with exit codes, never grepped output.

## Review Focus

1. **Two debits for one repayment** — the sweep and a staff click racing, or two ECS tasks running the sweep. The database must refuse the second. *(Task 3)*
2. **A terminal rejection re-presented** — account closed, mandate cancelled, amount over ceiling. Burns the NPCI monthly allowance and cannot succeed. *(Task 3)*
3. **A debit landing after the borrower already repaid** — `creditCollectionPayment` on a settled loan. Money has moved; it must become a flagged overpayment, not an exception swallowed. *(Task 3)*
4. **An amount above the mandate ceiling or above outstanding** — rejected by NPCI as "amount exceeds mandate max"; must be refused before the call. *(Task 3)*
5. **A settlement date outside the mandate's collection window** — rejected as "date before/after mandate start/end date". *(Task 3)*

---

### Task 1: Debit operations on the port and client

**Files:**
- Modify: `backend/navix-common/src/main/java/com/navix/common/verification/MandatePort.java`
- Modify: `backend/navix-verification/src/main/java/com/navix/verification/client/DigioMandateClient.java`
- Modify: `backend/navix-verification/src/main/java/com/navix/verification/service/MockMandateAdapter.java`
- Test: `backend/navix-verification/src/test/java/com/navix/verification/client/DigioMandateClientTest.java`

**Interfaces:**
- Produces:
  - `MandatePort.presentDebit(DebitRequest) -> DebitPlan`
  - `MandatePort.debitStatus(String txnId) -> DebitOutcome`
  - `MandatePort.cancelDebit(String txnId) -> boolean`
  - `record DebitRequest(String umrn, long amountPaise, LocalDate settlementDate, String uniqueKey, String corporateAccountNumber, String narration)`
  - `record DebitPlan(String planId, String status, String txnId, LocalDate scheduledSettlementDate)`
  - `record DebitOutcome(String txnId, String status, String utr, String failureCode, String failureReason, long amountPaise)`

- [ ] **Step 1: Write the failing tests** (MockRestServiceServer, as the existing tests do)

```java
@Test void presentDebitSendsAmountInPaiseAndAdhocFrequency()
  // asserts request JSON has amount_in_paise (int, not rupees), frequency "adhoc",
  // payment_count 1, umrn, unique_key, settlement_date; asserts NO "amount" key
@Test void presentDebitParsesPlanAndTransactionId()
@Test void debitStatusParsesUtrAndFailureCode()      // snake_case: failure_code, failure_reason, utr
@Test void debitBusinessErrorOnFiveHundredIsNotRetryable()
  // 500 {"code":"SYSTEM_ERROR",...} -> BusinessException carrying SYSTEM_ERROR; details logged
@Test void cancelDebitReturnsFalseWhenAlreadyInitiated()
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && ./mvnw -pl navix-verification test -Dtest=DigioMandateClientTest`
Expected: FAIL — methods not defined.

- [ ] **Step 3: Implement the three operations**

Endpoints: `POST /v3/client/nach_debit/scheduled/register`, `GET /v3/client/nach_debit/transaction/{txnId}`, `POST /v3/client/nach_debit/transaction/action` (`action: "cancel"`). Schemas are in [`docs/digio/03-digio-api-catalog.md`](../../digio/03-digio-api-catalog.md) §C and the mirrored specs. Responses are **snake_case** — do not trust the published spec's casing. `MockMandateAdapter` must refuse to serve in prod, as its existing methods do.

- [ ] **Step 4: Run to verify they pass**

Run: `cd backend && ./mvnw -pl navix-verification test -Dtest=DigioMandateClientTest`; `echo $?` must be 0.

- [ ] **Step 5: Commit**

```bash
git add backend/navix-common backend/navix-verification
git commit -m "feat(digio): debit presentation on the mandate port and client"
```

---

### Task 2: `mandate_debit` schema and entity

**Files:**
- Create: `backend/navix-app/src/main/resources/db/migration/V84__mandate_debit.sql`
- Create: `backend/navix-loan/src/main/java/com/navix/loan/entity/MandateDebit.java`
- Create: `backend/navix-loan/src/main/java/com/navix/loan/repository/MandateDebitRepository.java`
- Test: `backend/navix-app/src/test/java/com/navix/app/loan/MandateDebitRepositoryIT.java`

**Interfaces:**
- Produces: `MandateDebitRepository.findByUniqueKey(String)`, `findFirstByLoanIdOrderByAttemptNoDesc(Long)`, `findByStatusIn(Collection<Status>)`, `existsByLoanIdAndStatusIn(Long, Collection<Status>)`.
- `MandateDebit.Status`: `SCHEDULED`, `IN_PROGRESS`, `SUCCEEDED`, `FAILED`, `FAILED_TECHNICAL`, `CANCELLED`, `SKIPPED`.

- [ ] **Step 1: Write the failing test**

```java
@Test void partialUniqueIndexRefusesASecondInFlightDebitForOneLoan()
  // insert SCHEDULED for loan 1; a second SCHEDULED for loan 1 violates the constraint;
  // a second for loan 2 succeeds; a SUCCEEDED + a new SCHEDULED for loan 1 succeeds
@Test void uniqueKeyIsUnique()
@Test void findsNewestAttemptForALoan()
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw -pl navix-app -Pit test -Dtest=MandateDebitRepositoryIT` (needs Docker; if unavailable, say so and do not claim a pass)
Expected: FAIL — relation does not exist.

- [ ] **Step 3: Write V84 and the entity**

Columns per spec §10 / [`docs/digio/08-` §5](../../digio/08-navix-integration-architecture.md): `id`, `mandate_id`, `loan_id`, `payment_id`, `digio_plan_id`, `digio_txn_id`, `unique_key` (unique), `amount_paise` (BIGINT), `settlement_date`, `status`, `digio_status`, `failure_code`, `failure_reason`, `utr`, `attempt_no`, `dpd_at_attempt`, `triggered_by` (`SCHEDULED`/`MANUAL`), `actor_id`, `overpayment` (boolean), `created_at`, `updated_at`.

**The partial unique index is the double-debit guard** — `create unique index … on mandate_debit(loan_id) where status in ('SCHEDULED','IN_PROGRESS')`. No FK constraints; indexes only. Header comment explains why the index exists.

- [ ] **Step 4: Run to verify it passes**

Run as Step 2; `echo $?` must be 0.

- [ ] **Step 5: Commit**

```bash
git add backend/navix-app/src/main/resources/db/migration/V84__mandate_debit.sql backend/navix-loan
git commit -m "feat(digio): V84 mandate_debit table with the in-flight guard"
```

---

### Task 3: `DebitService` — guards, presentation, outcomes

**Files:**
- Create: `backend/navix-loan/src/main/java/com/navix/loan/service/DebitService.java`
- Modify: `backend/navix-loan/src/main/java/com/navix/loan/service/MandateService.java` (expose a loan-keyed usable-mandate accessor)
- Test: `backend/navix-loan/src/test/java/com/navix/loan/service/DebitServiceTest.java`

**Interfaces:**
- Consumes: Task 1's port methods; Task 2's repository; `LoanDirectory.findLoan`, `creditCollectionPayment`; `RepaymentService.outstandingAsOf`; `DpdCalculator.daysPastDue`; `MandateService`'s usable-mandate rule.
- Produces: `DebitService.present(Long loanId, Long amountPaise, Trigger trigger, Long actorId) -> DebitView` (null `amountPaise` = full outstanding), `DebitService.reconcile(Long debitId) -> DebitView`, `DebitService.eligibleForRepresentation(MandateDebit) -> boolean`.

- [ ] **Step 1: Write the failing tests**

```java
@Test void defaultsToFullOutstandingAsOfToday()
@Test void refusesAnAmountAboveOutstanding()
@Test void refusesAnAmountAboveTheMandateCeiling()                       // [RF 4]
@Test void refusesASettlementDateOutsideTheMandateWindow()               // [RF 5]
@Test void refusesWhenNoUsableMandateExists()
@Test void refusesWhenADebitIsAlreadyInFlightForTheLoan()                // [RF 1]
@Test void takesTheLoanAdvisoryLockBeforeCheckingInFlight()              // InOrder
@Test void commitsTheRowBeforeCallingTheProvider()                       // InOrder
@Test void recoversByUniqueKeyInsteadOfPresentingTwiceOnTimeout()        // [RF 1]
@Test void scheduledAndManualUniqueKeysDoNotCollide()
@Test void successCreditsThroughLoanDirectoryWithTheUtrAsTxnRef()
@Test void successOnASettledLoanIsParkedAsAFlaggedOverpayment()          // [RF 3]
@Test void insufficientBalanceIsEligibleForRepresentation()
@Test void accountClosedIsTerminalAndNotRepresented()                    // [RF 2]
@Test void unknownRejectCodeIsTreatedAsTerminal()                        // [RF 2]
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && ./mvnw -pl navix-loan test -Dtest=DebitServiceTest`
Expected: FAIL — `DebitService` not found.

- [ ] **Step 3: Implement `DebitService`**

Order inside `present`: advisory lock on the loan (`pg_advisory_xact_lock`, as `MandateService` already does on the customer) → in-flight check → resolve loan → application → usable mandate → derive amount and settlement date → validate against outstanding, ceiling and mandate window → **persist and commit** the row → call the port → store plan and txn ids.

`reconcile` reads `debitStatus`, maps the outcome, and on success calls `creditCollectionPayment`. If the loan has settled meanwhile, record the row with `overpayment = true` rather than letting the exception escape.

Re-presentation eligibility reads the reject code against the Return/Reject tables in [`docs/digio/06-` §5](../../digio/06-errors-retries-and-edge-cases.md); unknown ⇒ terminal.

- [ ] **Step 4: Run to verify they pass**

Run: `cd backend && ./mvnw -pl navix-loan test -Dtest=DebitServiceTest`; `echo $?` must be 0.

- [ ] **Step 5: Commit**

```bash
git add backend/navix-loan
git commit -m "feat(digio): DebitService with guards, recovery and outcome handling"
```

---

### Task 4: Scheduled sweep

**Files:**
- Create: `backend/navix-app/src/main/java/com/navix/scheduler/MandateDebitScheduler.java`
- Modify: `backend/navix-app/src/main/resources/application.yml`, `backend/.env.example`
- Test: `backend/navix-app/src/test/java/com/navix/scheduler/MandateDebitSchedulerTest.java`

**Interfaces:**
- Consumes: Task 3's `present` and `reconcile`; `LoanDirectory.listCollectible(LocalDate)`; `DpdCalculator`.
- Produces: cron property `NAVIX_DIGIO_DEBIT_CRON`.

- [ ] **Step 1: Write the failing tests**

```java
@Test void presentsAtDpdOneThreeFiveAndNine()
@Test void doesNotPresentAtOtherDpdValues()                  // e.g. 2, 4, 6, 10
@Test void doesNotPresentWhenTheFlagIsOff()
@Test void skipsALoanWithATerminalPreviousRejection()        // [RF 2]
@Test void catchesUpAMissedAttemptDayWhileStillEligible()
@Test void reconcilesInFlightDebitsPastTheirSettlementDate()
@Test void oneFailingLoanDoesNotStopTheSweep()
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && ./mvnw -pl navix-app test -Dtest=MandateDebitSchedulerTest`
Expected: FAIL — class not found.

- [ ] **Step 3: Implement the sweep**

Follow `PaymentReminderScheduler` for shape and cron-from-env. Default the cron to run **before the T-1 cutoff** in IST, and say in a comment why that time was chosen. Per loan, catch and log; never let one borrower's failure abort the run.

- [ ] **Step 4: Run to verify they pass**

Run as Step 2; `echo $?` must be 0.

- [ ] **Step 5: Commit**

```bash
git add backend/navix-app backend/.env.example
git commit -m "feat(digio): scheduled debit sweep at DPD 1/3/5/9"
```

---

### Task 5: Collections seam, endpoint and staff action

**Files:**
- Create: `backend/navix-common/src/main/java/com/navix/common/loan/DebitDirectory.java`
- Create: `backend/navix-loan/src/main/java/com/navix/loan/service/DebitDirectoryAdapter.java`
- Modify: `backend/navix-loan/src/main/java/com/navix/loan/controller/ApplicationController.java` (or the staff controller the Mandate tab already calls)
- Modify: `frontend/src/components/staff/customer-360/mandate-tab.tsx`
- Modify: `frontend/src/lib/auth/rbac.ts`, `frontend/src/lib/api/applications.ts`
- Test: `backend/navix-loan/src/test/java/com/navix/loan/service/DebitAuthorizationTest.java`, `frontend/src/lib/auth/rbac.test.ts`

**Interfaces:**
- Consumes: Task 3's `DebitService.present`.
- Produces: `POST /api/staff/loans/{loanId}/debit` guarded by `mandate:debit`; `DebitDirectory.presentForCase(...)` in `navix-common`.

- [ ] **Step 1: Write the failing tests**

```java
@Test void collectionExecutiveMayDebitOnlyTheirOwnAssignedCase()   // else CASE_NOT_ASSIGNED
@Test void collectionHeadMayDebitAnyCase()
@Test void otherRolesAreRefusedWithForbiddenRole()                 // loop all 9 live roles
@Test void dsaIsRejected()
@Test void everyAttemptIsAudited()                                  // actor, amount, trigger
```
```ts
it("grants mandate:debit to COLLECTION_EXECUTIVE, COLLECTION_HEAD and ADMIN only", …)
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && ./mvnw -pl navix-loan test -Dtest=DebitAuthorizationTest` and `cd frontend && npx vitest run src/lib/auth/rbac.test.ts`

- [ ] **Step 3: Implement the seam, endpoint and action**

`navix-collections` depends only on `navix-common`, so the directory interface lives there and the adapter in `navix-loan` — the `LoanDirectory`/`LoanDirectoryAdapter` pattern.

The Mandate tab gains a debit action showing **the amount and the account's last four digits in a confirmation step before firing** — this moves real money. Default the amount to full outstanding, allow override downward only.

- [ ] **Step 4: Run to verify they pass**

Run both commands from Step 2 plus `npx tsc --noEmit`; `echo $?` must be 0 for each.

- [ ] **Step 5: Commit**

```bash
git add backend/navix-common backend/navix-loan frontend/src
git commit -m "feat(digio): manual debit for collections staff"
```

---

### Task 6: Feature flag and documentation

**Files:**
- Create: `backend/navix-app/src/main/resources/db/migration/V85__digio_debit_flag.sql`
- Modify: `CLAUDE.md`, `docs/MIGRATIONS.md`, `docs/digio/README.md`

- [ ] **Step 1: Add the flag**

`digio-debit`, inserted **disabled**, matching V83's shape and header style.

- [ ] **Step 2: Update the documentation**

CLAUDE.md: the flag in §12's live-flags list (noting `defaultWhenMissing = FALSE` and that it moves money), `mandate:debit` in §7's permission tokens, `NAVIX_DIGIO_DEBIT_CRON` in the env list, and the migration range to V85. Describe **only what was built** — no webhook receiver exists, and the outcome path is polling.

- [ ] **Step 3: Verify**

Run: `cd backend && ./mvnw -pl navix-app -Pit test` (needs Docker; report honestly if unavailable); `echo $?`.

- [ ] **Step 4: Commit**

```bash
git add backend CLAUDE.md docs
git commit -m "feat(digio): debit feature flag and docs"
```

---

## Notes for the executor

- **Nothing in this plan may call production Digio.** There is no sandbox, and every debit moves real money from a real borrower's account. `MockMandateAdapter` is the test seam.
- The live API returns **snake_case** and uses **HTTP 500** for business rejections — see `docs/digio/13-live-verification-log.md` before writing any DTO.
- Two `navix-loan` tests historically fail on `main` (date rot, stale DigiLocker assert); they passed in recent runs. Report them separately from anything you cause.
