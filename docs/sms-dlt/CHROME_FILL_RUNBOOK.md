# Claude-in-Chrome Runbook — bulk-create DhanBoost DLT content templates on the STPL portal

This is the **driver prompt** for the Claude-in-Chrome browser agent. It creates every SMS content
template from [`dlt-templates.json`](./dlt-templates.json) one-by-one on the STPL DLT portal UI.

The portal has **no bulk import**, so each template is entered by hand — this runbook automates that
loop. The agent adapts to the actual DOM (field labels differ per portal), using the JSON as the
single source of truth for the data.

> **Batch:** `DHANBOOST_*_V1`. The NAVIX→DhanBoost rebrand changed the brand string and the URL in every
> body, invalidating all 15 `_V2` ids — so this is a fresh creation run, not an edit.

---

## Before you run it — four registration blockers

The agent will burn a submission attempt on each template if these aren't done first:

1. **`dhanboost.com` must be a live registered domain** — as of 2026-07-31 it is not. Every non-OTP
   template links to it.
2. **`DhanBoost` must be registered as a brand** under NAVIX FINANCE PRIVATE LIMITED on the DLT portal.
   DLT rejected us before for *"Entity brand name is not mentioned in the SMS content"*.
3. **Header `DHANBT`** must be registered and active (replaces the retired `NAVIXF`).
4. **`https://dhanboost.com/login` must be URL-whitelisted** under the entity, char-for-char.
   ⚠ The registered CTA is the **apex** — there is **no `www.`**. Do not reintroduce it.

Only #1 is optional for a partial run: `DHANBOOST_OTP_LOGIN_V1` is link-free and can be registered
(and can send) before the domain exists.

---

## How to run it

1. In Chrome, **log in to the STPL DLT portal yourself** and navigate to the **Content Template**
   creation area (the page with a message box + "Add Variable" / Submit). Automating login is out of
   scope — the agent starts from an authenticated session.
2. In Claude Code, paste the **Agent prompt** below (it references the JSON by path). The agent loads
   the Chrome MCP tools, reads the JSON, and fills each template.
3. Approve each **Submit** when the agent pauses for confirmation (recommended for the first 2–3, then
   let it run).

---

## Agent prompt (paste this to the browser agent)

> You are creating DLT SMS **content templates** on the STPL DLT portal, which is already open and
> logged in in the active Chrome tab. Do all of the following:
>
> 1. Load the Chrome tools and read the current tab context first
>    (`tabs_context_mcp`), then `read_page` to learn the form's field labels
>    (template name, category dropdown, message box, add-variable control, sample-value inputs,
>    Submit). Do **not** create a new tab — use the tab I have open.
> 2. Read the template data from `docs/sms-dlt/dlt-templates.json`. Each entry has `name`,
>    `category` (always **"Service Implicit"**), `content` (with literal `{#var#}` placeholders),
>    and `variables[]` (each with `purpose`, `tag`, `sample`). Ignore `dltTemplateId` — it is `null`
>    for this batch and is what you are collecting.
> 3. For **each** template, in order:
>    a. Set the **template name** field to `name`.
>    b. Set the **category** to **Service Implicit** (⚠ never "Transactional" — DhanBoost is
>       non-banking and it will be rejected).
>    c. Put the **exact** `content` string into the message box, `{#var#}` placeholders included.
>       Prefer the portal's copy/paste behavior so the `{#var#}` tokens register as variables. If the
>       portal needs the "Add Variable" button instead, type the static text and insert a variable at
>       each `{#var#}` position, left to right.
>    d. For each variable, set its **tag** (from `tag`) and **sample value** (from `sample`) in the
>       matching input, in order.
>    e. **Before submitting**, verify with `read_page` that the message box text equals `content`
>       **character-for-character** (no double spaces, no trailing space, brand name `DhanBoost`
>       present, ends with ` - DhanBoost`, URL exactly `https://dhanboost.com/login` — no `www.`). If it
>       differs, fix it before continuing.
>    f. Click **Submit**. Capture the confirmation / any returned **DLT Template ID** and the
>       template `name`.
>    g. Return to the create-template form for the next entry (the portal usually returns you there;
>       if not, navigate back).
> 4. Keep a running list of `{name → DLT Template ID, registered category, status}` and print it at the
>    end so it can be pasted into `SMSULTRON.md`, `DLT_SUBMISSION_TRACKER.md`, and the `dltTemplateId`
>    fields in `dlt-templates.json`.
>
> Guardrails:
> - **Pause and ask me** if: a field label is ambiguous, the category dropdown has no "Service
>   Implicit" option, a Submit returns an error, or the message box text won't match `content` after
>   two attempts. Do not guess through repeated failures.
> - **Stop immediately** if a submit is rejected for a missing entity brand name or an un-whitelisted
>   URL — that means a registration blocker above wasn't cleared, and every remaining template will
>   fail the same way.
> - **Never** trigger a JavaScript alert/confirm dialog (it freezes the extension). If a
>   delete/clear control might confirm, avoid it.
> - Record a GIF of the first template creation (`gif_creator`, name it `dlt_first_template.gif`) so I
>   can review the flow, then proceed without recording the rest.
> - **Pause on the two borderline templates** (`DHANBOOST_REBORROW_PREAPPROVED_V1`,
>   `DHANBOOST_REFERRAL_REWARD_CREDITED_V1`) before submitting: they are worded to fit **Service
>   Implicit**, but on the previous batch both were ultimately accepted only as **Service Explicit**.
>   Ask me before force-submitting either under a category the portal disputes.

---

## Notes for the operator

- **Copy/paste vs Add-Variable:** the STPL portal (per the guidelines PDF) supports pasting a message
  that already contains `{#var#}` and having those recognized as variables. If your portal instead
  requires clicking "Add Variable", the agent falls back to inserting per-position. Either yields the
  same registered template.
- **Variable tags:** the `tag` values in the JSON (`OTP`, `Amount`, `Number`, `Date`) are best-guess
  dropdown labels. On the previous run the portal lacked them and `Number` / `Alphanum` were used —
  functionally safe. If the dropdown uses different names, tell the agent the options and it will map them.
- **After approval:** paste the returned Template IDs into [`SMSULTRON.md`](./SMSULTRON.md) and the
  tracker, then wire them into the backend ([`SMSGuide.md`](./SMSGuide.md) §6–§7).
- **Alternate wordings:** if the reviewer rejects the primary text for KYC-approved, reborrow-approved,
  loan-closed or reborrow-preapproved, [`CHROME_AGENT_PROMPT_V3.md`](./CHROME_AGENT_PROMPT_V3.md) holds
  softer `_V1_ALT` rewrites for exactly those four.
- **DOM-specific autofill (optional):** once the real field selectors are known from the first run
  (`read_page`), a deterministic `javascript_tool` autofill snippet can be generated to skip the
  visual step-through. Ask for it after the first successful manual-assisted creation.
