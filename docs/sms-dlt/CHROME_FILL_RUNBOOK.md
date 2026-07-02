# Claude-in-Chrome Runbook — bulk-create NAVIX DLT content templates on the STPL portal

This is the **driver prompt** for the Claude-in-Chrome browser agent. It creates every SMS content
template from [`dlt-templates.json`](./dlt-templates.json) one-by-one on the STPL DLT portal UI.

The portal has **no bulk import**, so each template is entered by hand — this runbook automates that
loop. The agent adapts to the actual DOM (field labels differ per portal), using the JSON as the
single source of truth for the data.

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
>    and `variables[]` (each with `purpose`, `tag`, `sample`).
> 3. For **each** template, in order:
>    a. Set the **template name** field to `name`.
>    b. Set the **category** to **Service Implicit** (⚠ never "Transactional" — NAVIX is
>       non-banking and it will be rejected).
>    c. Put the **exact** `content` string into the message box, `{#var#}` placeholders included.
>       Prefer the portal's copy/paste behavior so the `{#var#}` tokens register as variables. If the
>       portal needs the "Add Variable" button instead, type the static text and insert a variable at
>       each `{#var#}` position, left to right.
>    d. For each variable, set its **tag** (from `tag`) and **sample value** (from `sample`) in the
>       matching input, in order.
>    e. **Before submitting**, verify with `read_page` that the message box text equals `content`
>       **character-for-character** (no double spaces, no trailing space, brand name `NAVIX Finance`
>       present, ends with ` - NAVIX Finance`). If it differs, fix it before continuing.
>    f. Click **Submit**. Capture the confirmation / any returned **DLT Template ID** and the
>       template `name`.
>    g. Return to the create-template form for the next entry (the portal usually returns you there;
>       if not, navigate back).
> 4. Keep a running list of `{name → DLT Template ID, status}` and print it at the end so it can be
>    pasted into `SMSGuide.md` §5.
>
> Guardrails:
> - **Pause and ask me** if: a field label is ambiguous, the category dropdown has no "Service
>   Implicit" option, a Submit returns an error, or the message box text won't match `content` after
>   two attempts. Do not guess through repeated failures.
> - **Never** trigger a JavaScript alert/confirm dialog (it freezes the extension). If a
>   delete/clear control might confirm, avoid it.
> - Record a GIF of the first template creation (`gif_creator`, name it `dlt_first_template.gif`) so I
>   can review the flow, then proceed without recording the rest.
> - Do not submit the two deferred promotional templates (`REBORROW_PREAPPROVED`,
>   `REFERRAL_REWARD_CREDITED`) — they are intentionally absent from the JSON.

---

## Notes for the operator

- **Copy/paste vs Add-Variable:** the STPL portal (per the guidelines PDF) supports pasting a message
  that already contains `{#var#}` and having those recognized as variables. If your portal instead
  requires clicking "Add Variable", the agent falls back to inserting per-position. Either yields the
  same registered template.
- **Variable tags:** the `tag` values in the JSON (`OTP`, `Amount`, `Number`, `Date`) are best-guess
  dropdown labels. If the portal's dropdown uses different names (e.g. "Currency", "Numeric",
  "Alphanumeric"), tell the agent the available options and it will map them.
- **After approval:** paste the returned Template IDs into the mapping table in
  [`SMSGuide.md`](./SMSGuide.md) §5, then wire them into the backend (§6 of the guide).
- **DOM-specific autofill (optional):** once the real field selectors are known from the first run
  (`read_page`), a deterministic `javascript_tool` autofill snippet can be generated to skip the
  visual step-through. Ask for it after the first successful manual-assisted creation.
