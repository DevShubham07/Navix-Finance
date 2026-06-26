# populateDummyData.md — seed demo data at every loan stage

A one-shot populator that fills a local NAVIX environment with **one loan application parked at
every stage of the lifecycle**, plus a **primary borrower with a rich account history** (a closed
loan, an active loan with a part-payment, and an in-review application). After it runs, every staff
queue has realistic rows and the borrower account menu (`/loans`, `/transactions`) has content.

- **Script:** [`scripts/populate-demo-data.ps1`](scripts/populate-demo-data.ps1)
- **How it works:** it drives the **real backend REST API** on `http://localhost:8090` with the demo
  actor headers (`X-Demo-Actor-Id` / `-Name` / `-Role`) that `DemoActorFilter` reads — so the flow
  service computes the money (integer paise), the salary-linked due date, and the append-only
  `application_event` audit trail for you. Nothing is inserted behind the state machine's back.
- **One exception:** an OVERDUE / past-due loan can't be made through the API (the server always
  stamps the due date in the *future*), so for the overdue + past-delinquency personas the script
  runs a single `UPDATE loan SET disbursed_on=…, due_date=…` via `docker compose exec db psql`.
  Pass `-SkipBackdate` to skip that (those personas then remain ACTIVE/CLOSED).

---

## Prerequisites

The local stack must be running (see `CLAUDE.md` §4 / `run-local.ps1`):

- **Postgres** on `localhost:5433` (Docker, db/user/pass `navix`).
- **Backend** on `http://localhost:8090` (Flyway applies V1–V14 and seeds the demo staff in V10).

Fastest path:

```powershell
./run-local.ps1 -NoFrontend     # Postgres + backend only (enough to seed)
# …wait until http://localhost:8090 answers…
./scripts/populate-demo-data.ps1
```

The seeded staff ids the script uses as actors come from `V10__seed_demo_staff.sql`:

| id | role | id | role |
|----|------|----|------|
| 1 | KYC_APPROVER (Ananya Rao) | 6 | DISBURSEMENT_HEAD (Vikram Shah) |
| 2 | CREDIT_EXECUTIVE (Rahul Mehta) | 7 | ACCOUNTANT (Deepa Iyer) |
| 5 | CREDIT_HEAD (Priya Nair) | 8 | COLLECTION_HEAD (Arjun Patel) |
| | | 10 | ADMIN (Meera Krishnan) |

---

## Run it

```powershell
./scripts/populate-demo-data.ps1                       # default: http://localhost:8090, backdate on
./scripts/populate-demo-data.ps1 -SkipBackdate         # no Docker / skip the OVERDUE personas
./scripts/populate-demo-data.ps1 -BackendBase http://localhost:8090 -DbService db
```

It prints each created application (`app #id -> STAGE`), a live status-count table, and the demo
logins at the end.

---

## What it seeds

### Primary borrower — `Aarav Sharma`
Log in at **`/login`** with mobile **`9819000001`**, OTP **`123456`** (the last 7 digits map to
`applicantId` **9000001**). Seeded under that one identity:

| Application | Ends at | Surfaces in |
|---|---|---|
| Closed loan (fully repaid) | `CLOSED` | `/loans` (history), `/transactions` (disbursal + repayment) |
| Active loan (part-paid) | `ACTIVE` | `/dashboard`, `/loans`, `/transactions` |
| In-flight application | `CREDIT_HEAD_PENDING` | `/loans` ("in review"); Credit Head's queue |

### One applicant per remaining stage (distinct identities → distinct staff-queue rows)

| Applicant | Stage | Staff surface (role to log in as) |
|---|---|---|
| 9000010 Bhavya Reddy | `KYC_PENDING` | KYC Approver → `kyc-approvals` |
| 9000011 Chetan Verma | `KYC_APPROVED` (applied) | Credit Head → `credit/queue` (assign) |
| 9000012 Divya Menon | `CREDIT_EXEC_PENDING` | Credit Executive → credit queue |
| 9000013 Esha Pillai | `CREDIT_HEAD_PENDING` | Credit Head → final approve |
| 9000014 Farhan Sheikh | `DISBURSEMENT_PENDING` | Disbursement Head → `disbursement` |
| 9000015 Gauri Joshi | `ACCOUNTANT_PENDING` | Accountant → `accounting` |
| 9000016 Harsh Patel | `ACTIVE` (disbursement fast-path) | Accountant ledger / borrower view |
| 9000017 Ira Bose | `OVERDUE` (no case) | Collections → collectible loans |
| 9000018 Jay Nair | `IN_COLLECTIONS` (case opened) | Collections → buckets / case detail |
| 9000019 Kavya Rao | `KYC_REJECTED` | KYC Approver (rejected) |
| 9000020 Lalit Shah | `REJECTED` (credit) | Credit queue (rejected) |
| 9000021 Maya Iyer | `CANCELLED` | — |
| 9000022 Nidhi Saxena | `PRE_APPROVED` (clean reborrow) | Disbursement Head fast-track |
| 9000023 Omkar Joshi | `REVIEW_PENDING` (late-history reborrow) | KYC Approver → `kyc-review` |

> Note: an overdue **loan** reads `OVERDUE` via compute-on-read, but the **application** row stays
> `ACTIVE` until a collection case flips the loan to `IN_COLLECTIONS` — so the OVERDUE personas show
> up on the loan/collections surfaces, not in `GET /api/applications?status=OVERDUE`.

---

## Re-running / reset

- The script is **re-runnable**: every KYC profile gets a fresh unique PAN/Aadhaar/mobile per run
  (the `applicant_profile` unique indexes from V12), so a second run never collides. The primary
  borrower's **login mobile stays fixed** (its profile mobile is left blank), so `9819000001` always
  resolves to the same history — re-running just adds more rows.
- For a clean slate, drop the demo data and let Flyway re-seed staff:

  ```powershell
  docker compose exec -T db psql -U navix -d navix -c "TRUNCATE payment, application_event, application_document, applicant_profile, collection_case, loan_application, loan RESTART IDENTITY CASCADE;"
  ```

  (This clears business data but keeps `staff_user` / blocklist / invites. Restart the backend if you
  want the `application_event` ids to restart cleanly.)

---

## Money used (so the numbers are recognisable)

Salary ₹50,000 → eligible limit ₹12,500 (25%, floored to ₹100); loan amount ₹10,000; processing fee
₹1,000 (10%); GST ₹180 (18% of fee); **net disbursed ₹8,820**; interest 1%/day over the salary-linked
tenure; late penalty 2%/day (capped 30 days) on the backdated personas. All amounts are integer paise
end-to-end (see `CLAUDE.md` §9).
