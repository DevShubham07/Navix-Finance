# Simulation admin environment — design

**Date:** 2026-11-09 · **Status:** awaiting owner review

## 1. Goal

Give DhanBoost one admin login, entered on the existing staff login page (`dhanboost.com/staff/login`,
also served at `app.dhanboost.com`), that opens a fully separate copy of the business. The domain, DNS,
URLs and UI stay exactly as they are, apart from the SIMULATION label: the account used to sign in alone
decides which data is shown. Staff are trained on it by walking cases through to closure. Nothing done
in it can reach the production database or production data.

The copy's dashboard is adjusted to show about **₹70 lakh total disbursed** and about **60% average
monthly case closure**.

Out of scope: direct database access or tunnels, date shifting, ageing simulations, scheduled jobs in
the sim, and borrower login in the sim.

## 2. Decisions

| Topic | Decision |
|---|---|
| Data | Exact, unmasked one-time copy of production (owner's decision; masking was recommended and declined) |
| Documents | One-time copy of the document bucket into a separate sim bucket |
| Login location | Same staff login page and URL; one designated account routes to the sim; no DNS or domain change |
| Usage | Admin console by hand, trainer-led |
| Hosting | Separate database, bucket, ECS service and load balancer |
| Sim login email | `sim.admin@dhanboost.com` (no mailbox needed; must not exist in production `staff_user`) |

## 3. Production is read-only (hard rule)

**Allowed against production, and nothing else:**
- Reading AWS settings (`describe-*`, and `get-parameter` for values the sim needs).
- Copying an existing RDS **automated backup** to a sim-owned snapshot.
- `s3:ListBucket`, `s3:GetObject` and `kms:Decrypt` for the one-time document copy.

**Forbidden:**
- Connecting to the production database, for any reason.
- Any write or configuration change on production RDS, S3, SSM, the `navix-backend` ECS service or its
  task definition, or `navix-alb`.
- Taking a new snapshot of the production instance. It briefly pauses disk activity on a Single-AZ
  instance, so it needs separate owner approval.

**How this is enforced:**
- The `navix-dev` account used to build the sim can also change production, so its commands name only new
  `*-sim*` resources, and production resources appear only in the read actions above.
- The document copy runs under an IAM policy containing only the three read actions above.
- The sim task role has no permissions on any production resource.
- The restored database has its passwords changed before anything else runs (§6 step 2), so production
  credentials stop working on it and sim credentials never exist for production.
- Every data script refuses to run if its target host is the production endpoint
  (`navix-finance-dev.cf22os0umu8l…`), and also unless the `sim_marker` table is present. Production
  has no such table.

The only production-side change is the code release in §8. It adds no migrations, so production's schema
and data are not touched, and it changes behaviour only for a session marked as sim. It ships last and
only on the owner's explicit OK.

## 4. Sim infrastructure

All in `ap-south-1`, sharing only the VPC, the private subnets and NAT with production.

**Database.** A new `navix-finance-sim` instance, `db.t4g.micro`, not publicly accessible, in a new DB
subnet group of the private subnets only (production's group is the default one). Verified read-only on
2026-11-09: production has daily automated backups, encrypted under production's KMS key. The latest
automated backup is copied to a new snapshot under the sim's own KMS key, and the sim is restored from
that copy. This reads the backup, not the live instance. Backup retention on the sim is 1 day.

**Documents.** A new private `navix-finance-sim-documents` bucket with its own KMS key. The source
bucket name comes from SSM `/navix/dev/navix/storage/bucket`. CORS allows `https://app.dhanboost.com` and `https://dhanboost.com`, both of which serve the staff login.

**Backend.** A new `navix-sim` ECS service and task family, using an image built from the live code
(verified 2026-11-09: production runs `origin/main` at `ca4eb1e3`, schema V99). It runs with
`NAVIX_ENV=sim`, so it reads only `/navix/sim/*`.

| Setting | Value | Why |
|---|---|---|
| `AUTH_SECRET` | New random value | A sim token is rejected by production, and the reverse |
| Datasource, bucket, KMS | Sim resources only | Separation |
| `NAVIX_ALLOW_DEV_DEFAULTS` | `true` | The boot guard otherwise refuses mock eSign; it only relaxes that guard and PayU's sandbox check |
| `NAVIX_SMS_ENABLED` | `false` | No customer contact |
| `NAVIX_SMS_MOCK`, `NAVIX_SMS_DEV_ECHO` | `false`, with no SMS gateway credentials | Mock mode accepts `123456`, which would allow login as a real borrower |
| `NAVIX_WHATSAPP_ENABLED` | `false` | No customer contact |
| `NAVIX_EMAIL_ENABLED` | `false` | No customer contact, and no reset links in logs |
| `NAVIX_SES_EVENTS_ENABLED` | `false` | Must not consume production's queue |
| `NAVIX_ESIGN_PROVIDER`, `NAVIX_DIGIO_PROVIDER` | `mock` | Real eSign and eMandates are binding |
| `NAVIX_BUREAU_FIXTURE` | Non-blank | No billable bureau calls |
| Signzy, Digitap, Fintrix, DigiLocker, Digio, PayU, Resend credentials | **Not set** | Vendor and payment calls cannot authenticate |
| `NAVIX_REMINDERS_CRON`, `NAVIX_EMPLOYMENT_RETRY_CRON`, `NAVIX_PROVIDER_HEALTH_CRON`, `NAVIX_DIGIO_DEBIT_CRON`, `NAVIX_PAYU_RECONCILE_CRON` | `-` | Spring's disabled value; no outbound or debit jobs |
| `NAVIX_CAPTCHA_SECRET` | Not set | Production has none today, so there is nothing to match |

Known consequence: admin corrections gated by an OTP are expected not to work in the sim.

**Server-to-server address.** The website's server reaches the sim through a new CloudFront distribution
on its AWS-provided `*.cloudfront.net` HTTPS address, so no certificate or DNS record on `dhanboost.com`
is needed. Users never see this address. Caching is disabled and all viewer headers, including
`Authorization`, are forwarded.

**Load balancer.** A new `navix-sim-alb` with an HTTP listener that accepts traffic from CloudFront only.
Listener rules, in order:
1. `/api/auth/borrower/*` returns a fixed 403.
2. Requests with the correct `X-Sim-Gateway` header value forward to the sim service.
3. Everything else returns a fixed 403.

**Security groups.** ALB accepts 80 from CloudFront's managed origin-facing prefix list only. Task accepts
8080 from the ALB group only. Database accepts 5432 from the task group only.

## 5. Website changes

Server-only Vercel settings: `SIM_LOGIN_EMAIL`, `SIM_BACKEND_BASE_URL`, `SIM_GATEWAY_KEY`. If any is
unset, the feature is inert and the email fails as an ordinary wrong login.

| File | Change |
|---|---|
| `frontend/src/lib/config.ts` | Read the three settings |
| `frontend/src/lib/api/bff-session.ts` | Add `sim?: true` to `StaffBffSession` |
| `frontend/src/app/api/auth/staff/login/route.ts` | If the trimmed, lowercased email matches, call the sim backend with the gateway header and store `sim: true` |
| `frontend/src/lib/api/bff-proxy.ts` | Use the sim backend and gateway header only when the staff cookie has `sim: true` **and** its token equals the token being forwarded |
| `frontend/src/app/api/auth/staff/logout/route.ts` | Same routing |
| `frontend/src/app/api/auth/staff/me/route.ts` | Return `sim`, never the token |
| `frontend/src/components/staff/staff-shell.tsx` | Permanent SIMULATION banner |
| `frontend/src/lib/export/exporters.ts` | SIMULATION label on browser exports |

The token comparison matters because a browser can hold a sim staff session and a real borrower session
at once, and borrower calls must stay on production.

The session cookie is unsigned, so a forged `sim` flag is possible. It fails closed: the two backends
use different JWT secrets, so a token sent to the wrong backend gets a 401.

Forgot-password, reset-password and accept-invite stay on production. The sim admin does not use them.

**Label (fixed requirement).** Every screen and export shows SIMULATION, with no way to switch it off,
so invented figures cannot be mistaken for actual company performance. Backend PDFs
(`SanctionLetterPdfRenderer`, `CreditBriefPdfRenderer`, and any other backend-generated file found
during planning) carry the label when `NAVIX_ENV=sim`; production output is unchanged. Customer documents
are shown as originally uploaded; the label covers everything the system generates.

## 6. Preparing the copied data

Run in this order, on the sim database only:

1. Create `sim_marker`.
2. Change the database master and application passwords.
3. Disable every `staff_user`, clear active sessions, invalidate invites and reset tokens, turn off the
   `digio-mandate`, `digio-debit` and `payu-repayment` flags, and create the one ADMIN
   (`sim.admin@dhanboost.com`, BCrypt hash, password handed to the owner privately).
4. Measure the copy: first `disbursed_on`, total principal, and closed vs due by month.
5. Add training loans (§7).
6. Verify the targets on the dashboard.

Resetting after a training session means re-running the restore and these steps.

## 7. Reaching the target numbers

**Definitions**
- **Total disbursed** is the "Principal disbursed" card over all time: `sum(loan.principal)`.
  Net disbursed will show about ₹61.7 lakh, because fee and GST are deducted.
- **Monthly closure** is loans with `status = 'CLOSED'` ÷ all loans, grouped by `due_date` month, for
  completed months only. The average of those months is the target.

**Targets:** total within ±1% of ₹70,00,000; average monthly closure 60% ±2 points. Individual months
may vary when the amount available to add is too small to balance them.

**Method**
- Existing rows are not edited. Numbers move only by adding loans under made-up customers.
- Made-up customers are visibly fictional (names such as `Sim Trainee 0001`) with non-real identifiers.
  They have no documents; the copied real files are there for practising document review.
- Each added loan is complete, so drill-downs agree with the cards: `customer_profile`,
  `loan_application`, `application_event`, `loan`, `payment` (verified, for closed loans) and
  `collection_case` (for overdue loans).
- Amounts follow the product rules: 10% fee, 18% GST on the fee, 1% daily interest, in integer paise.
- `disbursed_on` falls between the first real disbursement and today.
- Open loans provide the cases staff practise closing. Each console stage also gets at least 5 made-up
  files: credit review, sanctioned, disbursement pending, active, overdue, and repayments awaiting
  verification.

**If the real total already exceeds ₹70 lakh,** adding loans cannot reach the target. Stop and ask the
owner before removing or changing anything.

**Expected drift:** figures move as trainees disburse and close cases. Resetting (§6) restores them.

## 8. Rollout

Steps 1–3 change nothing on production.

1. **Preconditions (§10).** Then confirm production's backup settings, read-only.
2. Create the infrastructure, restore the database, copy the documents, deploy the sim backend.
3. Prepare the data (§6) and test the sim on its own through its CloudFront address.
4. Release through the normal push to `main`, **only after the owner's OK**. CI then redeploys the website
   and the backend together. The code reaches `main` because a website-only deploy from a branch would be
   overwritten by the next push.
5. Run the post-release checks (§9).

All work happens on a branch built from the live commit, so nothing reaches `main` before step 4.

**Rollback:** redeploy the previous website version, which removes the sim route. The sim can then be
deleted without affecting production.

**End of use:** this environment holds real identity data, so it is deleted when training ends:
database with no final snapshot, bucket emptied and removed, `/navix/sim/*` parameters deleted.

## 9. Testing

**Automated:** routing tests for a normal login (production), the sim login (sim, gateway header sent),
a browser holding both a sim staff and a borrower session (borrower calls stay on production), and
missing settings (inert). Label tests for the banner and exports, with no label on a normal session.
PDF label present only when `NAVIX_ENV=sim`. Typecheck, lint and backend tests, checking real exit codes.

**On the sim before release**
- Backend boots with no vendor credentials.
- A request without the gateway header gets 403, and so does `/api/auth/borrower/*`.
- A signed-in request passes through CloudFront with its `Authorization` header intact.
- A direct request to the load balancer, bypassing CloudFront, is refused.
- A sim token is rejected by production's backend.
- Other staff accounts cannot log in.
- No SMS, WhatsApp or email leaves the sim; the logs confirm it.
- The dashboard shows the §7 targets, and drill-downs match the cards.

**After release**
- Normal staff login and borrower login still work and reach production.
- A marker change made in the sim does not appear in production's console.
- The running website deployment matches the released commit.

## 10. Preconditions (owner)

- **Compliance sign-off** for putting real Aadhaar, PAN, bureau data and documents in a training
  environment, obtained before step 2 of §8.
- **AWS access:** done. Profile `navix-dev` (verified 2026-11-09) can read production and create the sim
  resources.

## 11. Limits and cost

- One account allows one active session, so one person is signed in at a time. Trainer-led sessions
  work; individual trainee seats would need more accounts.
- Rough estimate $70–85/month while running, plus document storage. The ECS service can be scaled to
  zero and the database stopped between sessions; AWS restarts a stopped database after 7 days.
