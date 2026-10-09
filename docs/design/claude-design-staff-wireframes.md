# DhanBoost Staff Console — Claude Design wireframe spec

> Paste this whole file into Claude Design. It describes, from the shipped Next.js code **and verified against the running console on 2026-10-09**, three screens of the DhanBoost staff console (ADMIN login): the **Customers register**, the **customer pop-up** opened from a row (every tab, with sample data), and the **Admin dashboard**. Build them as high-fidelity wireframes that look identical to the live app. Colours, type sizes, spacing, radii, copy, column order and sample values below come from `frontend/src/app/staff/customers/page.tsx`, `components/staff/application-detail-dialog.tsx`, `components/staff/customer-tabs.tsx`, `app/staff/dashboard/page.tsx`, `components/staff/staff-shell.tsx`, `tailwind.config.ts` and `globals.css`.

---

## 0. Design system (use these exact tokens)

### Palette

| Token | Hex | Used for |
| --- | --- | --- |
| `navy` / `navy-800` | `#0C2540` | table header bar, active chips, primary buttons, big figures, headings accent |
| `navy-900` | `#081A31`| **sidebar background**, primary button hover |
| `navy-700` | `#12365C` | link hover |
| `navy-tint` | `#EAEFF6` | soft navy pills (status "Active" in Journey, "N pending"), avatar circle |
| `gold` (brand accent, actually **emerald**) | `#14A06B` | Sanction button, sidebar active bar, Disbursals sparkline, sidebar brand tag |
| `gold-dark` | `#0B6B46` | role label under the staff name, gold-50 pill text |
| `gold-soft` | `#A7E8CE` | "Your work" hero border, gold-accent StatCard border (e.g. "In queue now", "Unallocated") |
| `gold-50` | `#E7F6EF` | count circle in extra-action rows, Fast-track pill |
| `gold-400` | `#3FBF89` | "STAFF CONSOLE" tag in the sidebar |
| `ivory` | `#FDFBF6` | **page background** |
| `white` | `#FFFFFF` | cards, header bar, odd table rows, modal |
| `ink` | `#0C2238` | primary text, headings |
| `slate` | `#46566E` | default body text |
| `muted` | `#8593A6` | secondary text, labels, placeholders |
| `line` | `#EAE1D0` | every 1px border |
| `line-2` | `#E0D5BF` | chip borders, outline-button borders |
| `grey-50` | `#FBF7F0` | date-group header rows, borrower strip, hover rows, check cards |
| `grey-100` | `#F7F2E9` | **even table rows (zebra)**, icon-button hover |
| `grey-200` | `#EFE7D6` | table row hover |
| `success-600 / 700 / 100 / 50` | `#1C9B6A` / `#167A54` / `#C5E9D9` / `#E7F5EF` | ✓ Yes, Closed/Completed pills, Repaid tiles, green tradeline cells |
| `warning-800 / 100 / 50` | `#5A3D05` / `#F6E3B0` / `#FBF1D8` | Pending badges, "needs review" text, amber credit pill |
| `error-700 / 600 / 100 / 50` | `#8F1E18` / `#B3261E` / `#F9D4D1` / `#FCEBEA` | Overdue, Rejected, +DPD, Reject button, red delinquency figures |
| `info-700 / 100` | `#12365C` / `#D5E0EC` | Active / Sanctioned / Disbursed badges |
| `neutral-100 / 200 / 700 / 800` | `#ECEAE4` / `#DEDBD2` / `#374151` / `#1F2937` | Draft / Cancelled / Upcoming badges, document type pills, "Consent not given" |

### Typography

- **One family everywhere: Inter.** Headings Inter 700, letter-spacing −0.022em. Figures use tabular numerals.
- Staff pages are wrapped in `.navix-crm`: **base 11px, line-height 1.45**. Everything is deliberately dense.
- Tailwind scale (px): `xs` 9.6 · `sm` 11.2 · `base` 12.8 · `lg` 14.4 · `xl` 16 · `2xl` 19.2 · `3xl` 24 · `4xl` 28.8 · `5xl` 38.4.
- Table header text: **9.2px, 700, uppercase, letter-spacing .04em, white on navy**.
- Pop-up body text: **10.4px**. Tiny meta lines: 8.8px.

### Formats (these are what the live app renders)

- **Money**: `₹` + Indian grouping, **no decimals** unless there are paise: `₹5,000` · `₹2,43,600` · `₹8,900` · `₹3,29,126`. Never "₹1.3L".
- **Dates**: `08 Oct 2026`. **Date-times**: `08 Oct 2026, 09:59 pm` (12-hour, lowercase am/pm).
- Missing value: an em dash `—`, never 0.

### Geometry

- Radii: **12px** on cards/buttons/modals/table panels; pills 999px; small inputs 10px.
- Shadows: `shadow-sm` = `0 6px 18px -10px rgba(12,37,64,.18)`; modal `shadow-md` = `0 24px 50px -28px rgba(12,37,64,.30)`.
- Spacing unit 4px. Page padding **24px**.

### Buttons (`.btn`)

- Inter 700, 12.2px, padding 14×24, radius 12, 1.5px border. `btn-sm` = padding 11×18, 11px text.
- `btn-navy`: bg `#0C2540`, white. `btn-gold`: bg `#14A06B`, white. `btn-outline`: transparent, navy text, border `line-2`. `btn-icon`: 28px square, icon only (14px Lucide).
- Danger: bg `error-600`, white. Success: bg `success-600`, white.
- Small toolbar buttons (Refresh, Prev/Next, "Open →" in tables): border `line`, px 12 py 6, 9.6px, radius 12; hover bg grey-100.

### Badges (pill, 500 weight, size sm = px 8 py 2, 9.6px)

`success` bg #C5E9D9 / #115C40 · `warning` bg #F6E3B0 / #5A3D05 · `error` bg #F9D4D1 / #6f1712 · `info` bg #D5E0EC / #12365C · `neutral` bg #DEDBD2 / #374151.

Application status → tone: Draft `neutral` · Kyc Pending `warning` · Credit Exec Pending `warning` · Sanctioned `info` · Disbursement Pending `warning` · Disbursement Failed `error` · Active `info` · Overdue `error` · Closed `success` · Rejected `error` · Cancelled `neutral`. Labels are Title Case ("Credit Exec Pending").

**Credit pill** (bureau + score + stars): `Experian 670 · ★★★☆☆ 3.0` — amber (warning tones) for 2.5–3.4, green for ≥3.5, red below 2.5, grey when no rating.

### Icons

Lucide, stroke 1.5–2, 13–17px.

---

## 1. Staff shell (wraps every screen)

Desktop 1440×900. Fixed sidebar + fluid content column.

### Sidebar — 240px, full height, bg `#081A31`

- **Brand block** (px 20, py 16, bottom border `rgba(255,255,255,.10)`): 42×42 green rupee/growth-arrow logo tile, then **"DhanBoost"** (Inter 700, 16.9px, white) over **"STAFF CONSOLE"** (8.2px, 600, uppercase, letter-spacing .22em, `#3FBF89`).

- **Nav** (px 12, py 20). Group headings 8.7px 700 uppercase, colour `#7E9CC0`. Items: 17px icon + label, 11.2px, px 12 py 8, radius 12, colour `#AEC3D9`; hover `rgba(255,255,255,.05)` + white. **Active** = bg `rgba(255,255,255,.10)`, white 600, and a **3px emerald bar on the left edge** (reads like a rounded bracket).

- Groups and items for ADMIN, in order (Lucide icons):

  **OPERATIONS** — Dashboard `LayoutDashboard` · Live applications `Workflow` · Customers `Contact` ▸ (children, indented 16px with a faint left rule, 9.6px, `#7E9CC0`: Incomplete · Pending · Review · Approved · Disbursement Pending · Active · Overdue · Hold · Rejected · Closed · Unallocated) · Unallocated customers `UserX` · Verification Dashboard `ListChecks` · My decisions `History` · Staff performance `Gauge` · Leads `Phone` · Import leads `Upload` · Telecalling `Phone` · *(Referral payouts* `Gift` *only when the referral flag is on — hidden today)*

  **COLLECTIONS** — DPD buckets `HandCoins` ▸ (children with a right-aligned count pill `rgba(255,255,255,.10)`, 8px: Upcoming · 1–7 DPD · 8–30 DPD · 31–60 DPD · 61–90 DPD · 90+ DPD) · Settlements `HandCoins` · Loans `Landmark` ▸ (● Active green dot · ● Overdue red dot · Closed)

  **ADMINISTRATION** — Staff `Users` · DSA `Briefcase` · Invites `Mail` · Blocklist `Ban` · Payment settings `CreditCard` · Company expenses `Wallet` · Leads dashboard `Phone` · All applications `Files` · Rejections `Ban` · Provider API dashboard `ListChecks` · Transactions `Receipt`

### Top header — sticky, white, bottom border `line`, px 24 py 12, contents right-aligned

**Search pill** (rounded-full, border `line`, \~190px: magnifier + muted "Search..." + a small kbd chip "Ctrl K") · **Profile** (two right-aligned lines: "Meera Krishnan" 11.2px 600 ink / "Administrator" 9.6px `#0B6B46`; then a 36px circle `#EAEFF6` with "MK" navy 700) · **Bell** with a red "99+" count bubble · **Sign out** (border `line`, `LogOut` 15px + "Sign out", 11.2px muted).

### Main

bg `#FDFBF6`, padding 24. `PageHeader`: **h1 24px** ink 700 + muted 11px subtitle, actions right, mb 24.

---

## 2. Screen A — Customers register (`/staff/customers`)

### 2.1 Page header

**Customers** · *Every borrower — search by name or ID, then open to see loans, payments and KYC.* · right: `[ ⬇ Export ▾ ]` and `[ ↻ Refresh ]` (small toolbar buttons).

### 2.2 Segment chips (mb 12, wrap onto two rows, gap 6)

`.cal-preset` pills: padding 7×15, radius 999, border `#E0D5BF`, white, 10.5px 600 slate; **selected = navy bg, white**. Live counts:

`All (11216)` **selected** · `Incomplete (1577)` · `Pending (0)` · `Review (2)` · `Approved (8)` · `Disbursement Pending (0)` · `Active (79)` · `Overdue (56)` · `Hold (0)` · `Rejected (9484)` · `Closed (10)` · `Unallocated (11206)`

(For the wireframe you may use smaller numbers: 248 / 31 / 22 / 17 / 9 / 6 / 83 / 14 / 2 / 38 / 26 / 19.)

### 2.3 Toolbar (mb 16, gap 8)

1. **SearchBar**: pill input \~250px, magnifier left, placeholder *"Name, PAN, mobile, customer or application ID"*, border `line`, radius 999, py 8 → then `btn-sm btn-navy` **"🔍 Search"** (pill-shaped).
2. **Date filter**: a pill-shaped grey-50 track (border `line`, p 4) holding 4 pills 9.6px 600: `All time` (selected: navy pill, white) · `Today` · `Yesterday` · `Custom`.
3. Right-aligned 9.6px muted: **"Sorted by stage date ↓"**.

### 2.4 Register panel

White panel, border `line`, radius 12, shadow-sm; horizontally scrollable table; pagination footer inside.

**Table** (`.staff-data-table`): min-width 1344px (scrolls sideways); cells padding 8×14, bottom border `line`; **header navy, white 9.2px uppercase 700**; **even rows** `#F7F2E9`; hover `#EFE7D6`; the **checkbox column is sticky left** (ADMIN sees it) and the **"Open" column is sticky right** with a soft shadow edge; `Amount`, `Loans`, `Outstanding` right-aligned tabular.

**Columns (22):** `S.No.` · `☐` (sticky) · `Customer` · `Date` · `Mobile` · `PAN` · `Account` · `IFSC` · `Loan` · `Amount` · `Due` · `Owner` · `Loans` · `Outstanding` · `Bureau` · `Failure` · `Latest status` · `Stage date` · `Credit exec` · `Disbursed by` · `Collections exec` · `Open` (sticky)

**Date-group header rows**: full-width, bg grey-50, px 12 py 8, `ChevronDown` 14px + bold ink **"08 Oct 2026 · 4 customers"**. Groups by stage-date, newest first.

**Cells:**

- Customer: 24px circle `#EAEFF6` with a navy `Contact` 13px icon; **NAME IN CAPS as stored** (ink 600) over `#2296807` (9.6px muted link).
- Mobile / Loan: tabular muted. PAN / Account / IFSC: tabular ink.
- Amount: `₹25,000 req` (ink figure + tiny muted "req") or `elig ₹8,900` (both muted).
- Due: ink date; **overdue → error-700 bold date + "+12d"**.
- Owner: ink name or muted "Unallocated". Loans: `2 / 2 apps`. Outstanding: bold `₹5,050`.
- Bureau: credit pill (`Experian 670 · ★★★☆☆ 3.0` amber; `Experian 765 · ★★★★★ 4.5` green) or muted "Not fetched".
- Failure: muted "—" or a `neutral` badge, e.g. **"Consent not given"**.
- Latest status: StatusBadge. Stage date / Credit exec / Disbursed by / Collections exec: muted.
- Open: 28px outline icon buttons `UserPlus` (Assign, only on actionable rows) · `X` (Reject, same) · `Pencil` (Edit) · `Info` (Quick summary) · then **"Open →"** as a small outline pill button.

**Sample rows** (group "08 Oct 2026 · 4 customers", then "07 Oct 2026 · 21 customers" showing 4):

| \# | Customer | Date | Mobile | PAN | Account | IFSC | Loan | Amount | Due | Owner | Loans | Outstanding | Bureau | Failure | Latest status | Stage date | Credit exec | Disbursed by | Collections exec |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | **VIJAY RAJPAL POHAAL** #2296807 | 08 Oct 2026, 09:59 pm | 9702296807 | BRIPP0815L | 010510110001154 | BKID0000105 | #208 | ₹5,000 req | 10 Nov 2026 | Unallocated | 2 / 2 apps | ₹5,050 | Experian 670 · ★★★☆☆ 3.0 | Consent not given | **Active** | 08 Oct 2026, 10:05 pm | — | DhanBoost Admin | — |
| 2 | **RAMACHANDRA RAM PRAKASH** #3001693 | 08 Oct 2026, 06:51 pm | 6303001693 | FZJPP7536R | 181810100001112 | UBIN0818186 | #207 | ₹8,000 req | 05 Nov 2026 | Unallocated | 2 / 2 apps | ₹8,060 | Experian 690 · ★★★☆☆ 3.0 | Consent not given | **Active** | 08 Oct 2026, 09:53 pm | — | DhanBoost Admin | — |
| 3 | **SUBASINI PADHAN** #7715378 | 08 Oct 2026, 09:02 pm | 7477715378 | HUUPP0838J | 1851166000025707 | KVBL0001851 | — | ₹6,000 req | — | Srishti Jaiswal | 1 / 2 apps | ₹0 | Experian 765 · ★★★★★ 4.5 | Consent not given | **Sanctioned** | 08 Oct 2026, 09:02 pm | — | — | Srishti Jaiswal |
| 4 | **NIRBHAY KUMAR** #2633167 | 08 Oct 2026, 06:11 pm | 8882633167 | KRNPK5384P | 50100740668098 | HDFC0001667 | #206 | ₹8,000 req | 05 Nov 2026 | Unallocated | 2 / 2 apps | ₹8,060 | Experian 697 · ★★☆☆☆ 2.5 | Consent not given | **Active** | 08 Oct 2026, 08:21 pm | — | DhanBoost Admin | — |
| 5 | **MEENA BHANDARI** #7021574 | 07 Oct 2026, 11:34 am | 9897021574 | ATOPD1161E | 06401000021721 | PSIB0000640 | — | elig ₹7,500 | — | Unallocated | 0 / 1 apps | ₹0 | Experian 793 · ★★★★★ 4.5 | — | **Rejected** | 08 Oct 2026, 12:14 am | DhanBoost Admin | — | — |
| 6 | **—** #7368 | 07 Oct 2026, 10:40 pm | 9958767868 | DDNPP7868C | — | — | — | — | — | Unallocated | 0 / 1 apps | ₹0 | Not fetched | Consent not given | **Draft** | 07 Oct 2026, 10:40 pm | — | — | — |
| 7 | **ANJALI VERMA** #1040 | 07 Oct 2026, 08:05 am | 9988776655 | CDEPV9012M | 12345678901234 | SBIN0005678 | #199 | ₹20,000 req | **28 Sep 2026 +11d** (red) | Kiran Das | 2 / 2 apps | ₹28,400 | Experian 611 · ★★☆☆☆ 2.0 | — | **Active** | 07 Oct 2026, 08:30 am | Neha Kapoor | DhanBoost Admin | Kiran Das |
| 8 | **RAHUL NAIR** #1039 | 07 Oct 2026, 05:22 pm | 9012345678 | DEFPN3456N | 65432109876543 | AXIS0009876 | — | ₹18,000 req | — | Neha Kapoor | 0 / 1 apps | ₹0 | Experian 698 · ★★★☆☆ 3.0 | — | **Credit Exec Pending** | 07 Oct 2026, 05:45 pm | — | — | — |

Row 1's **Open →** launches the pop-up in §3.

### 2.5 Pagination footer (top border `line`, px 12 py 8, 9.6px muted)

"Showing 1–25 of 11216" · right: "Rows per page \[25 ▾\]" · `[‹ Prev]` (disabled 40%) · "Page 1 of 449" · `[Next ›]`.

### 2.6 Variants

- **Bulk bar** when ≥1 ticked: "**2 selected** · \[Assign\] \[Reject\] \[Clear\]".
- **Empty**: centred "No customers for “xyz”." (11.2px ink 500).
- **"My customers"** pill (`#EAEFF6`, navy 600) beside the date filter when `?mine=1`.

---

## 3. Screen B — The customer pop-up (unified application/customer dialog)

Opened by **Open →** on **VIJAY RAJPAL POHAAL** (#2296807, application **#11489**, loan #208). One modal with both the application tabs and the customer tabs.

### 3.1 Modal frame

- Overlay `rgba(20,43,80,.55)`, centred, fade-up 250ms.
- Panel: white, radius 12, **width 80vw** (≈1152px), padding **38px 34px**, shadow-md, max-height `100dvh − 48px`. The body below the tab strip is its own scroller (max 80vh); the header + tabs stay put.

### 3.2 Header (bottom border `line`, pb 12)

- **Title** 14.4px navy 700: **VIJAY RAJPAL POHAAL** + muted 11.2px normal **"— Application #11489"**.
- Meta line (9.6px muted, gap 8): `#2296807` · `9702296807` · `PAN BRIPP0815L` · `risk C` · StatusBadge **Active** · credit pill **Experian 670 · ★★★☆☆ 3.0**.
- Right: outline pill **"Full customer page ↗"** (9.6px 600 navy) and a **✕** (18px, muted) in the corner.
- Row 2 (mt 12): the stage action cluster. ACTIVE file = empty. *Variants to draw:* Credit Exec Pending → `[ ✓ Sanction ]` btn-gold · `[ Reassign ]` outline · `[ ✕ Reject ]` red · `[ Mark pending ]` outline. Disbursement Pending → input *"Transaction id"* + `[ Release ]` green + `[ Fail ]` red.

### 3.3 Tab strip (mt 8, bottom border `line`, wraps to **two rows**)

Tab = 9.6px 600, px 12 py 8, 2px bottom border; active navy/navy, inactive muted/transparent. Row 1: `Overview` **(active)** · `Journey` · `Verifications` · `Documents` · `Past details` · `Audit log` **(navy-tint count pill "6")** · `Personal` · `Employment & salary` · `Bank` · `Credit report`Row 2: `Loan applications` · `Calls & remarks` · `Skip Tracer` · `Customer activity`

### 3.4 Shared primitives (body 10.4px)

- **Section**: white, border `line`, radius 12, p 12; title 9.6px 600 uppercase tracking-wide muted, mb 8.
- **KV row**: label muted left, value ink right-aligned, py 4; mono values tabular; empty "—".
- **Bool**: `✓ Yes` success-700 with 13px check, or muted "No".
- **StaffFieldTable** (Bank/Credit tabs): a mini `.staff-data-table` with navy header `S.NO. · FIELD · VALUE · SOURCE`, zebra rows, field names bold.
- **FocusCard** (Overview): white, border `line`, radius 12, p 16; title = 15px icon + 11.2px navy 600.
- **Headline**: label 9.6px 600 uppercase muted → **figure 24px navy 700** → caption 9.6px muted; bottom border, pb 12, mb 12.

### 3.5 Tab: Overview (ADMIN sees every focus card)

1. **Borrower strip** — grey-50 box, border `line`, p 16, 3-col KV: Name **VIJAY RAJPAL POHAAL** · Mobile `9702296807` · PAN `BRIPP0815L` · Employer **MIJ tibbia college an Haji AR kalshekar hospital** · Monthly salary **₹35,640** · Eligible limit **₹8,900**
2. **Wait-time box** — border `line`, p 16, 3-col KV: Waiting since submission **17 hours** · At this stage for **16 hours** · Marked pending **—** · Applied on **08 Oct 2026, 09:59 pm**
3. **FocusCard** `ShieldCheck` **"KYC & verification"** — Headline "REQUIRED CHECKS CLEARED" **8 / 8**, caption "100% complete". 2-col KV; a passed check shows `✓ Passed` with an 8.8px muted provider line beneath: PAN **Not run** · Aadhaar (DigiLocker) `✓ Passed` / "DIGILOCKER · 08 Oct 2026, 09:59 pm" · Email **Not run** · Bureau **Not run** · Salary **Not run** · Address `✓ Passed` / "SIGNZY · 08 Oct 2026, 09:59 pm" · Penny drop **Not run** · Identity match **100%**. Footer 8.8px: *Per-check detail and manual overrides are on the Verifications tab.*
4. **FocusCard** `Gauge` **"Credit"** — Headline "REQUESTED" **₹5,000**, caption "against a sanctioned amount of ₹6,000". 2-col KV: Experian score `670` · Star rating **3.0★** · Recommendation **REFER — MANUAL REVIEW** · Risk category **C** · Bureau **DIGITAP_EXPERIAN** · Purpose **—** · Approved repayment date **10 Nov 2026** · Sanction tenure **33 days** · Sanctioned on **08 Oct 2026, 09:59 pm** · Sanction remarks **Carried over from application 8916**. Then a grey-50 note box: *"Vijay Rajpal Pohaal presents a fair credit profile (bureau score 670) with 0 reported defaults across 110 accounts (19 active). Total outstanding exposure is ₹604,530, predominantly secured (54%) — a healthy debt mix. Recent enquiry activity is low; refer for manual underwriting review.*"Below a top border, four blocks with 9.6px uppercase muted labels:
   - **DELINQUENCY HISTORY ⓘ** — 2-col KV, bad values in **error-700**: Worst DPD (12m) **900+ days** · Worst DPD (24m) **900+ days** · Worst DPD (36m) **900+ days** · Accounts ever 30+ dpd **5** · Currently past due **0** · Written-off / settled **6** · Oldest account opened **28 May 2015**
   - **ENQUIRY VELOCITY** — 4 inline KV: Last 7d **1** · Last 30d **3** · Last 90d **4** · Last 180d **5**
   - **TOP EXPOSURES** — 4 small grey-50 boxes (lender name 9.6px muted over bold amount): Aavas Financiers Limited ₹3,29,126 · WHIZDM FINANCE PRIVATE LIMITED ₹41,988 · FINC FRIENDS PRIVATE LIMITED ₹40,797 · SOLOMON CAPITAL PRIVATE LIMITED ₹39,345
   - **TRADELINES** — "110 tradelines" + right link "Show all 110 (+90 closed/settled)"; navy-header table `Lender · Type · Status · Opened · Closed · Balance · Past due · Worst DPD · Payment history`; rows: Aavas Financiers Limited / XXXXXXXXXXX1402 · Type 44 · `Active` (success) · 31 Jul 2017 · — · ₹3,29,126 · ₹0 · 0 days · a 9×4 grid of tiny green "0" cells + "Newest → oldest, one cell per month"; WHIZDM FINANCE · Short Term · Active · 01 Jun 2026 · … ; SOLOMON CAPITAL PRIVATE LIMITED · Personal Loan · Active · 27 Aug 2026 · — · ₹39,345 · ₹0 · 0 days · *No payment history reported*.
5. **FocusCard** `Banknote` **"Disbursement"** — Headline "TRANSFERRED TO BORROWER" **₹4,410**, caption "₹5,000 principal − ₹500 fee − ₹90 GST". 2-col KV: Account number `010510110001154` · IFSC `BKID0000105` · Account holder **VIJAY RAJPAL POHAAL** · Bank **Bank of India** · Penny drop **Verified** (success-700 bold) · Name match at bank **100%** · Transaction ref `INDBH08109107420` · Disbursed on **08 Oct 2026** · Repayment due **10 Nov 2026** · Contracted repayable (on-time) **₹6,650**.
6. **FocusCard** `PhoneCall` **"Amount due"** — Headline "NET AMOUNT DUE TODAY" **₹5,050**, caption "Due 10 Nov 2026 — not yet overdue". Label "HOW IT ADDS UP" + 8.8px note *Interest runs from disbursal on 08 Oct 2026 and stops on the due date 10 Nov 2026; the grace day adds one normal-interest day without penalty. The 2% daily penalty starts the following day and is capped at 30 days.* 2-col KV: Principal `₹5,000` · + Interest accrued (1%/day × 1 day) `₹50` · + Late penalty (2%/day, ≤30d) `₹0` · − Paid (verified) `₹0` · **= Net due ₹5,050**. Divider; Loan status **ACTIVE** · Disbursed on **08 Oct 2026** · Net disbursed `₹4,410` · Contracted repayable (on-time) `₹6,650`.
7. **FocusCard** `PhoneCall` **"References"** (pencil icon button top-right for ADMIN) — 2-col KV: **Friend · Shafi haji Shaikh** `8252605758` · **Relative · Gaurav** `8591171584`.

### 3.6 Tab: Journey

- White card p 16. Assignee line 9.6px muted: **"Unassigned · in this stage since 08 Oct 2026, 10:05 pm"** (or "Assigned to Neha Kapoor …").
- **Vertical stepper**: a 2px vertical rule with 28px circles. Done = green circle with white ✓; current = white circle with a navy ring + dot; upcoming = grey outline circle. Each row: bold label + a small pill — `Completed` (success) / `Active` (navy-tint) / `Upcoming` (neutral) — and a 9.6px muted line "08 Oct 2026, 09:59 pm · VIJAY RAJPAL POHAAL" (or "Not yet reached"). Rows: Started ✓ · KYC ✓ · Credit review ✓ · Disbursement ✓ (… · DhanBoost Admin) · **Active & repayment** ● Active (08 Oct 2026, 10:05 pm · DhanBoost Admin) · Closed ○ Upcoming.
- **Cost card** (`Banknote` "Cost"): "Due 10 Nov 2026" then a 2-column `<dl>`: Principal ₹5,000 · Processing fee ₹500 · GST ₹90 · Net disbursed ₹4,410 · Contracted repayable (on-time) ₹6,650 · Interest accrued (1d) ₹50 · Paid (verified) ₹0 · **Amount due today ₹5,050** · Disbursed on 08 Oct 2026 · Repayment due 10 Nov 2026 (sub-hint "On the borrower's salary day").

### 3.7 Tab: Verifications

*(Not screenshot-verified: this tab froze the dev browser; drawn from code.)* A vertical list of check cards (grey-50, border `line`, radius 12, p 12): check name 11.2px navy 600 left, StatusBadge right, provider line, 2–4 KV facts, then small outline buttons `[ Details ]` `[ Override ]` `[ ↻ Retry ]`. Rows: Pan · Aadhaar · Email · Bureau · Salary · Employment (advisory `info` tone) · Selfie · Address · Penny Drop · Esign. Use `success` PASS for Aadhaar/Address/Selfie/Esign and `neutral` PENDING "Not run" for the rest to match this file.

### 3.8 Tab: Documents (grouped — every application of this customer)

- **Section "CUSTOMER DOCUMENTS"**: grouped by type with a 9.6px bold sub-heading; each row is a bordered card (radius 8, p 10): `FileText` icon · **file name** · 8.8px muted "Application #8916 · uploaded 16 Sept 2026, 06:45 pm" (+ "Password: **Vija2201**" when set) · right: a `neutral` type pill ("Salary slip"), an outline **"↗ View"** button and a red-text **"🗑 Delete"** (ADMIN). Groups: **Bank statement** `bank-statement-1` · **Salary slip** `salary-slip-1`, `-2`, `-3` · **AADHAAR** … · **Selfie** `selfie.jpg` (uploaded 17 Sept 2026, 12:46 pm).
- Label "APPLICATION DOCUMENTS", then a collapsible box **"⌄ Application #11489 · Active"** with a count pill `3`: `credit-brief.pdf` (Credit brief) · `sanction-letter.pdf` (Sanction letter) · `sanction-letter-signed.pdf` (Signed agreement). Below: a dashed grey-50 area **"UPLOAD / REPLACE A DOCUMENT"** with a category select, file picker and `[ ⬆ Upload ]` btn-navy.

### 3.9 Tab: Past details

- **Loan history** table (S.No. · Loan · Principal · Disbursed · Due · Status · Outstanding): #208 · ₹5,000 · 08 Oct 2026 · 10 Nov 2026 · Active · ₹5,050 / #122 · ₹5,000 · 17 Sept 2026 · 10 Oct 2026 · Closed · ₹0.
- **OTHER APPLICATIONS (1)**: **#8916** (navy link) · Closed · right `₹5,000`.
- **PAYMENTS (1)**: `₹6,050 · UPI · 08 Oct 2026` + `Verified` (success) pill.

### 3.10 Tab: Audit log (badge 6)

Vertical **EventTimeline**: 32px circular icon dot per event (tone bg), 2px `line` connector, **bold label** + muted "FROM → TO", 9.6px muted "by actor · ROLE · date-time", optional note:

1. `FilePlus` neutral **Application created** · by system · 08 Oct 2026, 09:59 pm
2. `Repeat` info **Reborrow started** DRAFT → SANCTIONED · by VIJAY RAJPAL POHAAL · BORROWER · 08 Oct 2026, 09:59 pm · "Carried over from application 8916"
3. `Send` info **Accepted for disbursal** SANCTIONED → DISBURSEMENT_PENDING · by BORROWER · 08 Oct 2026, 10:00 pm
4. `Banknote` success **Transfer confirmed** DISBURSEMENT_PENDING → DISBURSED · by DhanBoost Admin · ADMIN · 08 Oct 2026, 10:05 pm · "Txn/ref: INDBH08109107420"
5. `CheckCircle2` success **Loan activated** DISBURSED → ACTIVE · by ADMIN · 08 Oct 2026, 10:05 pm
6. `Circle` neutral **Updated** · 08 Oct 2026, 10:05 pm

### 3.11 Tab: Personal (2-column grid of Sections)

Left: **IDENTITY & PROFILE** (Full name VIJAY RAJPAL POHAAL · PAN `BRIPP0815L` · Aadhaar number — · Mobile `9702296807` · Email vijaypohaal126@gmail.com · Official (work) email Acc.mumtibbia@gmail.com · Date of birth 1981-01-22 · Address RAHUL DREAM, B.WING, NEAR MOT HER MARY'S SCHOOL, RAHUL PARK, 8V74+RQH, JESAL PARK ROAD, BAN DARWADI, BHAYANDAR EAST, … MAHARASHTRA 401105, INDIA) · **PAN (PROVIDER)** · **AADHAAR (DIGILOCKER)** (Name on Aadhaar · masked number · DOB · Gender · Address · Address line "B/207, KANTI PARADISE …" · Landmark R.N.P Park · State MAHARASHTRA · District THANE · City THANE · PIN code 401105 · Country INDIA · Aadhaar signer DS DIGITAL INDIA CORPORATION · Status `✓ Yes`) · **PAN CARD (SIGNUP UPLOAD)** · **EMERGENCY CONTACT** · **OWNER** (select "Assign to \[Unallocated ▾\]" + `[ Save ]`). Right: **VERIFICATION** (PAN verified `✓ Yes` · Aadhaar (DigiLocker) **No** · Aadhaar linked `✓ Yes` · Work email (employer match) `✓ Yes` · Personal email (OTP) `✓ Yes` · Work email (OTP) `✓ Yes` · Address verified `✓ Yes` · Penny drop `✓ Yes` · Identity match 100%) · **EMAIL (PROVIDER)** · **AADHAAR CARD (SIGNUP UPLOAD)** · **AADHAAR CARD (DIGILOCKER FALLBACK)** ("Not used — DigiLocker completed, or the step has not been reached.") · **COMPLIANCE**. Full width: **LOAN COST CALCULATION** (same `<dl>` as §3.6) and the link **"Open full customer page ↗"**.

### 3.12 Tab: Employment & salary

Two columns: **EMPLOYMENT & SALARY (DECLARED)** — Employer MIJ tibbia college an Haji AR kalshekar hospital · Employment status SALARIED · UAN — · Salary bank Bank of India · Last salary received 10 Sept 2026 · Salary day Day 10 · Monthly salary ₹35,640 · Annual salary ₹4,27,680 · Salary % — · Increment % — · Eligible limit **₹8,900** + `neutral` badge "Salary rule". **EMPLOYMENT (EPFO)** — Status "REVIEW — Carried over from application 8916 — EPFO shows ANJUMAN-I-ISLAM, DR. MOHD. IJTU MEDICAL COLLEGE & HARKT HOSPITAL, not the declared employer — manual review" · Declared employer … · Employer on record ANJUMAN-I-ISLAM, DR. MOHD. IJTU MEDICAL COLLEGE & HARKT HOSPITAL · Employer name match **No** · Employee name match Yes · Currently employed Yes · Date of joining 2006-09-29 · Date of exit — · Tenure 239 months · UAN `100412452483` · UANs matched 1 · Matched on mobile · Establishment id `MHBAN0027944000` · Recent PF filing — · PF filing details — · Employer confidence 0.50. Full width: **SALARY SLIPS** — `salary-slip-1/2/3` rows ("Application #8916 · uploaded 16 Sept 2026, 06:45 pm", pill "Salary slip", `[↗ View]`).

### 3.13 Tab: Bank

- Intro 9.6px muted: *No dedicated bank-account entity — synthesized from the salary bank on the KYC profile, the latest penny-drop verification, and disbursal transaction refs on loans.*
- **SALARY BANK** field table (Source "KYC profile"): Bank Bank of India · Account `010510110001154` · IFSC `BKID0000105` · Account mobile `9702296807` · Penny drop verified `✓ Yes`.
- **BANK STATEMENTS**: `bank-statement-1` (Password: Vija2201). **CANCELLED CHEQUE / PASSBOOK**: centred "No cancelled cheque or passbook uploaded."
- **PENNY-DROP DERIVED**: muted "No penny-drop on this application — verification was carried over from a previous application."
- **DISBURSAL TXN REFS** table: 1 · #208 · `INDBH08109107420` / 2 · #122 · `INDBH09179021117`.

### 3.14 Tab: Credit report

- Top white card p 20: **CreditScoreGauge** — a thick semicircular arc segmented **red → orange → yellow-green → green** with curved labels POOR / FAIR / GOOD / EXCELLENT, end labels 300 and 900, a navy needle with a gold dot at the pivot, big **670** (38px navy 700) over "CREDIT SCORE" (letter-spaced caption), then an amber pill **REFER — MANUAL REVIEW** and "★★★☆☆ 3.0 / 5".
- **CreditProfileCard** (same headline, A/B/C facts, tradeline + enquiry tables, collapsible raw response).
- **UNDERWRITING & BRIEF METADATA** field table: Risk category C (Customer profile) · Bureau DIGITAP_EXPERIAN (Customer profile) · Credit brief generated 16 Sept 2026, 06:46 pm (Credit brief).
- **BUREAU PULL (RAW VERIFICATION DIAGNOSTICS)** field table (Source "Bureau provider"): Source — · No record — · Active accounts — · Overdue / defaults — · Total balance —.

### 3.15 Tab: Loan applications

- **APPLICATIONS (2)**: **#11489** · Active / "Assigned to — · in stage since 08 Oct 2026, 10:05 pm" · right `₹5,000`; **#8916** · Closed / "Assigned to Abhishek pandey · in stage since 08 Oct 2026, 09:55 pm" · `₹5,000`.
- **LOANS (2)**: line "Total principal **₹10,000** · Outstanding **₹5,050** · Last verified payment **08 Oct 2026**"; **LoanCard** "Loan #208 · ₹5,000" + pill `ACTIVE` with the breakdown `<dl>`; **LoanCard** "Loan #122 · ₹5,000" + pill `CLOSED` (Interest accrued (21d) ₹1,050 · Paid (verified) ₹6,050 · Amount due today ₹0 · Disbursed on 17 Sept 2026 · Repayment due 10 Oct 2026 · Closed on 08 Oct 2026).
- **PAYMENTS (1)**: ₹6,050 · UPI · 08 Oct 2026 · `Verified`.

### 3.16 Tab: Calls & remarks

- Form: two selects side by side with 9.6px bold labels — **Call type** \[Outbound ▾\] · **Outcome** \[Connected ▾\]; then **Relates to loan** \[— not loan-specific — ▾\]; a 3-row textarea *"Call notes…"*; a small navy pill button **"✓ Log call"**.
- Log rows (bordered card): **OUTBOUND · CONNECTED** / multi-line notes "2nd Number… 8591171584 Gaurav Reference number 8652605758 Shafi shaikh" / 8.8px muted "Abhishek pandey · 17 Sept 2026, 02:44 pm".
- **REMARKS** section: textarea *"Add a remark about this customer…"* + `[ Add remark ]`.

### 3.17 Tab: Skip Tracer

Intro 11px slate: *Looks up alternate mobiles, emails and addresses from Digitap for this customer. Each run is a paid lookup.* · a small navy pill **"🔍 Find contacts"** · empty state centred: **"No skip trace has been run for this customer."** / 9.6px muted *"Use Find contacts when their registered details stop working."*

### 3.18 Tab: Customer activity

- Filter chips (9.6px 600, rounded-full; selected navy): `All (71)` · `Lifecycle (14)` · `Profile edit (19)` · `Verification (19)` · `Reference (4)` · `Upload (14)` · `Call (1)`.
- Section header per application: **"#11489 · ₹5,000"** + `Active` badge + navy-tint pill **"Active & repayment"** + muted "Assigned to — · in stage since 08 Oct 2026, 10:05 pm".
- Entries: an 8px uppercase type pill (LIFECYCLE navy-tint · PROFILE gold-50 · VERIFICATION info-50 · REFERENCE neutral · UPLOAD grey-200 · REMARK grey-100 · CALL success-50), **bold title**, right 8.8px date-time, muted detail, "by ACTOR": LIFECYCLE **Activate** "DISBURSED → ACTIVE · loanId=208" by ADMIN · 08 Oct 2026, 10:05 pm / LIFECYCLE **Validate success** "DISBURSEMENT_PENDING → DISBURSED · Txn/ref: INDBH08109107420" by ADMIN / LIFECYCLE **Accept offer** "SANCTIONED → DISBURSEMENT_PENDING · amountPaise=500000" by BORROWER · 10:00 pm / PROFILE **Updated Sanctioned amount paise** "500000 → 600000" by DhanBoost Admin / REFERENCE **Reference 2** "Gaurav · 8591171584 · Relative" by VIJAY RAJPAL POHAAL · 09:59 pm / REFERENCE **Reference 1** "Shafi haji Shaikh · 8252605758 · Friend" / VERIFICATION **Address check** "PASS · SIGNZY · Carried over from application 8916 — Address resolved" / VERIFICATION **Selfie check** "PASS · SIGNZY · score 100 · Carried over from application 8916 — Live selfie matched to Aadhaar photo" / VERIFICATION **Aadhaar check** …

---

## 4. Screen C — Admin dashboard (`/staff/dashboard`, role ADMIN)

Section headings: **h2 16px** ink 700 + ⓘ (14px muted `Info`); 32px between sections.

### 4.1 Page header

**"Welcome, Meera"** · *"Administrator · your work, decisions and borrowers"* · right `[ ↻ Refresh ]`.

### 4.2 "Your work" hero (mb 32)

White card, **border** `#A7E8CE`, radius 16, p 24, shadow-sm.

- Left: "Your work" (14.4px 700) + ⓘ · muted "Live pipeline" · **38px navy 700 "10"** + "items need your action".
- Right: `[ Open queue → ]` btn-sm btn-navy (pill).
- mt 20: navy-tint pill **"🕒 Oldest waiting · #10481 · 240.1h in stage →"** and muted "9 items over 24h waiting · 7 over 48h"; then a **one-row QueueTable** (§4.3 columns): 1 · #10481 · #3682534 · 24 Sept 2026, 05:52 pm · PRAGYAN CHAND PANDEY · 9473882534 · BPWPP2934J · 039901595703 · ICIC0000399 · … · Actions; then its own pagination footer "Showing 1–1 of 1 · Rows per page 25 · Page 1 of 1".

### 4.3 Queue section

Heading **"Live pipeline"** + ⓘ; right pill (navy-tint, 11.2px 600) **"10 pending"**. **QueueTable columns:** `S.NO.` · `APPLICATION` (bold "#11462") · `CUSTOMER ID` (muted "#3968873") · `DATE` · `CUSTOMER` (bold caps) · `MOBILE` · `PAN` · `ACCOUNT` · `IFSC` · `LOAN` · `AMOUNT` · `DUE` · `CREDIT` · `CREDIT EXEC` · `DISBURSED BY` · `COLLECTIONS EXEC` · `ACTIONS` (sticky right: a 28px outline **ⓘ** icon button + an outline pill **"Open →"**). Rows (10): #11462 ANAMIKA 07 Oct 2026, 04:01 pm · #11425 KARTIK JINDAL 06 Oct 2026, 09:44 pm · #11488 SUBASINI PADHAN 08 Oct 2026, 09:02 pm · #11427 VIJAY KUMAR MISHRA 06 Oct 2026, 10:37 pm · #11330 SAVITHA 05 Oct 2026, 12:57 pm · #11207 KIRAN HONAGANAHALLI KRISHNASETTY 04 Oct 2026, 10:09 am · #11202 BALDEV KUMAR 04 Oct 2026, 08:21 am · #11094 KUNDAN KUMAR 02 Oct 2026, 01:43 pm · #10556 PRAKASH CHANDRASHEKAR SALLODAGI 25 Sept 2026, 05:12 pm · #10481 PRAGYAN CHAND PANDEY 24 Sept 2026, 05:52 pm. Footer "Showing 1–10 of 10". Below: optional **extra action rows** (white bordered list; 40px gold-50 circle with the count, bold label, muted "N items awaiting your action", right "Open queue →"): e.g. **4** Repayments to verify · **2** Settlements to approve.

### 4.4 Period row (mt 32)

Inline: 9.6px uppercase muted label **"PERIOD"** then a pill-track with pills: `Today` · `Yesterday` · `This week` · `Last week` · `This month` (navy) · `Last month` · `All time` · `Custom`.

### 4.5 "Your decisions" (mt 32) — right link **"Full history →"**; 4-col grid of **9 StatCards**

**StatCard** = white, border `line` (accent variants: gold-soft / success-100 / error-100), radius 12, p 20, shadow-sm; label 11.2px muted (ⓘ top-right when present); **value 24px navy 700**; hint 9.6px muted. Decided **521** · Approved / rejected **104 / 417** ("Approval rate 20%", success border) · In queue now **10** (gold border, ⓘ) · Avg turnaround **14.8h** (ⓘ) · Disbursement Pending Total Amount **₹2,43,600** ("Sanctioned/disbursed value of the files you moved forward in this period.") · Active days **16** ("35.2 actions/day") · Busiest day **05 Oct 2026** ("123 actions") · Working window **01 Oct 2026** ("through 08 Oct 2026") · Calls made **44**. Below: white card p 16, label **ACTIVITY**, navy sparkline (area 10% opacity, 1.5px line) over \~9 points.

### 4.6 "Your decision outcomes" (mt 32)

Live state shows an empty card: centred muted **"No approvals in this period yet."** *(Populated variant: 6 StatCards — Now overdue · Repaid clean · Still live · Your PAR · Avg sanctioned · Avg bureau score.)*

### 4.7 "Your borrowers" (mt 32) — `Users` icon + heading + ⓘ; right **"Open book →"**

Live state: empty card **"Nothing allocated to you yet."** *(Populated variant: 15 tiles — Borrowers in your book · Live · Overdue · Closed · Outstanding · At risk · DPD split (stacked bar + legend) · Due next 7 days · Avg ticket · Largest exposure · Concentration · Repeat borrowers · Avg credit score · Thin file · To chase.)*

### 4.8 "Collections desk" (mt 32) — **7 tiles for ADMIN** (the two head-only tiles are not shown)

Open cases **0** · **By DPD bucket** (label, a 10px stacked bar — empty grey-100 track here — then "No open cases." and hint "Your open cases. Each bucket links to its full list on DPD buckets.") · Outstanding (your cases) **₹0** (error border) · Recovered by you **₹0** (success border) · Recovery rate **—** · Awaiting validation **0** (gold border) · Settlements proposed **0** ("0 approved · 0 rejected").

### 4.9 "Team" (mt 32) — `.staff-data-table` in a white panel

Columns `NAME · ROLE · ACTIONS · APPROVAL RATE · AVG TURNAROUND · IN QUEUE NOW` (last four right-aligned). Name = navy 600 link; role = muted enum (CREDIT_EXECUTIVE, CREDIT_HEAD, DISBURSEMENT_HEAD, ACCOUNTANT, COLLECTION_HEAD, COLLECTION_EXECUTIVE, ADMIN, TELECALLER, DSA). Rows: Kabir Singh · CREDIT_EXECUTIVE · 0 · — · — · 0 / Neha Gupta · CREDIT_EXECUTIVE · 0 … / Priya Nair · CREDIT_HEAD / Vikram Shah · DISBURSEMENT_HEAD / Deepa Iyer · ACCOUNTANT / Arjun Patel · COLLECTION_HEAD / Sana Khan · COLLECTION_EXECUTIVE / Meera Krishnan · ADMIN / **DhanBoost Admin · ADMIN · 331 · 59% · 14.8h · 0** / Tara Menon · TELECALLER / Arjun Rao · DSA … (≈40 rows).

### 4.10 30-day trend cards (mt 32, 3 columns)

White card p 16: header "APPLICATIONS" (9.6px uppercase muted 600) + total (14.4px navy 700) right; 40px sparkline; footer 8.8px "Last 30 days" + delta right.

- **Applications** 2960 · navy line · "▼ 189 vs last wk (-35%)" (error-700)
- **Disbursals** 113 · emerald line · "▲ 0 vs last wk (+0%)" (success-700)
- **Repayments** 64 · green `#2E9E6B` line · "▼ 10 vs last wk (-36%)"

### 4.11 "Pipeline at a glance" (mt 32) — `Route` icon + heading + ⓘ

Row of **6 stage tiles** (flex-1, min 120px, white, border `line`, radius 12, p 16): label 9.6px muted + ⓘ top-right, figure 19.2px navy 700; **Closed** tile bg grey-50 with a muted figure. Started · KYC · Credit review · Disbursement · Active & repayment · Closed (e.g. 31 · 22 · 17 · 6 · 97 · 26).

### 4.12 "Customers by segment" (mt 32) — **5-column grid** of 12 StatCards

All **11216** · Incomplete **1577** · Pending **0** · Review **2** · Approved **8** · Disbursement Pending **0** · Active **79** · **Overdue 56** (error border) · Hold **0** · Rejected **9484** · Closed **10** · **Unallocated 11206** (gold border because non-zero).

### 4.13 "Salary dates" (mt 32) — `CalendarClock` icon + heading + ⓘ

White card p 20: pill SearchBar (placeholder *"Name, mobile, or PAN"*) + navy pill **"🔍 Search"**; default state muted text *"Search for a customer to view or change their salary date."* *(Results variant: table* `Name · Mobile · PAN · Loan status · Salary day · Save` *with a* `[30 ▾]` *select and* `[ 💾 Save ]`*.)*

### 4.14 "Transactions" collapsible (mt 32)

A white `<details>` panel, **collapsed by default**: summary row (px 20 py 16) = `ChevronRight` + `Receipt` icon + **"Transactions"** (14.4px). *Open variant:* top border, p 20, muted intro *"Company-wide money movement — disbursals out and repayments in."* + ⓘ, right link **"View all →"**; two tiles (max 448px, 2 cols): **Incoming** (success-100 border, `ArrowDownLeft` green, 16px navy 700 **₹6,48,200**) · **Outgoing** (`ArrowUpRight` navy, **₹9,02,050**); then a divided list of the latest 5 (name over 9.6px muted "Repayment · loan #302 · 09 Oct 2026"; amount right, incoming success-700 with "+", outgoing ink with "−").

---

## 5. Optional Frame — Full customer page (`/staff/customers/{id}`)

Same shell. "← All customers" link, **h1 caps name** 28px, subtitle "Customer #2296807 · borrower history · Unallocated", `[ ↻ Refresh ]`. Two columns: left a white card with the customer tabs in two rows (**Personal Details** · Employment & salary · Bank Accounts · Verifications · Credit Report / Documents · Loan Applications · Customer Call Logs · Skip Tracer · Audit Logs) and the same bodies as §3.11–3.18; right rail: **"Credit score"** card (the gauge from §3.14) and **"₹ Maximum loan amount (admin)"** card ("Current limit: ₹8,900" + `neutral` "Salary rule" pill, explanatory muted text, an amount input "Maximum amount (₹)" and a save button).

## 6. Deliverables for Claude Design

1. **Frame 1 — Customers register** (1440×900, §2, 8 rows, two date groups; plus the bulk-bar variant).
2. **Frame 2 — Customer pop-up, Overview tab**, dim overlay over Frame 1.
3. **Frames 3–15 — one per remaining pop-up tab** (Journey, Verifications, Documents, Past details, Audit log, Personal, Employment & salary, Bank, Credit report, Loan applications, Calls & remarks, Skip Tracer, Customer activity).
4. **Frame 16 — Admin dashboard** (1440 wide, full-height scroll, §4.1–4.14 in order).
5. **Frame 17 — Full customer page** (§5), optional.
6. Component sheet: StatCard (4 accents), StatusBadge (all tones), credit pill (3 tones), Section/KV/field table, FocusCard+Headline, Tabs, cal-preset chip, date-filter pill track, table header/zebra row/sticky actions, buttons (navy/gold/outline/danger/success/icon), journey step (3 states), sidebar nav item states.