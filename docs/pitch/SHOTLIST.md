# SHOTLIST.md — screenshots for the SoftSolutionsAI × DhanBoost pitch deck

The deck (`SoftSolutionsAI_DhanBoost_Pitch.pptx`) has **12 screenshot frames**. Until a file exists
they render as dashed placeholders, so the deck is already presentable — it just gets much better
with these. Capture them, save into `docs/pitch/shots/` under the exact basenames below, then:

```bash
python3 docs/pitch/build_pitch_deck.py    # re-reads shots/, embeds what's there
```

> **Use only seeded demo data.** Every persona in this list (Meera Krishnan, Ananya Rao, Priya Nair,
> Rahul Mehta, Vikram Shah, Deepa Iyer, Arjun Patel, Sana Khan) is a fixture created by
> `scripts/seed-demo-data.ps1`. **No real borrower PII goes in a deck that leaves the building** —
> not a real PAN, not a real mobile, not a real Aadhaar, not a real bank account.

## Setup

1. Bring the offline stack up and seed it — the "Before you record" checklist in
   [`DEMO_WALKTHROUGH.md`](../../DEMO_WALKTHROUGH.md) is the authority:
   `.\scripts\run-demo.ps1` then `.\scripts\seed-demo-data.ps1 -Verify` (every line must read PASS).
2. Browser at **1920×1080, zoom 100%**, DevTools closed, bookmarks bar hidden.
3. Staff sign-in: `/staff/login` → `meera.krishnan@navix.example` / `Admin@12345`; switch persona
   with the floating **"Act as role"** bar, bottom-right of every staff page.
4. Frames are **16:9**, so a full 1920×1080 grab drops in with no cropping. For composite shots
   (multiple routes in one frame) either stitch them side by side at 1920×1080, or just pick the
   single strongest screen — the deck reads fine either way.

## The 12 shots

| # | Save as | Capture | Persona / notes |
|---|---|---|---|
| 6 | `borrower-onboarding.png` | `/signup/pan` (best single screen) or a 2-up of `/signup/mobile-otp` + `/signup/pan` | Borrower. Run the backend with `NAVIX_SMS_MOCK=true` so the OTP screen shows the dev code instead of waiting on a handset. |
| 7 | `borrower-identity.png` | `/signup/digilocker` consent screen, or `/signup/selfie` with the liveness frame visible | Borrower. The selfie screen is the more striking of the two — camera permission prompt dismissed first. |
| 8 | `borrower-underwriting.png` | `/signup/penny-drop` or `/signup/salary` | Borrower. **Blur or fake the account number** even on seeded data — a visible account number in a deck reads badly. |
| 9 | `borrower-apply.png` | `/loan/apply` with an amount chosen so the cost breakdown is fully populated | Borrower. **The money slide of the borrower half** — make sure fee, GST, net disbursal, due date and total repayable are all on screen. |
| 10 | `borrower-repay.png` | `/repay` showing the "pay today" amount, or `/loan/status` with the trail expanded | Borrower with an ACTIVE loan (any seeded active persona). |
| 13 | `staff-dashboard.png` | `/staff/dashboard` (sparklines + pipeline bar), or `/staff/applications` | **KYC_APPROVER or ACCOUNTANT**, not ADMIN — a role-specific "N items need your action" tells the story better than admin's oversight view. Let the page finish loading; there's a brief placeholder while the session resolves. |
| 14 | `staff-verifications.png` | `/staff/verifications` — 5 tiles + 4 triage buckets; open the manual-override dialog if you want the stronger shot | ADMIN or KYC_APPROVER. Only tracks *pending* applications by design, so don't look for an approved one here. |
| 15 | `staff-kyc.png` | `/staff/kyc-approvals` with the "Approve instant loans" panel visible, or `/staff/kyc-review` | KYC_APPROVER (Ananya Rao). The reborrow-review queue shows loan history inline — good detail if it's populated. |
| 16 | `staff-credit.png` | `/staff/applications` → **Open** on a row → the detail popup, Journey tab | ADMIN or CREDIT_HEAD. Get the score/★ badge, the cost card and the audit log in frame. Don't click the credit-brief PDF download — no S3 offline, it won't resolve. |
| 18 | `staff-disbursement.png` | Disbursement panels with the txn-id input, or `/staff/accounting/transactions` | DISBURSEMENT_HEAD / ACCOUNTANT. On the ledger, set the period to **"All time"** first or it looks empty. |
| 19 | `staff-collections.png` | A filtered DPD-bucket pane from the Collections sidebar, or a case detail with the amount-due arithmetic | COLLECTION_HEAD (Arjun Patel). The amount-due card that spells out the interest and penalty arithmetic is the best shot on this page. |
| 20 | `staff-admin.png` | `/staff/customers/{id}` (customer 360 + change history), or `/staff/admin/all-applications` | ADMIN. If you show change history, make a salary edit first so there's a fresh audit row. Payment-settings QR/PDF previews stay blank offline — avoid that page. |

## Known-empty things (so you don't chase them)

Full table in [`DEMO_WALKTHROUGH.md`](../../DEMO_WALKTHROUGH.md) → "If something looks empty". The
three that catch everyone: the transactions ledger defaults to **this month**; the closed-loans panel
is lazy-loaded until expanded; S3-backed previews (credit-brief PDF, payment QR) don't resolve on the
offline stack.

## Optional extra credit

- **Record a 60–90s screen capture** of credit assignment and review (Credit Head assigns →
  Credit Executive decides, or the Head reassigns) and embed it on slide 17. That slide is the deck's
  differentiator and it plays far better as motion than as bullets.
- **Swap the palette** to SoftSolutionsAI's brand: `PALETTE` and `LOGO` at the top of
  `build_pitch_deck.py`. The DhanBoost navy/gold is the current default because this is a DhanBoost
  case study.

## Before presenting — the TODOs left in the deck on purpose

| Slide | Fill in |
|---|---|
| 26 "Three ways to start" | Commercials for all three engagement models |
| 27 / 28 | Whether DhanBoost is named as the reference client, or kept anonymous until the room is qualified |
| 28 | SoftSolutionsAI contact name, email, phone |
