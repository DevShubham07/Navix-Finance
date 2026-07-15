# NAVIX DLT template-creation (SmartPing) — Service Implicit batch

Complete, self-contained instruction set for the Claude-in-Chrome agent creating NAVIX's DLT SMS
content templates on the **SmartPing** entity portal. Everything the agent needs is inline.

---

## Preconditions (the operator ensures these before the agent runs)

- Chrome is open and **logged in to the SmartPing entity portal** as **NAVIX FINANCE PRIVATE LIMITED**
  (login `info@navixfinance.com`) at **`https://smartping.live/entity/content-form`** — the Content
  Template create form (message box + category dropdown + Submit). Login automation is out of scope;
  the agent starts from an authenticated session and uses the tab already open (does NOT open a new tab).
- The URL **`https://www.navixfinance.com/login`** is already **URL-whitelisted** under the entity
  (every template contains this link; an un-whitelisted URL → rejection).

## Entity / sender settings

- Header / Sender ID: **NAVIXF**.
- Principal Entity ID (PE-ID): the account is logged in as NAVIX FINANCE, so the form may auto-bind the
  entity. If a PE-ID field is REQUIRED and blank, STOP and ask the operator (the known PE-ID is
  `1701178039634361131`, but do not assume the form wants it typed).
- Category for **every** template: **`Service Implicit`**.
  ⚠ NEVER "Transactional" (banks-only; NAVIX is non-banking → rejected).
  ⚠ NEVER "Service Explicit" — if the form or reviewer tries to force Explicit, or shows a
  promotional-category warning on submit, **STOP and ask** (see rule 8).

---

## HARD RULES (apply to every template)

1. **Category = `Service Implicit`** for all 4. If the dropdown has no "Service Implicit" option,
   STOP and ask.
2. Set the **template name** exactly as given below.
3. **Paste the CONTENT string EXACTLY** — character-for-character. It must: contain the brand name
   `NAVIX Finance`; end with ` - NAVIX Finance`; have NO double spaces and NO trailing space; keep the
   URL exactly `https://www.navixfinance.com/login`.
4. **Variables:** all 4 templates are **variable-free** (no `{#var#}` tokens). Do not add any variable.
   If the form requires at least one variable, STOP and ask — do not invent one.
5. **Before submitting each one**, use `read_page` to confirm the message-box text equals the CONTENT
   string exactly. Fix if it differs; if it still won't match after two attempts, STOP and ask.
6. Click **Submit**. Then open the template's **eye (view) icon** on the content-template listing to
   read its assigned **DLT Template ID**, and capture it with the template name. Return to the create
   form for the next entry (navigate back if the portal doesn't auto-return).
7. **NEVER trigger a JavaScript alert/confirm/prompt dialog** (it freezes the extension). Avoid any
   Delete/Clear control that might confirm.
8. ⚠ **If the portal auto-classifies any template as promotional / Service Explicit, or shows a
   category warning on submit, STOP and ask the operator** before proceeding — do not accept an
   Explicit registration.
9. Record a GIF of the FIRST creation (`gif_creator`, name `dlt_first_template.gif`); then proceed
   without recording. Pause for operator approval after the first submit, then continue.

---

## The 4 templates — create IN ORDER

Format: `[#] NAME — CATEGORY` / `CONTENT:` (submit this) / `VARS:`

**[1] NAVIX_KYC_APPROVED_V3 — Service Implicit**
CONTENT: `Your KYC verification with NAVIX Finance is complete. Log in at https://www.navixfinance.com/login to continue your loan application. - NAVIX Finance`
VARS: none

**[2] NAVIX_REBORROW_APPROVED_V3 — Service Implicit**
CONTENT: `Your loan application with NAVIX Finance is approved. Log in at https://www.navixfinance.com/login to view the details and next steps. - NAVIX Finance`
VARS: none

**[3] NAVIX_LOAN_CLOSED_V3 — Service Implicit**
CONTENT: `Your loan with NAVIX Finance is fully repaid and closed. Log in at https://www.navixfinance.com/login to view your loan closure statement. - NAVIX Finance`
VARS: none

**[4] NAVIX_REBORROW_PREAPPROVED_V3 — Service Implicit**
CONTENT: `Your NAVIX Finance account review is complete and your eligibility is updated. Log in at https://www.navixfinance.com/login to view your account status. - NAVIX Finance`
VARS: none

---

## At the end

Print a table of `{ template name -> DLT Template ID, registered category, status }` for all 4.
