# DhanBoost DLT template-creation (SmartPing) — alternate-wording batch

Complete, self-contained instruction set for the Claude-in-Chrome agent creating DhanBoost's DLT SMS
content templates on the **SmartPing** entity portal. Everything the agent needs is inline.

> **What this batch is.** Four templates whose *primary* wording (in `CHROME_AGENT_PROMPT.md` /
> `dlt-templates.json`) has drawn reviewer pushback. These are **reworded alternates** for the same four
> notification types — softer, more account-status-flavoured, to survive a Service Implicit review.
> Named `DHANBOOST_*_V1_ALT` so they never collide with the primary `DHANBOOST_*_V1` batch.
>
> ⚠ **Only register these if the primary wording is rejected.** If both end up registered, the backend
> must point at exactly one id per type — and `NotificationTemplates.java` must be edited to match the
> ALT text char-for-char, since it currently ships the primary wording.

---

## Preconditions (the operator ensures these before the agent runs)

- Chrome is open and **logged in to the SmartPing entity portal** as **NAVIX FINANCE PRIVATE LIMITED**
  (login `info@navixfinance.com` — the entity registration and its login email are unchanged by the
  rebrand) at **`https://smartping.live/entity/content-form`** — the Content Template create form
  (message box + category dropdown + Submit). Login automation is out of scope; the agent starts from an
  authenticated session and uses the tab already open (does NOT open a new tab).
- **`DhanBoost` is registered as a brand under the entity** — without it, DLT rejects every submit for
  a missing entity brand name. If unsure, STOP and ask.
- Header **`DHANBT`** is registered and active (replaces the retired `NAVIXF`).
- **`dhanboost.com` is live** and the URL **`https://www.dhanboost.com/login`** is **URL-whitelisted**
  under the entity (every template here contains this link; an un-whitelisted URL → rejection).

## Entity / sender settings

- Header / Sender ID: **DHANBT**.
- Principal Entity ID (PE-ID): entity-level and unchanged — `1701178039634361131`. The account is logged
  in as the entity, so the form may auto-bind it. If a PE-ID field is REQUIRED and blank, STOP and ask
  the operator (do not assume the form wants it typed).
- Category for **every** template: **`Service Implicit`**.
  ⚠ NEVER "Transactional" (banks-only; DhanBoost is non-banking → rejected).
  ⚠ NEVER "Service Explicit" — if the form or reviewer tries to force Explicit, or shows a
  promotional-category warning on submit, **STOP and ask** (see rule 8).

---

## HARD RULES (apply to every template)

1. **Category = `Service Implicit`** for all 4. If the dropdown has no "Service Implicit" option,
   STOP and ask.
2. Set the **template name** exactly as given below.
3. **Paste the CONTENT string EXACTLY** — character-for-character. It must: contain the brand name
   `DhanBoost`; end with ` - DhanBoost`; have NO double spaces and NO trailing space; keep the
   URL exactly `https://www.dhanboost.com/login`.
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

**[1] DHANBOOST_KYC_APPROVED_V1_ALT — Service Implicit**
CONTENT: `Your KYC verification with DhanBoost is complete. Log in at https://www.dhanboost.com/login to continue your loan application. - DhanBoost`
VARS: none

**[2] DHANBOOST_REBORROW_APPROVED_V1_ALT — Service Implicit**
CONTENT: `Your loan application with DhanBoost is approved. Log in at https://www.dhanboost.com/login to view the details and next steps. - DhanBoost`
VARS: none

**[3] DHANBOOST_LOAN_CLOSED_V1_ALT — Service Implicit**
CONTENT: `Your loan with DhanBoost is fully repaid and closed. Log in at https://www.dhanboost.com/login to view your loan closure statement. - DhanBoost`
VARS: none

**[4] DHANBOOST_REBORROW_PREAPPROVED_V1_ALT — Service Implicit**
CONTENT: `Your DhanBoost account review is complete and your eligibility is updated. Log in at https://www.dhanboost.com/login to view your account status. - DhanBoost`
VARS: none

---

## At the end

Print a table of `{ template name -> DLT Template ID, registered category, status }` for all 4.
If any of these are adopted over the primary wording, flag that `NotificationTemplates.java` must be
updated to the ALT text char-for-char before the id is wired into `navix.sms.dlt-template-ids`.
