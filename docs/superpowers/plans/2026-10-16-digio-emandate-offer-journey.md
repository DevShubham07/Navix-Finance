# Digio eMandate Offer-Journey Step — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Register a per-customer Digio NACH eMandate as the final, blocking step of the borrower offer journey, so no file reaches `DISBURSEMENT_PENDING` without a UMRN.

**Architecture:** A `MandatePort` seam in `navix-common` with a Digio adapter in `navix-verification` (forced: `ProviderApiExecutionWriter` is unreachable from `navix-loan`), orchestrated by `MandateService` in `navix-loan`. The new `OFFER_MANDATE` step sits after `OFFER_DISBURSAL_ACCOUNT`; `confirmDisbursalAccount` stops routing onward and the mandate status poll calls `flow.acceptOffer` instead. Registration only — no debit presentation.

**Tech Stack:** Spring Boot 3.4.1 / Java 21, Flyway, Postgres, Next.js 15 / React 19, Vitest.

**Spec:** [`../specs/2026-10-16-digio-emandate-offer-journey-design.md`](../specs/2026-10-16-digio-emandate-offer-journey-design.md)

## Global Constraints

- Money is **integer paise (`long`), HALF_UP, never floats**. Digio's `maximum_amount` is **rupees decimal** — convert once, in the adapter, with `BigDecimal`.
- Mandate parameters are fixed: `auth_mode: "API"`, `management_category: "L001"`, `frequency: "Adhoc"` (write) / `"ADHO"` (read), `is_recurring: true`, `collection_amount` **omitted**.
- `maximum_amount` = **2 × max(`LoanMath.eligibleLimitPaise(salary)`, sanctioned principal)**.
- `first_collection_date` = today IST. `final_collection_date` = today IST + 5 years.
- `customer_ref_number` = `<customer_id>-<attempt_no>`, ≤20 chars, `^[0-9A-Za-z-]+$`. **One `mandate` row per attempt.**
- `customer_name` must match `^[0-9A-Za-z]{1,}[0-9A-Za-z\s.]{0,}$`, max 40 chars. Account number 8–18 **numeric**.
- **Never blind-retry a create** — recover by this attempt's CRN via `GET /v3/client/mandate/by_crn/{crn}`. Digio has no idempotency key.
- Business errors arrive as **HTTP 500 `{code:"SYSTEM_ERROR"}`** — classify on the body, never the status. Always log `details`.
- **UMRN present ⇒ ACTIVE**, whatever the state strings say.
- Store `account_last4` only. Never log full account numbers, `authentication_url`, or the `Authorization` header.
- Credentials + `corporate_config_id` from SSM `/navix/<env>/navix/digio/…`; never in source.
- Flag `digio-mandate`, read with `defaultWhenMissing = FALSE`.
- Migration head is **V81** — the new migration is **V82**.
- Java 21 required. Verify with exit codes, never grepped output. Two `navix-loan` tests already fail on `main` (date rot, stale DigiLocker assert) — do not mistake them for regressions.

## Review Focus

1. **Name with an apostrophe or hyphen** (`O'Brien`, `Anne-Marie`) — Digio 400s on its own pattern; must be sanitised before the call, not after. *(Task 3)*
2. **Create times out after Digio accepted it** — a blind retry files a second real mandate; must recover by CRN. *(Task 3)*
3. **Borrower's bank has `api_mandate: false`** — blocking step with no override strands them; must park for staff, not throw. *(Task 3)*
4. **Existing UMRN holder starts a non-reborrow application** — `applicableOfferSteps` only drops steps when `reappliedFrom != null`, so the naive wiring re-asks them. *(Task 4)*
5. **Digio returns HTTP 500 `SYSTEM_ERROR`** — a client treating 5xx as transient retries an entitlement failure forever. *(Task 1)*

---

### Task 1: Port, Digio client and fail-fast config

**Files:**
- Create: `backend/navix-common/src/main/java/com/navix/common/verification/MandatePort.java`
- Create: `backend/navix-verification/src/main/java/com/navix/verification/config/DigioProperties.java`
- Create: `backend/navix-verification/src/main/java/com/navix/verification/config/DigioConfig.java`
- Create: `backend/navix-verification/src/main/java/com/navix/verification/client/DigioMandateClient.java`
- Create: `backend/navix-verification/src/main/java/com/navix/verification/service/MockMandateAdapter.java`
- Test: `backend/navix-verification/src/test/java/com/navix/verification/config/DigioConfigTest.java`
- Test: `backend/navix-verification/src/test/java/com/navix/verification/client/DigioMandateClientTest.java`

**Interfaces:**
- Produces: `MandatePort.register(MandateRequest) -> MandateSession`, `MandatePort.fetch(String digioMandateId) -> MandateResult`, `MandatePort.findByRef(String customerRefNumber) -> Optional<MandateResult>`, `MandatePort.liveBanks() -> List<BankCapability>`.
  - `record MandateRequest(String customerRefNumber, String customerName, String mobile, String accountNumber, String ifsc, String bankName, long maximumAmountPaise, LocalDate firstCollectionDate, LocalDate finalCollectionDate)`
  - `record MandateSession(String digioMandateId, String state, String authenticationUrl)`
  - `record MandateResult(String digioMandateId, String state, String bankState, String umrn, String authSubMode, String rejectCode, String rejectReason, Instant authenticatedAt)`
  - `record BankCapability(String ifscPrefix, boolean apiMandate, List<String> allowedAuthSubTypes)`

- [ ] **Step 1: Write the failing tests**

`DigioMandateClientTest` (MockRestServiceServer):
```java
@Test void registerSendsFixedMandateParameters() // asserts request JSON has auth_mode "API",
  // mandate_data.management_category "L001", frequency "Adhoc", is_recurring true,
  // maximum_amount 20000 for 2_000_000 paise, and NO collection_amount key
@Test void registerConvertsPaiseToRupeeDecimal()      // 1_234_56L -> 1234.56
@Test void fetchParsesSnakeCaseAndUmrn()              // ifsc_prefix/api_mandate style keys
@Test void fetchReadsFrequencyAsAdho()                // response "ADHO" parses without error
@Test void httpFiveHundredSystemErrorThrowsBusinessExceptionNotRetryable()
  // 500 {"code":"SYSTEM_ERROR","message":"...","details":"EX..."} -> BusinessException
  // carrying code SYSTEM_ERROR; asserts details is logged, not swallowed   [Review Focus 5]
@Test void liveBanksParsesSnakeCaseCapabilities()
```

`DigioConfigTest`:
```java
@Test void refusesToStartWhenEnabledAndClientIdBlank()
@Test void refusesToStartWhenEnabledAndWebhookSecretBlank()  // not used yet; must still be present
@Test void startsWithMockProvider()
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && ./mvnw -pl navix-verification test -Dtest='DigioMandateClientTest,DigioConfigTest'`
Expected: FAIL — classes not found.

- [ ] **Step 3: Implement the port, properties, config and client**

`DigioProperties` is a `@ConfigurationProperties(prefix = "navix.digio")` record: `provider`, `baseUrl`, `clientId`, `clientSecret`, `webhookSecret`, `corporateConfigId`, `narration`, `expireInDays`.

`DigioConfig` mirrors `EsignConfig` exactly — two `@Bean` methods selected by `@ConditionalOnProperty(name = "navix.digio.provider", havingValue = "digio", matchIfMissing = true)` and `havingValue = "mock"`, throwing `IllegalStateException` on any blank required field. Follow `EsignConfig`'s comment style: say *why* it refuses to boot.

`DigioMandateClient` uses `RestClient` with HTTP Basic. `POST /v3/client/mandate/create_form`, `GET /v3/client/mandate/{id}`, `GET /v3/client/mandate/by_crn/{crn}`, `GET /v3/client/mandate/banks?mode=API`. Request/response DTOs are **snake_case** — the published spec's camelCase is wrong. Annotate response records `@JsonIgnoreProperties(ignoreUnknown = true)`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./mvnw -pl navix-verification test -Dtest='DigioMandateClientTest,DigioConfigTest'`; `echo $?` must be 0.

- [ ] **Step 5: Commit**

```bash
git add backend/navix-common backend/navix-verification
git commit -m "feat(digio): MandatePort seam, Digio client and fail-fast config"
```

---

### Task 2: Schema and entity

**Files:**
- Create: `backend/navix-app/src/main/resources/db/migration/V82__mandate.sql`
- Create: `backend/navix-loan/src/main/java/com/navix/loan/entity/Mandate.java`
- Create: `backend/navix-loan/src/main/java/com/navix/loan/repository/MandateRepository.java`
- Test: `backend/navix-loan/src/test/java/com/navix/loan/repository/MandateRepositoryTest.java`

**Interfaces:**
- Produces: `MandateRepository.findFirstByCustomerIdAndUmrnIsNotNullOrderByIdDesc(Long customerId)`, `findFirstByCustomerIdOrderByAttemptNoDesc(Long customerId)`, `findByCustomerRefNumber(String crn)`, `findByDigioMandateId(String id)`.
- `Mandate.Status` enum: `PENDING_AUTHORIZATION`, `ACTIVE`, `AUTHORIZATION_FAILED`, `EXPIRED`, `BANK_UNSUPPORTED`.

- [ ] **Step 1: Write the failing test**

```java
@Test void findsNewestMandateWithUmrnForCustomer()   // two rows, only the newer has a umrn
@Test void customerRefNumberIsUnique()               // duplicate insert violates the constraint
@Test void attemptNoIncrementsPerCustomer()
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw -pl navix-loan test -Dtest=MandateRepositoryTest`
Expected: FAIL — `Mandate` not found.

- [ ] **Step 3: Write V82 and the entity**

Columns per spec §9. `digio_mandate_id`, `customer_ref_number` unique; `umrn` unique where not null (partial index). Indexes on `customer_id`, `loan_application_id`. **No FK constraints** — matches the repo's deliberate debt. Header comment explains why the row is per-attempt, not per-customer.

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && ./mvnw -pl navix-loan test -Dtest=MandateRepositoryTest`; `echo $?` must be 0.

- [ ] **Step 5: Commit**

```bash
git add backend/navix-app/src/main/resources/db/migration/V82__mandate.sql backend/navix-loan
git commit -m "feat(digio): V82 mandate table and entity"
```

---

### Task 3: MandateService — registration, recovery, capability

**Files:**
- Create: `backend/navix-loan/src/main/java/com/navix/loan/service/MandateService.java`
- Test: `backend/navix-loan/src/test/java/com/navix/loan/service/MandateServiceTest.java`

**Interfaces:**
- Consumes: Task 1's `MandatePort`, Task 2's `MandateRepository` / `Mandate.Status`.
- Produces: `MandateService.init(Long appId) -> StepResult` (`derived.url` = authentication url, mirroring `esignInit`), `MandateService.status(Long appId) -> StepResult`, `MandateService.activeMandate(Long customerId) -> Optional<Mandate>`.

- [ ] **Step 1: Write the failing tests**

```java
@Test void ceilingIsTwiceEligibleLimit()                 // salary 40_000_00 -> limit 10_000_00 -> 20_000_00
@Test void ceilingFloorsAtTwiceSanctionedWhenOverrideExceedsFormula()
  // sanctioned 15_000_00 with formula limit 10_000_00 -> 30_000_00, not 20_000_00
@Test void sanitisesApostropheAndHyphenFromCustomerName()   // "O'Brien-Smith" -> "OBrien Smith" [RF 1]
@Test void rejectsAccountNumberShorterThanEightDigits()
@Test void crnIsCustomerIdAndAttemptNumber()                // "4471-1", then "4471-2" on retry
@Test void recoversByCrnInsteadOfCreatingTwiceOnTimeout()
  // port.register throws a timeout; findByRef returns a mandate; asserts register called ONCE
  // and the recovered digio id is persisted                                            [RF 2]
@Test void parksAsBankUnsupportedWhenBankCannotDoApiMandate()
  // liveBanks has api_mandate=false for the ifsc prefix; status is BANK_UNSUPPORTED,
  // no call to port.register                                                           [RF 3]
@Test void statusMarksActiveWhenUmrnPresentDespitePartialState()
  // state "partial" + umrn set -> ACTIVE                       (UMRN beats enums)
@Test void statusStaysPendingWhenNoUmrn()
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && ./mvnw -pl navix-loan test -Dtest=MandateServiceTest`
Expected: FAIL — `MandateService` not found.

- [ ] **Step 3: Implement `MandateService`**

`init` order: resolve capability from `liveBanks()` keyed on the disbursal IFSC's first 4 chars → if `api_mandate` false, persist `BANK_UNSUPPORTED` and return without calling `register`. Otherwise derive amount/dates/CRN, persist `PENDING_AUTHORIZATION`, call `register`. On any exception from `register`, call `findByRef(crn)` before surfacing — adopt the result if present.

Cache `liveBanks()` in-memory keyed by IFSC prefix with a 24-hour expiry; the payload is 88 KB / 305 banks.

- [ ] **Step 4: Run to verify they pass**

Run: `cd backend && ./mvnw -pl navix-loan test -Dtest=MandateServiceTest`; `echo $?` must be 0.

- [ ] **Step 5: Commit**

```bash
git add backend/navix-loan
git commit -m "feat(digio): MandateService with CRN recovery and bank-capability gate"
```

---

### Task 4: Journey wiring and the block

**Files:**
- Modify: `backend/navix-loan/src/main/java/com/navix/loan/service/JourneyService.java` (`OfferStep` enum ~line 75; `applicableOfferSteps` ~line 155; `deriveOffer` ~line 336)
- Modify: `backend/navix-loan/src/main/java/com/navix/loan/service/OfferService.java` (`confirmDisbursalAccount` ~lines 414, 447)
- Modify: `backend/navix-loan/src/main/java/com/navix/loan/controller/OfferController.java`
- Test: `backend/navix-loan/src/test/java/com/navix/loan/service/MandateJourneyTest.java`

**Interfaces:**
- Consumes: Task 3's `MandateService.init/status/activeMandate`.
- Produces: `OfferStep.OFFER_MANDATE("mandate")`; endpoints `POST /api/applications/{id}/offer/mandate/init`, `GET /api/applications/{id}/offer/mandate/status`; error code `MANDATE_REQUIRED`.

- [ ] **Step 1: Write the failing tests**

```java
@Test void confirmDisbursalAccountAdvancesToMandateNotDisbursement()
  // asserts journey pointer is OFFER_MANDATE and flow.acceptOffer was NOT called
@Test void mandateStatusWithUmrnCallsAcceptOffer()      // the only path to DISBURSEMENT_PENDING
@Test void mandateStatusWithoutUmrnDoesNotAcceptOffer()
@Test void deriveOfferReturnsMandateWhenAccountConfirmedAndNoUmrn()
@Test void deriveOfferReturnsDoneWhenCustomerAlreadyHasUmrn()
@Test void stepIsDroppedForExistingUmrnHolderOnNonReborrowApplication()
  // reappliedFrom == null, customer has an ACTIVE mandate -> OFFER_MANDATE absent
  // from applicableOfferSteps                                                          [RF 4]
@Test void stepIsPresentForFirstTimeBorrower()
@Test void flagOffLetsAccountConfirmationRouteStraightToDisbursement()
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && ./mvnw -pl navix-loan test -Dtest=MandateJourneyTest`
Expected: FAIL — `OFFER_MANDATE` undefined.

- [ ] **Step 3: Wire the step**

Add `OFFER_MANDATE("mandate")` **between `OFFER_DISBURSAL_ACCOUNT` and `OFFER_DONE`**.

In `applicableOfferSteps`, drop `OFFER_MANDATE` when `mandateService.activeMandate(app.getCustomerId()).isPresent()` — evaluated **outside** the `reappliedFrom` branch, because that branch only runs for reborrows.

In `deriveOffer`, after the `disbursalConfirmedAt == null` clause: return `OFFER_MANDATE` when the flag is on and the customer has no mandate with a UMRN.

In `confirmDisbursalAccount`, replace **both** `journey.advance(appId, OFFER_DONE); return flow.acceptOffer(...)` sites (the `useBankProof` branch and the penny-drop branch) with: advance to `OFFER_MANDATE` and return when the flag is on; keep the existing behaviour when off. `MandateService.status` calls `flow.acceptOffer` once a UMRN lands.

Controller methods mirror `esign/init` and `esign/status` exactly, including the ownership check.

- [ ] **Step 4: Run to verify they pass**

Run: `cd backend && ./mvnw -pl navix-loan test -Dtest=MandateJourneyTest`; `echo $?` must be 0.
Then: `cd backend && ./mvnw test`; `echo $?` — expect only the two known `navix-loan` failures.

- [ ] **Step 5: Commit**

```bash
git add backend/navix-loan
git commit -m "feat(digio): OFFER_MANDATE step gates disbursement"
```

---

### Task 5: Borrower screen and BFF

**Files:**
- Modify: `frontend/src/lib/offer.ts:13-24` (`OFFER_STEPS`)
- Modify: `frontend/src/lib/domain/journey-steps.ts:30-32`
- Modify: `frontend/src/components/staff/stage-signal.test.tsx:9`
- Create: `frontend/src/app/(borrower)/loan/mandate/page.tsx`
- Create: `frontend/src/app/api/borrower/applications/[[...path]]` route coverage for the two endpoints (verify the existing catch-all already forwards them; add only if not)
- Test: `frontend/src/lib/domain/journey-steps.test.ts` (extend if present)

**Interfaces:**
- Consumes: Task 4's two endpoints.

- [ ] **Step 1: Write the failing test**

```ts
it("counts the mandate step", () => {
  expect(stepInfo("OFFER_MANDATE")).toMatchObject({ journey: "offer", label: "Mandate", n: 11, total: 12 });
  expect(stepInfo("OFFER_ESIGN")).toMatchObject({ journey: "offer", label: "eSign", n: 9, total: 12 });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd frontend && npx vitest run src/components/staff/stage-signal.test.tsx`
Expected: FAIL — `total: 11` received.

- [ ] **Step 3: Add the step and the screen**

`offer.ts`: append `{ seg: "mandate", step: "OFFER_MANDATE", label: "Auto-repayment setup" }`.
`journey-steps.ts`: append `["OFFER_MANDATE", "Mandate"]` after `OFFER_DISBURSAL_ACCOUNT`, and update the existing `total: 11` assertion to `12`.
`/loan/mandate/page.tsx` mirrors the eSign screen: POST init, redirect to `derived.url`, poll status on return, advance on `PASS`. On `BANK_UNSUPPORTED`, show the "we'll be in touch" state rather than a retry button.

**Copy for the ceiling disclosure is a compliance question** — use a placeholder and flag it in the PR, do not invent final wording.

- [ ] **Step 4: Run to verify it passes**

Run: `cd frontend && npx vitest run && npx tsc --noEmit`; `echo $?` must be 0 for both.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "feat(digio): borrower mandate screen and journey step"
```

---

### Task 6: Staff visibility and override

**Files:**
- Modify: `frontend/src/components/staff/customer-360/mandate-tab.tsx`
- Modify: `frontend/src/components/staff/customer-tabs.tsx:173` (re-enable in the strip)
- Modify: `frontend/src/lib/auth/rbac.ts` (add `mandate:override`)
- Modify: `backend/navix-loan/src/main/java/com/navix/loan/controller/OfferController.java` (staff override endpoint)
- Test: `frontend/src/lib/customers/customer-tabs-registry.test.tsx`

**Interfaces:**
- Consumes: Task 3's `MandateService`, Task 2's `Mandate.Status`.
- Produces: `POST /api/staff/applications/{id}/mandate/override` guarded by `mandate:override`.

- [ ] **Step 1: Write the failing test**

```tsx
it("enables the mandate tab", () => { /* asserts the tab is no longer disabled */ });
```
Backend: `@Test void overrideRequiresMandateOverridePermission()` and `@Test void overrideRoutesBankUnsupportedFileToDisbursement()`.

- [ ] **Step 2: Run to verify they fail**

Run: `cd frontend && npx vitest run src/lib/customers/customer-tabs-registry.test.tsx`
Expected: FAIL — tab still disabled.

- [ ] **Step 3: Implement status display and override**

Tab shows status, UMRN, bank, `account_last4`, authorised-at, and reject reason. Override is permitted **only** from `BANK_UNSUPPORTED`, is audited to `application_event`, and calls `flow.acceptOffer`. Grant `mandate:override` to `DISBURSEMENT_HEAD` + `ADMIN`.

- [ ] **Step 4: Run to verify they pass**

Run: `cd frontend && npx vitest run && npx tsc --noEmit`; `cd ../backend && ./mvnw -pl navix-loan test`; `echo $?` each.

- [ ] **Step 5: Commit**

```bash
git add frontend/src backend/navix-loan
git commit -m "feat(digio): staff mandate tab and bank-unsupported override"
```

---

### Task 7: Configuration, flag and documentation

**Files:**
- Modify: `backend/.env.example`
- Modify: `backend/navix-app/src/main/resources/application.yml`
- Create: `backend/navix-app/src/main/resources/db/migration/V83__digio_mandate_flag.sql`
- Modify: `CLAUDE.md` (§12 feature-flag list, §12 env-var list)
- Modify: `docs/digio/README.md` (link the spec and this plan)

- [ ] **Step 1: Add config and flag**

`.env.example` and `application.yml` get the `NAVIX_DIGIO_*` keys from spec §4 with `NAVIX_DIGIO_PROVIDER=mock` and dummy values, so a local run and CI boot without credentials — the `NAVIX_ESIGN_PROVIDER=mock` precedent.

V83 inserts `digio-mandate` **disabled**.

- [ ] **Step 2: Verify a clean boot with the mock provider**

Run: `cd backend && ./mvnw -pl navix-app -Pit test`; `echo $?` must be 0.

- [ ] **Step 3: Update CLAUDE.md**

Add `digio-mandate` to the live-flags list noting `defaultWhenMissing = FALSE` and why (money-affecting rail). Add `NAVIX_DIGIO_*` to the secrets list. Correct the stale "V1..V78" to the current head.

- [ ] **Step 4: Commit**

```bash
git add backend CLAUDE.md docs/digio/README.md
git commit -m "feat(digio): config, feature flag and docs"
```

---

## Notes for the executor

- **Nothing in this plan may call production Digio.** There is no sandbox (`docs/digio/11-` Q3); `MockMandateAdapter` is the test seam. A real mandate is a legally binding NPCI filing.
- The published OpenAPI specs in `docs/digio/api-reference/schemas/` are right about paths and enum **values** but wrong about field casing, required-ness and masking — see `docs/digio/13-` before writing any DTO.
- Webhooks are deliberately absent (spec §8). Do not add a receiver; API mandates return the UMRN in-session.
