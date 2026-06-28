# NAVIX — Notification Infrastructure Plan (in-app + SMS + email)

> Authored 2026-06-28. Foundational, extensible notification infra for NAVIX Finance. Every file
> path, line number, method signature, and migration number below was **verified against the live
> repo** (see §2). This is the complete, standalone build plan.

---

## 1. Context, goals & locked decisions

NAVIX has **no notification system today**. Business events (KYC decisions, credit hand-offs, disbursal,
repayments, collections, staff/IAM changes) move silently — a borrower never learns their KYC was
approved, and a staffer never learns an application landed in their queue except by polling the page. We
are now also **collecting a borrower email** at onboarding, which makes a real email channel worthwhile.

This builds **foundational, extensible** notification infra that future features extend, with three
properties:

- **Enum-categorized** — a self-describing catalog of *what* notifications exist (`NotificationType`).
- **Role-based & fan-out** — one notification can target a role and deliver to **many** recipients at
  once (e.g. all ACTIVE `CREDIT_EXECUTIVE`s), or the borrower, or a single specific staffer.
- **Templated** — each type has per-channel templates (subject/body) with `{placeholder}` substitution.

Three channels: **in-app website notifications**, **SMS**, **email**.

**Locked decisions:**
1. **Delivery = mock/log by default.** In-app is fully real & persisted; SMS/email are rendered and a
   delivery row is recorded, but no real send. Real providers flip on via one config flag — mirrors the
   existing `NAVIX_SMS_MOCK` philosophy (pre-go-live: SMS blocked on DLT, no SES/SMTP host yet).
2. **Email backend = SMTP** (`JavaMailSender`), behind an `EmailClient` port: `LogEmailClient` (default)
   + `SmtpEmailClient` (real, off until `navix.email.provider=smtp` + `spring.mail.*` set).
3. **Staff = in-app + email only** (no `staff_user.mobile`). **Borrowers = in-app + SMS + email** (add
   nullable `applicant_profile.email`).
4. **Comprehensive wiring** — publish events from the full lifecycle, repayment, collections, and IAM.

**Non-goals (deliberately out of the foundation):** OTP stays in `BorrowerOtpService` (it is synchronous —
the code must be on the request path); per-user preferences/opt-out; i18n; realtime SSE/WebSocket
(polling is chosen to match the existing `useQuery` convention); DB-backed admin-editable templates.

---

## 2. Ground-truth facts this plan is built on (verified)

The non-obvious repo facts that shape the design. Each was confirmed by reading the code.

| # | Fact | Source / consequence |
|---|---|---|
| 1 | **Latest migration is `V20__applicant_profile_credit_brief.sql`.** | New migrations are **V21** (notification core) + **V22** (`applicant_profile.email`). |
| 2 | **Borrower email is captured-but-discarded.** `applicant_profile` has only an `email_verified` *boolean* (V16). `verifyEmail()` passes `officialEmail` to Fintrix and never stores the string. `ProfileRequest` DTO and the frontend `ProfileInput` have **no email field**. | Persisting email is a **prerequisite (Phase 0)** — migration + entity + verify-capture + DTO + frontend. Without it the borrower EMAIL channel is dead. |
| 3 | **`ApplicationFlowService.logEvent` is the single choke-point.** At `logEvent` (`navix-loan/.../ApplicationFlowService.java:395`), `eventRepository.save(event)` is **line 407**; it reads the actor via `ActorContext.get()` and has the `app` in scope, exposing `app.getId()`, `app.getApplicantId()`, `app.getAssignedExecutiveId()`, `app.getLoanId()`. 22 call sites; the 2 non-transition direct calls (`CREATE`, `APPLY`) are also covered — and we *want* `APPLY` (→ `LOAN_APPLIED`). | Publish **one** `ApplicationTransitionedEvent` from line 407. Carry `loanId` (needed for money templates). |
| 4 | **Listener must switch on `action`, not `toStatus`.** Some transitions are same-status (`APPLY`: KYC_APPROVED→KYC_APPROVED) and some share a `toStatus` (`KYC_APPROVE` & `APPLY` both reach KYC_APPROVED). | Key the map on `action` (§5); sub-branch on `toStatus` only for `REBORROW` (→ PRE_APPROVED vs REVIEW_PENDING). |
| 5 | **`closeForLoan` logs action `"REPAID"`** (`ApplicationFlowService.java:343`, ACTIVE/OVERDUE→CLOSED). | `LOAN_CLOSED` keys on `REPAID`. |
| 6 | **No existing `ApplicationEventPublisher`/`@EventListener`** anywhere in the backend. | Clean slate — adopt Spring events with no migration risk. |
| 7 | **`StaffDirectory`/`StaffSummary(id,name,role,active)` is deliberately lean — no email/mobile.** | Add a **new** PII-bearing `StaffContactDirectory` port. Reuse `StaffUserRepository.findByRoleAndStatusOrderByIdAsc(role, ACTIVE)` for role fan-out. |
| 8 | **Staff have no `mobile` column.** | Staff never get SMS — falls out naturally from address-gating. |
| 9 | **`StaffService.updateStaff` has no role-change detection** (blindly sets the role). | Add `if (staff.getRole() != req.role())` before publishing `STAFF_ROLE_CHANGED`. |
| 10 | **`latestProfileForApplicant(applicantId)` is `private`** in `ApplicantReviewService`. | Expose a public latest-profile lookup (service method or `ApplicantProfileRepository` query) for `BorrowerContactAdapter`. |
| 11 | **`UltronSmsClient.send(number, text)` has no internal mock path** — `BorrowerOtpService` decides via `SmsProperties.mock()`. | The new `SmsSender` must honour `SmsProperties.mock()` itself (or push the short-circuit into `UltronSmsClient.send`) so notifications don't hit the gateway in mock mode. |
| 12 | **`NavixApplication` scans `com.navix`** for `@Component/@Entity/@Repository/@ConfigurationProperties`, but has **no `@EnableAsync`**. | A new `com.navix.notification` module auto-discovers with zero extra wiring; the module's `AsyncConfig` must add `@EnableAsync`. |
| 13 | **`SecurityConfig` is `anyRequest().authenticated()`** with a permit-list (`/api/auth`, `/api/storage`, actuator, docs). | `/api/notifications` is **auto-protected — no SecurityConfig change**. |
| 14 | **`CurrentActor.id()` is a `String`** ("SYSTEM" for system transitions; numeric otherwise). `ActorContext` is a `ThreadLocal`, **not** inherited by `@Async`. | `recipient_id` is `bigint` (parse id → Long); `actor_id` stays `varchar`. **Every datum must be carried in the event** — the async side has no ActorContext. |
| 15 | **`CollectionCase` PK is `GenerationType.UUID`; `collection_case.loan_id` is bigint.** | `notification.case_id` is `uuid`. `applicantId` for collections events is resolved via `LoanDirectory.findLoan(loanId)`. |
| 16 | **Frontend has React Query wired** (`QueryClientProvider` at `layout.tsx:83`; `live-journey.ts` polls via `refetchInterval`); `lucide-react` (Bell) + `Badge` exist; `Masking.maskEmail()` exists in navix-common; no `date-fns`. | Bell uses a 20s poll + the proven `account-menu.tsx` dropdown pattern + a tiny local `timeAgo`. |

**Decision to confirm (recommendation baked in).** The signup wizard collects two emails —
`personalEmail` (optional today) and `officialEmail` (required, employer-verified via Fintrix).
**Recommendation:** persist `personalEmail` as the primary **contact** email (a borrower-controlled
inbox — sending loan/repayment info to a *work* address the employer can read is a privacy risk), **fall
back to `officialEmail`** if blank, and make the contact email **required** at signup (consistent with
"we are now asking the borrower for an email"). Flip to official-primary if preferred — this is the one
place the plan forks.

---

## 3. Categorization — who gets what, and why ★

Two views of the same catalog. **§3 is recipient-centric** ("which customer gets which notification and
for what") — the product-facing answer. **§4 is the canonical enum** (the build artifact).

### 3.1 The BORROWER (in-app + SMS + email; SMS/email address-gated)

| Notification | Category | Channels | Fires when | Why the borrower needs it |
|---|---|---|---|---|
| `KYC_APPROVED` | KYC | in-app · SMS · email | KYC approver approves | KYC cleared → prompt to choose an amount |
| `KYC_REJECTED` | KYC | in-app · SMS · email | KYC approver rejects | KYC failed → what to fix / resubmit |
| `REBORROW_PREAPPROVED` | KYC | in-app · SMS | returning borrower, clean history | pre-approved → pick an amount (fast-track) |
| `REBORROW_REVIEW_PENDING` | KYC | in-app | returning borrower w/ past overdue | reborrow is under review |
| `REBORROW_REVIEW_APPROVED` | KYC | in-app · SMS | approver clears the reborrow review | cleared → pick an amount |
| `REBORROW_REVIEW_REJECTED` | KYC | in-app · SMS | approver rejects the reborrow review | declined |
| `LOAN_APPLIED_FAST_TRACK` | DISBURSEMENT | in-app | pre-approved borrower applies | acknowledgement (also notifies disb heads) |
| `CREDIT_APPROVED` | CREDIT | in-app · email | credit head final-approves | approved → disbursal next (no SMS — see §6) |
| `CREDIT_REJECTED` | CREDIT | in-app · SMS · email | credit exec or head rejects | declined at credit |
| `DISBURSEMENT_REJECTED` | DISBURSEMENT | in-app · email | disbursement head rejects | declined at disbursal |
| `LOAN_DISBURSED` | DISBURSEMENT | in-app · SMS · email | loan minted (`ACTIVATE` → ACTIVE) | **money credited**: net amount, due date, total repayable |
| `REPAYMENT_RECORDED` | REPAYMENT | in-app | borrower records a payment | receipt; "pending verification" |
| `REPAYMENT_VERIFIED` | REPAYMENT | in-app · SMS | accountant verifies a **non-closing** payment | payment confirmed + remaining balance |
| `LOAN_CLOSED` | REPAYMENT | in-app · SMS · email | final payment closes the loan | loan closed / NOC |
| `SETTLEMENT_APPROVED` | COLLECTIONS | in-app · SMS | a settlement is approved | agreed full-&-final; pay this to close |
| `APPLICATION_CANCELLED` | SYSTEM | in-app | application cancelled pre-disbursal | acknowledgement |

> **`REPAYMENT_VERIFIED` vs `LOAN_CLOSED` dedup:** a verification that *closes* the loan fires **both** a
> `RepaymentVerifiedEvent` and the `REPAID` transition. To avoid double-notifying on the final payment,
> `RepaymentVerifiedEvent` carries `closedTheLoan`; the listener emits `REPAYMENT_VERIFIED` **only when
> not closed** (`LOAN_CLOSED` covers the closing case).

### 3.2 STAFF, by role (in-app + email; never SMS)

| Role | Receives | Category | Channels | Fires when / why |
|---|---|---|---|---|
| `KYC_APPROVER` | `KYC_SUBMITTED` | KYC | in-app | borrower submits KYC → your queue |
| | `REBORROW_REVIEW_PENDING` | KYC | in-app | returning borrower w/ past overdue → `/staff/kyc-review` |
| `CREDIT_HEAD` | `LOAN_APPLIED` | CREDIT | in-app | borrower applied for an amount → credit queue |
| | `CREDIT_RECOMMENDED` | CREDIT | in-app | exec recommended → your **final approval** (SoD) |
| `CREDIT_EXECUTIVE` (the assignee) | `CREDIT_ASSIGNED` | CREDIT | in-app | credit head assigned this app **to you** (targeted, 1 recipient) |
| `DISBURSEMENT_HEAD` | `CREDIT_APPROVED` | DISBURSEMENT | in-app | credit cleared → new disbursal to process |
| | `LOAN_APPLIED_FAST_TRACK` | DISBURSEMENT | in-app | pre-approved borrower applied → fast-track section |
| | `DISBURSEMENT_FAILED` | DISBURSEMENT | in-app | accountant's transfer failed → retry |
| `ACCOUNTANT` | `DISBURSEMENT_PENDING_ACCOUNTANT` | DISBURSEMENT | in-app | disb head routed a transfer for you to validate |
| | `REPAYMENT_RECORDED` | REPAYMENT | in-app | borrower logged a payment → verify it |
| `COLLECTION_HEAD` | `COLLECTION_CASE_OPENED` | COLLECTIONS | in-app | a case opened on an overdue loan |
| | `SETTLEMENT_PROPOSED` | COLLECTIONS | in-app | an officer proposed a settlement → approve (SoD) |
| `COLLECTION_EXECUTIVE` | `COLLECTION_CASE_OPENED` | COLLECTIONS | in-app | a case opened → work it |
| **the staffer themselves** (`TO_STAFF_SUBJECT`) | `STAFF_INVITED` | STAFF_IAM | **email** | invite created → carries the **accept link/token** (unlocks the deferred "emailed invites" item) |
| | `STAFF_CREATED` | STAFF_IAM | email | admin created your account |
| | `STAFF_ROLE_CHANGED` | STAFF_IAM | in-app · email | your role changed (only on a **real** change) |
| | `STAFF_DISABLED` | STAFF_IAM | email | your account was disabled |

> **`ADMIN`** has oversight via dashboards; it is **not** fanned into routine flow (noise). The
> `TO_ADMINS` policy is defined but **reserved/unused in v1**.

---

## 4. The canonical catalog (self-describing enum)

`com.navix.notification.catalog.NotificationType` — each constant carries metadata via ctor args:
`NotificationType(NotificationCategory category, Set<NotificationChannel> channels, Set<RecipientPolicy> audience)`.
The enum's `name()` is the template key. **Channels are address-gated per recipient at dispatch** (IN_APP
always; SMS only if `mobile` present → staff naturally never get SMS; EMAIL only if `email` present; a
missing address on an intended channel → `SKIPPED(NO_ADDRESS)`, recorded for audit). `audience` being a
**Set** delivers fan-out (a role policy → many staff) **and** multi-target (borrower **and** disbursement
heads) in one declaration.

| NotificationType | category | channels | audience (RecipientPolicy) |
|---|---|---|---|
| `KYC_SUBMITTED` | KYC | IN_APP | TO_KYC_APPROVERS |
| `KYC_APPROVED` | KYC | IN_APP, SMS, EMAIL | TO_BORROWER |
| `KYC_REJECTED` | KYC | IN_APP, SMS, EMAIL | TO_BORROWER |
| `REBORROW_PREAPPROVED` | KYC | IN_APP, SMS | TO_BORROWER |
| `REBORROW_REVIEW_PENDING` | KYC | IN_APP | TO_KYC_APPROVERS, TO_BORROWER |
| `REBORROW_REVIEW_APPROVED` | KYC | IN_APP, SMS | TO_BORROWER |
| `REBORROW_REVIEW_REJECTED` | KYC | IN_APP, SMS | TO_BORROWER |
| `LOAN_APPLIED` | CREDIT | IN_APP | TO_CREDIT_HEADS |
| `CREDIT_ASSIGNED` | CREDIT | IN_APP | TO_ASSIGNED_EXECUTIVE |
| `CREDIT_RECOMMENDED` | CREDIT | IN_APP | TO_CREDIT_HEADS |
| `CREDIT_APPROVED` | CREDIT | IN_APP, EMAIL | TO_BORROWER, TO_DISBURSEMENT_HEADS |
| `CREDIT_REJECTED` | CREDIT | IN_APP, SMS, EMAIL | TO_BORROWER |
| `LOAN_APPLIED_FAST_TRACK` | DISBURSEMENT | IN_APP | TO_DISBURSEMENT_HEADS, TO_BORROWER |
| `DISBURSEMENT_PENDING_ACCOUNTANT` | DISBURSEMENT | IN_APP | TO_ACCOUNTANTS |
| `DISBURSEMENT_FAILED` | DISBURSEMENT | IN_APP | TO_DISBURSEMENT_HEADS |
| `DISBURSEMENT_REJECTED` | DISBURSEMENT | IN_APP, EMAIL | TO_BORROWER |
| `LOAN_DISBURSED` | DISBURSEMENT | IN_APP, SMS, EMAIL | TO_BORROWER |
| `REPAYMENT_RECORDED` | REPAYMENT | IN_APP | TO_ACCOUNTANTS, TO_BORROWER |
| `REPAYMENT_VERIFIED` | REPAYMENT | IN_APP, SMS | TO_BORROWER |
| `LOAN_CLOSED` | REPAYMENT | IN_APP, SMS, EMAIL | TO_BORROWER |
| `COLLECTION_CASE_OPENED` | COLLECTIONS | IN_APP | TO_COLLECTION_HEADS, TO_COLLECTION_EXECUTIVES |
| `SETTLEMENT_PROPOSED` | COLLECTIONS | IN_APP | TO_COLLECTION_HEADS |
| `SETTLEMENT_APPROVED` | COLLECTIONS | IN_APP, SMS | TO_BORROWER |
| `APPLICATION_CANCELLED` | SYSTEM | IN_APP | TO_BORROWER |
| `STAFF_INVITED` | STAFF_IAM | EMAIL | TO_STAFF_SUBJECT |
| `STAFF_CREATED` | STAFF_IAM | EMAIL | TO_STAFF_SUBJECT |
| `STAFF_ROLE_CHANGED` | STAFF_IAM | IN_APP, EMAIL | TO_STAFF_SUBJECT |
| `STAFF_DISABLED` | STAFF_IAM | EMAIL | TO_STAFF_SUBJECT |

Supporting enums:
- `NotificationChannel { IN_APP, SMS, EMAIL }`
- `NotificationCategory { KYC, CREDIT, DISBURSEMENT, REPAYMENT, COLLECTIONS, STAFF_IAM, SECURITY, SYSTEM }`
  (`SECURITY` reserved for future login-alert / password-change.)
- `RecipientType { STAFF, BORROWER }`
- `RecipientPolicy { TO_BORROWER, TO_ASSIGNED_EXECUTIVE, TO_KYC_APPROVERS, TO_CREDIT_HEADS,
  TO_CREDIT_EXECUTIVES, TO_DISBURSEMENT_HEADS, TO_ACCOUNTANTS, TO_COLLECTION_HEADS,
  TO_COLLECTION_EXECUTIVES, TO_ADMINS, TO_STAFF_SUBJECT }`

---

## 5. The `action → NotificationType` map (the listener switch)

Keyed on the `action` string written at `ApplicationFlowService.logEvent` (the verified vocabulary of all
22). `AUTO_ROUTE`, `VALIDATE_SUCCESS`, and `CREATE` are **deliberate no-ops** — e.g. an exec-decision
call logs `EXEC_APPROVE` **and** `AUTO_ROUTE` in one transaction, so only the first should notify.

| action | from → to | → NotificationType |
|---|---|---|
| `CREATE` | null → DRAFT | — (no-op) |
| `SUBMIT_KYC` | → KYC_PENDING | `KYC_SUBMITTED` |
| `KYC_APPROVE` | KYC_PENDING → KYC_APPROVED | `KYC_APPROVED` |
| `KYC_REJECT` | KYC_PENDING → KYC_REJECTED | `KYC_REJECTED` |
| `APPLY` | KYC_APPROVED → KYC_APPROVED (same-status) | `LOAN_APPLIED` |
| `APPLY_FAST_TRACK` | PRE_APPROVED → DISBURSEMENT_PENDING | `LOAN_APPLIED_FAST_TRACK` |
| `ASSIGN` | KYC_APPROVED → CREDIT_EXEC_PENDING | `CREDIT_ASSIGNED` |
| `EXEC_APPROVE` | CREDIT_EXEC_PENDING → CREDIT_EXEC_APPROVED | `CREDIT_RECOMMENDED` |
| `EXEC_REJECT` | CREDIT_EXEC_PENDING → REJECTED | `CREDIT_REJECTED` |
| `AUTO_ROUTE` | (×2 routing hops) | — (no-op) |
| `HEAD_APPROVE` | CREDIT_HEAD_PENDING → CREDIT_HEAD_APPROVED | `CREDIT_APPROVED` |
| `HEAD_REJECT` | CREDIT_HEAD_PENDING → REJECTED | `CREDIT_REJECTED` |
| `DISB_ACCEPT` | DISBURSEMENT_PENDING → ACCOUNTANT_PENDING | `DISBURSEMENT_PENDING_ACCOUNTANT` |
| `DISB_REJECT` | DISBURSEMENT_PENDING → REJECTED | `DISBURSEMENT_REJECTED` |
| `VALIDATE_FAIL` | ACCOUNTANT_PENDING → DISBURSEMENT_FAILED | `DISBURSEMENT_FAILED` |
| `VALIDATE_SUCCESS` | ACCOUNTANT_PENDING → DISBURSED | — (no-op; wait for `ACTIVATE`) |
| `ACTIVATE` | DISBURSED → ACTIVE | `LOAN_DISBURSED` |
| `RETRY` | DISBURSEMENT_FAILED → ACCOUNTANT_PENDING | `DISBURSEMENT_PENDING_ACCOUNTANT` |
| `REPAID` | ACTIVE/OVERDUE → CLOSED | `LOAN_CLOSED` |
| `CANCEL` | → CANCELLED | `APPLICATION_CANCELLED` |
| `REBORROW` | DRAFT → **PRE_APPROVED** | `REBORROW_PREAPPROVED` |
| `REBORROW` | DRAFT → **REVIEW_PENDING** | `REBORROW_REVIEW_PENDING` |
| `REVIEW_APPROVE` | REVIEW_PENDING → PRE_APPROVED | `REBORROW_REVIEW_APPROVED` |
| `REVIEW_REJECT` | REVIEW_PENDING → REJECTED | `REBORROW_REVIEW_REJECTED` |

> `LOAN_DISBURSED` keys on **`ACTIVATE`** (the one DISBURSED→ACTIVE mint step), so it fires **once**
> whether the loan came via the accountant path or the disbursement-head fast-path. `REBORROW` is the
> only action that forks → sub-branch on `toStatus`.

Repayment, collections, and IAM notifications come from their own dedicated events (not the transition
map): `RepaymentRecordedEvent → REPAYMENT_RECORDED`, `RepaymentVerifiedEvent → REPAYMENT_VERIFIED`
(unless `closedTheLoan`), `CollectionCaseOpenedEvent → COLLECTION_CASE_OPENED`,
`SettlementProposedEvent → SETTLEMENT_PROPOSED`, `SettlementApprovedEvent → SETTLEMENT_APPROVED`,
`StaffAccountEvent{changeType} → STAFF_{INVITED,CREATED,ROLE_CHANGED,DISABLED}`.

---

## 6. DLT template register (SMS reality — a hard external constraint)

Every distinct SMS body must be a **separately DLT-registered template** (the same wall that blocks OTP
today). So the SMS set is **deliberately minimal** — these **8** borrower templates are the entire DLT
registration backlog this feature creates; staff never get SMS, so there are no staff templates:

`KYC_APPROVED`, `KYC_REJECTED`, `CREDIT_REJECTED`, `LOAN_DISBURSED`, `REPAYMENT_VERIFIED`, `LOAN_CLOSED`,
`SETTLEMENT_APPROVED`, `REBORROW_PREAPPROVED` (add `REBORROW_REVIEW_APPROVED`/`_REJECTED` only if
reborrow-review SMS is wanted — start without).

`CREDIT_APPROVED` is intentionally **email + in-app only** (the money SMS is `LOAN_DISBURSED`) to keep
the DLT backlog small. Until templates are registered, all SMS runs in mock mode (`NAVIX_SMS_MOCK=true`)
and deliveries record `SENT` with a mock ref — identical to OTP today.

---

## 7. Architecture (decoupled, non-blocking)

```
business service  --publishEvent-->  Spring ApplicationEventPublisher
  (loan/collections/iam — already                |
   depend only on navix-common)                  v   (events live in navix-common)
                            NotificationEventListener  @TransactionalEventListener(AFTER_COMMIT) + @Async
                                                   |   maps action/event -> (NotificationType, NotificationContext)
                                                   v
                            NotificationDispatcher  (its own @Transactional)
                              1. read type metadata (category, channels, audience)
                              2. AudienceResolver -> List<ContactInfo>   <-- FAN-OUT (role -> many staff)
                              3. TemplateRenderer  -> per-channel RenderedMessage
                              4. persist 1 Notification per recipient
                              5. per channel: ChannelSender (try/catch) -> NotificationDelivery row
                                   InAppSender | SmsSender(SmsGateway) | EmailSender(EmailClient)
```

- **Why events + AFTER_COMMIT + @Async:** notifications must never block, fail, or roll back a business
  transaction. Business modules emit a thin event and forget; the dispatcher runs **after commit** on a
  bounded pool. (No existing event usage — clean slate.)
- **Why a new module + ports:** the engine lives in a new `navix-notification` module depending only on
  `navix-common`. Business modules depend on **events in navix-common**, never on the engine — so **no
  new cross-module Maven edge** beyond `navix-app → navix-notification` (the right direction).
  `NavixApplication` scans `com.navix`, so the module auto-discovers with zero extra wiring.
- **The async thread has no `ActorContext` and no transaction** (`ActorContext` is a `ThreadLocal`, not
  inherited by `@Async`). **Every datum is carried in the event;** the dispatcher opens its own
  `@Transactional` and does its own contact/loan reads via ports.

---

## 8. Build order & files

### Phase 0 — PREREQUISITE: persist the borrower contact email (blocking for the EMAIL channel)
Email is captured-but-discarded today (§2 #2). Until it's stored, borrower email notifications are dead.
1. **Migration `V22__applicant_profile_email.sql`**: `alter table applicant_profile add column email varchar(255);`
   (nullable — older/returning borrowers won't have one; EMAIL is address-gated).
2. **`ApplicantProfile` entity** (`navix-loan/.../entity/ApplicantProfile.java`): add `email` field + accessors.
3. **Capture at verify** (`ApplicationVerificationService.verifyEmail`, ~line 113): add `profile.setEmail(email);`
   before `profileRepo.save(profile)` (currently only sets the boolean).
4. **DTO + save path**: add `email` (and optionally `personalEmail`) to `ProfileRequest`
   (`navix-loan/.../dto/ReviewDtos.java`) and map it in `ApplicantReviewService.saveProfile`.
5. **Frontend**: `frontend/src/app/(borrower)/signup/email/page.tsx` currently only **verifies** the email
   (`verificationApi.email`) — extend `ProfileInput` (`lib/api/applications.ts`) + `saveProfileSlice` to
   **persist** the contact email; make it **required** per the §2 decision.
6. **Public lookup** for the contact adapter (§2 #10): expose a public "latest profile for applicantId"
   (service method or `ApplicantProfileRepository` query).

### Phase A — shared vocabulary + events (`navix-common`)
New package `com.navix.common.notification`:
- Enums: `NotificationChannel`, `NotificationCategory`, `RecipientType`.
- `ContactInfo(RecipientType type, Long id, String name, String email, String mobile, String role)`.
- Contact ports (PII-bearing; separate from the lean `StaffSummary`):
  `com.navix.common.staff.StaffContactDirectory { Optional<ContactInfo> contact(Long staffId); List<ContactInfo> contactsByRole(String role); }`
  and `com.navix.common.loan.BorrowerContactDirectory { Optional<ContactInfo> borrowerContact(Long applicantId); }`.
- SMS gateway port: `com.navix.common.sms.SmsGateway { String send(String number, String text); }`
  (signature already matches `UltronSmsClient.send`).
- Domain events (`com.navix.common.notification.event`, immutable records — **all data inline**):
  - `ApplicationTransitionedEvent(applicationId, applicantId, loanId, fromStatus, toStatus, action, assignedExecutiveId, actorId, actorRole, at)`.
  - `RepaymentRecordedEvent(loanId, applicantId, paymentId, amountPaise, at)`.
  - `RepaymentVerifiedEvent(loanId, applicantId, paymentId, amountPaise, closedTheLoan, at)`.
  - `CollectionCaseOpenedEvent(caseId, loanId, applicantId, at)`.
  - `SettlementProposedEvent(settlementId, caseId, loanId, applicantId, amountPaise, proposedBy, at)`.
  - `SettlementApprovedEvent(settlementId, caseId, loanId, applicantId, amountPaise, approvedBy, at)`.
  - `StaffAccountEvent(staffId, email, name, role, changeType{INVITED,CREATED,ROLE_CHANGED,DISABLED}, inviteToken, at)`.

### Phase B — the engine (new module `navix-notification`)
- `backend/navix-notification/pom.xml` — mirror `navix-iam/pom.xml` (navix-common + `spring-boot-starter-data-jpa`
  + `spring-boot-starter-validation` + lombok); **add** `spring-boot-starter-web` and
  **`spring-boot-starter-mail`**. Add `<module>navix-notification</module>` to `backend/pom.xml` and the
  dependency to `backend/navix-app/pom.xml`.
- **Migration `V21__notification_core.sql`** — `notification` + `notification_delivery` (DDL §8.1).
  *(Phase 0's V22 is independent; order V21/V22 by number, not dependency.)*
- **Entities/repos** (`com.navix.notification.entity` / `.repository`): `Notification`, `NotificationDelivery`
  (plain `@Entity`, own identity id, enums as STRING). `NotificationRepository`:
  `findByRecipientTypeAndRecipientIdOrderByCreatedAtDesc(rt,id,Pageable)`,
  `countByRecipientTypeAndRecipientIdAndInAppTrueAndReadAtIsNull(rt,id)`, `@Modifying` mark-read
  (id+owner scoped) + mark-all.
- **Catalog**: `NotificationType` (§4), `RecipientPolicy`.
- **Templates** (code-defined for v1 — versioned, type-safe; SMS bodies are DLT-locked anyway):
  `NotificationTemplates` registry keyed by `(NotificationType, NotificationChannel) -> TemplateDef(subject, body)`;
  `TemplateRenderer` does `{placeholder}` substitution (generalizes `BorrowerOtpService.buildMessage`'s
  `{otp}`/`{ttl}` `String.replace`), unknown keys → `—`.
- **Audience**: `AudienceResolver` injects both contact ports; role policies → `contactsByRole(ROLE)`
  (fan-out across ACTIVE holders via `findByRoleAndStatusOrderByIdAsc(role, ACTIVE)`),
  `TO_ASSIGNED_EXECUTIVE`/`TO_STAFF_SUBJECT` → `contact(id)`, `TO_BORROWER` → `borrowerContact(applicantId)`;
  union + de-dupe by `(type,id)`.
- **Channels**: `ChannelSender { NotificationChannel channel(); DeliveryOutcome send(RenderedMessage, ContactInfo); }`
  (**never throws**); `InAppSender` (no-op transport — the row is the inbox), `SmsSender` (injects
  `SmsGateway`, `send("91"+mobile, body)`, **honours `SmsProperties.mock()`** per §2 #11, catches
  `SmsException`), `EmailSender` (injects `EmailClient`).
- **Email port** (engine-internal): `EmailClient { EmailResult send(EmailMessage); }`; `LogEmailClient`
  (`@ConditionalOnProperty(name="navix.email.provider", havingValue="log", matchIfMissing=true)` — default,
  logs `Masking.maskEmail()` recipient + subject); `SmtpEmailClient` (`havingValue="smtp"`, wraps
  `JavaMailSender` via `MimeMessageHelper`). *(Spring Boot mail auto-config only activates when
  `spring.mail.host` is set, so no `JavaMailSender` bean is required in log mode.)*
- **Dispatcher**: `NotificationDispatcher.dispatch(NotificationType, NotificationContext)` (`@Transactional`)
  — metadata → audience → per recipient: render + persist `Notification` (`in_app = channels.contains(IN_APP)`)
  → per address-gated channel: create `NotificationDelivery(PENDING)`, call sender in try/catch, update
  `SENT`/`FAILED`/`SKIPPED`. **Error isolation: one recipient/channel failure never aborts the rest.**
  `NotificationContext(applicantId, applicationId, loanId, caseId, assignedExecutiveId, staffSubjectId,
  actorId, actorRole, Map<String,Object> model)`. For money/date templates (`LOAN_DISBURSED`,
  `LOAN_CLOSED`) the dispatcher enriches the model from the loan via `LoanDirectory`.
- **Listener**: `NotificationEventListener` — one
  `@TransactionalEventListener(phase=AFTER_COMMIT) @Async("notificationExecutor")` handler per event; the
  `ApplicationTransitionedEvent` handler holds the **action-keyed** switch (§5).
- **Async config**: `AsyncConfig` (`@Configuration @EnableAsync` — required, §2 #12) →
  `@Bean("notificationExecutor") ThreadPoolTaskExecutor` (core/max/queue from props, prefix `notif-`,
  `CallerRunsPolicy`) + an uncaught-exception handler that logs.
- **Web**: `NotificationController @RequestMapping("/api/notifications")` (covered by existing
  `anyRequest().authenticated()` — **no SecurityConfig change**): `GET /?page=&size=` (mine, newest-first),
  `GET /unread-count`, `POST /{id}/read`, `POST /read-all`. `NotificationService` resolves the current
  recipient from `ActorContext` (`role()=="BORROWER"` → `(BORROWER, parseLong(id))`; else `(STAFF,
  parseLong(id))`) and **scopes every read/write by `(recipientType, recipientId)`** (cross-recipient
  access → 404). DTO `NotificationView(id, type, category, title, body, read, applicationId, loanId,
  caseId, createdAt)`.

**§8.1 — `V21__notification_core.sql` DDL** (Postgres; partial index powers the hot unread-count):
```
notification(id bigint generated by default as identity primary key,
  recipient_type varchar(16) not null, recipient_id bigint not null,
  type varchar(64) not null, category varchar(32) not null,
  title varchar(200) not null, body varchar(2000) not null,
  in_app boolean not null default true, read_at timestamptz,
  application_id bigint, loan_id bigint, case_id uuid,
  actor_id varchar(64), actor_role varchar(64),
  dedupe_key varchar(120),                                  -- optional idempotency (§11)
  created_at timestamptz not null default now())
  + index ix_notif_owner (recipient_type, recipient_id, created_at desc)
  + partial index ix_notif_unread (recipient_type, recipient_id) where in_app and read_at is null
notification_delivery(id bigint generated by default as identity primary key,
  notification_id bigint not null references notification(id),
  channel varchar(16) not null, address varchar(320),
  status varchar(16) not null,                              -- PENDING|SENT|FAILED|SKIPPED
  provider_ref varchar(160), error varchar(1000), attempts int not null default 0,
  created_at timestamptz not null default now(), updated_at timestamptz)
```
*(`case_id uuid` is correct — `CollectionCase` PK is `GenerationType.UUID`. This `notification_delivery →
notification` FK is the one intra-module FK; existing tables are FK-light, so drop it if it clashes with
the project's no-FK convention.)*

### Phase C — wiring (adapters + event publishing)
- `backend/navix-app/.../sms/UltronSmsClient.java` — add `implements SmsGateway` (signature already matches);
  push the `SmsProperties.mock()` short-circuit into `send` so all callers inherit it (§2 #11).
- `navix-iam` — `StaffContactAdapter implements StaffContactDirectory` (query `staff_user`; ACTIVE-only for
  `contactsByRole` via `findByRoleAndStatusOrderByIdAsc`; expose name/email/role — **no mobile**).
- `navix-loan` — `BorrowerContactAdapter implements BorrowerContactDirectory` (reuse the **new public**
  latest-profile lookup from Phase 0 → name + mobile + email).
- **Event publishing** (inject `private final ApplicationEventPublisher eventPublisher;` + one line each):
  - `ApplicationFlowService.logEvent(...)` **at line 407**, after `eventRepository.save(event)` — publish
    `ApplicationTransitionedEvent` (`app.getId()`, `app.getApplicantId()`, `app.getLoanId()`, from, to,
    action, `app.getAssignedExecutiveId()`, `actor.id()`, `actor.role()`). **One line covers all 22 call
    sites** including same-status `APPLY` (→ `LOAN_APPLIED`).
  - `RepaymentService.recordPayment` → `RepaymentRecordedEvent`; `RepaymentService.verifyPayment` →
    `RepaymentVerifiedEvent` with `closedTheLoan = (owed == 0)` (from `recomputeOutstanding`; `LOAN_CLOSED`
    still flows via `closeForLoan → logEvent("REPAID")`).
  - `CollectionsService.openCase` (**only on fresh `orElseGet` creation**) → `CollectionCaseOpenedEvent`
    (resolve applicantId via `LoanDirectory`); `SettlementService.propose/approve` →
    `SettlementProposed/ApprovedEvent` (resolve loanId/applicantId from the case → `LoanDirectory`).
  - `StaffService.createStaff/disableStaff` + `InviteService.createInvite/acceptInvite` → `StaffAccountEvent`;
    `updateStaff` → publish `ROLE_CHANGED` **only when `staff.getRole() != req.role()`** (add the check, §2 #9).
    `createInvite` carries the token so `STAFF_INVITED`'s email can include the accept link.

### Phase D — frontend
- BFF proxies (mirror `app/api/staff/applications/[[...path]]/route.ts` — optional catch-all, forwards the
  JWT bearer from the cookie; the JWT audience scopes staff vs borrower):
  - `frontend/src/app/api/borrower/notifications/[[...path]]/route.ts` (`getBorrowerSession`).
  - `frontend/src/app/api/staff/notifications/[[...path]]/route.ts` (`getStaffSession`).
- `lib/api/applications.ts` — add `NotificationView` + `makeNotificationsApi(base)` (via existing `bff<T>()`);
  export `borrowerNotificationsApi` (`/api/borrower/notifications`), `staffNotificationsApi`
  (`/api/staff/notifications`).
- `lib/api/notifications.ts` (new) — React Query hooks `useNotifications(scope)` + `useUnreadCount(scope)`
  (`refetchInterval: 20000`, matching `live-journey.ts`); mark-read mutations invalidate both keys.
- `components/notifications/notification-bell.tsx` (new, shared, `scope:"borrower"|"staff"`) — `lucide-react`
  `Bell` + existing `Badge` for the count; dropdown reuses the proven `account-menu.tsx` pattern
  (click-outside + Escape; no new library). Rows show relative time (small local `timeAgo` — no date-fns),
  an unread dot, mark-all, and row-click → mark-read + navigate via a `type → href` map (using
  `applicationId`/`loanId`/`caseId`).
- Mount points: `components/app/app-header.tsx` (borrower, **before** `<AccountMenu/>`, ~line 44);
  `components/staff/staff-shell.tsx` (staff, in the `div.ml-auto` between the avatar and Sign-out, ~line 190).

### Phase E — config (`navix-app/.../application.yml`, under the existing `navix:` block)
```yaml
  email:
    provider: ${NAVIX_EMAIL_PROVIDER:log}      # log | smtp
    enabled:  ${NAVIX_EMAIL_ENABLED:true}
    from:     ${NAVIX_EMAIL_FROM:NAVIX Finance <no-reply@navixfinance.example>}
  notifications:
    async: { core-pool-size: ${NAVIX_NOTIF_CORE_POOL:2}, max-pool-size: ${NAVIX_NOTIF_MAX_POOL:8}, queue-capacity: ${NAVIX_NOTIF_QUEUE:500} }
# spring.mail.* (host/port/username/password) only when provider=smtp
```
`@ConfigurationProperties` records `EmailProperties`/`AsyncProperties` in the module (auto-bound via the
existing `@ConfigurationPropertiesScan("com.navix")`).

### Phase F — tests
- **Module unit**: dispatcher **fan-out + error isolation** (role→3 execs→3 notifications; one sender throws
  → others still `SENT`, no exception escapes); `AudienceResolver` per policy (+ null id → empty, composite
  de-dupe); `TemplateRenderer` (substitution, missing→`—`, per-channel subject/body); **listener
  action-mapping** (`KYC_APPROVE→KYC_APPROVED`; `AUTO_ROUTE`/`VALIDATE_SUCCESS`/`CREATE`→ no dispatch;
  `REBORROW`+toStatus fork); `RepaymentVerifiedEvent` dedup (closing → no `REPAYMENT_VERIFIED`);
  `EmailClient` toggle (`log` default vs `smtp` slice).
- **`navix-app`**: `NotificationController` scoping (`@WebMvcTest` + stubbed `ActorContext` — borrower sees
  only own rows, mark-read of another's → 404, unread-count = in_app && unread); **integration** (`-Pit`,
  Testcontainers + Awaitility) — a committed business call → assert `notification` +
  `notification_delivery(IN_APP,SENT)` appear post-AFTER_COMMIT (confirms async + V21/V22 + JPA `validate`).

---

## 9. Verification walkthrough
1. `./mvnw -q -pl navix-notification -am install` then `./mvnw -q -pl navix-app -am package` — module
   compiles, app builds with the new edge.
2. Boot `navix-app` (defaults `NAVIX_EMAIL_PROVIDER=log`, `NAVIX_SMS_MOCK=true`). Flyway applies V21/V22;
   Hibernate `validate` passes.
3. Borrower submits KYC → every ACTIVE `KYC_APPROVER` gets an in-app `KYC_SUBMITTED`; the bell badge ticks
   up on the 20s poll. Inspect `notification` + `notification_delivery(IN_APP,SENT)`.
4. Approver approves → borrower gets `KYC_APPROVED` in-app + SMS (mock JobId) + EMAIL (LogEmailClient line,
   masked recipient) — **email only if Phase 0 persisted an address**, else `SKIPPED(NO_ADDRESS)`.
5. Credit head assigns an exec → **only that exec** gets `CREDIT_ASSIGNED` (targeted). Exec recommends →
   **all** credit heads get `CREDIT_RECOMMENDED` (fan-out). Disburse → `LOAN_DISBURSED` to borrower with net
   amount + due date; click the bell row → marks read, count drops, navigates to the loan.
6. Failure isolation: `NAVIX_SMS_MOCK=false` + bad creds, repeat a borrower event → SMS delivery `FAILED`,
   IN_APP still `SENT`, the business request unaffected.
7. `curl /api/notifications/unread-count` with borrower vs staff JWT → two correctly scoped counts.
8. Staff invite: `POST /api/staff/invites` → `STAFF_INVITED` email (log line) carries the accept link.
- Frontend: `npx tsc --noEmit` + ESLint (per CLAUDE.md the `npm run build` static-prerender is a known env
  issue — use dev/tsc).

---

## 10. What this unlocks beyond the ask
- **Emailed staff invites** (a deferred go-live item, CLAUDE.md §13) — `STAFF_INVITED` carries the accept
  link, closing that gap for free once SMTP is on.
- A **real borrower email channel**, now that Phase 0 persists the address — reusable for future
  statements, sanction letters, and NOC delivery.

---

## 11. Notes / deferred (sharpened)
- **Durability — be precise.** AFTER_COMMIT + in-memory `@Async` is **at-most-once**, with **two** distinct
  gaps: (a) a crash *after* business commit but *before* the async dispatch runs loses the notification
  **with no row to retry** (the `Notification` is written by the dispatcher, not the business tx); (b) a
  channel send that fails leaves a `FAILED`/`PENDING` delivery row. A future `@Scheduled DeliveryRetryJob`
  reprocesses (b). Only a **transactional outbox** (write an outbox row *inside* the business transaction,
  dispatch from a poller) closes (a) — the documented prod path, deferred for v1.
- **OTP stays out of this engine** (non-goal). It is synchronous and already lives in `BorrowerOtpService`;
  it *shares* the `SmsGateway` port + `{placeholder}` rendering style, nothing more.
- **Idempotency.** Events can re-fire (business retries; at-least-once if an outbox is added later). The
  optional `dedupe_key` column + a partial unique guard on money events (`LOAN_DISBURSED`, `LOAN_CLOSED`,
  `REPAYMENT_VERIFIED`) makes re-publish safe; low-risk in v1 (each transition logs once).
- **Overdue / repayment reminders — recommended fast-follow (Phase G, not v1).** For a salary-linked product
  the highest-value message is "your repayment is due in 2 days" / "you're overdue". These have **no
  triggering event** (time passing), so they need a small `@Scheduled` daily job over ACTIVE/OVERDUE loans
  that computes DPD and dispatches `REPAYMENT_DUE_SOON` / `LOAN_OVERDUE` through this same engine (a DLT
  template each).
- **Also deferred:** per-user preferences/opt-out, i18n, realtime SSE/WebSocket, DB-backed admin-editable
  templates, verifying the borrower's *personal* email (a future email-OTP step).
```