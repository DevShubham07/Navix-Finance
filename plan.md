# Plan / Handoff — pending tasks for the next session

> Written 2026-06-28. The previous content of this file (borrower account menu + admin
> per-step control) is **shipped**; see `CLAUDE.md` / `handoff.md`. This file now tracks
> what's **left** after the marketing-site redesign.

---

## Where things stand

**Marketing redesign — DONE locally, NOT yet live.** The public site was rebuilt as 16
pages ported from the design export (`~/Downloads/NAVIX (1).html`). Details in the memory
`navix-marketing-redesign.md`. Two local commits sit on top of `origin/main` (`667f00a`),
**not pushed**:

- `b20743d` — fix(digilocker): unique redirect_url per attempt (busts stale consent token)
- `0794cb7` — feat(marketing): full public-site redesign (16 pages)

Local `next build` is **green**; all 16 routes prerender static; `tsc --noEmit` + ESLint clean.
A **preview** deploy succeeded (CLI, from `frontend/`):
`https://frontend-84tidffd0-devshubham07s-projects.vercel.app` (READY, target=preview).

Git identity was fixed to `DevShubham07 <shubham720648@gmail.com>` so Vercel's author check
passes (see memory `navix-vercel-git-author-block.md`). Pushing is a clean fast-forward
(no force needed).

---

## ⛔ BLOCKER — Vercel GitHub-integration build fails (root directory)

A push-triggered (GitHub-integration) deploy fails with:

```
Running "npm run vercel-build"
> next build
> Build error occurred
[Error: > Couldn't find any `pages` or `app` directory. Please create one under the project root]
Error: Command "npm run vercel-build" exited with 1
```

**Diagnosis.** The Next.js app lives in **`frontend/`**, but the Vercel project's
**Root Directory is not set to `frontend`**, so the GitHub-integration build runs `next build`
at the **repo root** (which has no `app`/`pages` dir → the error). There is **no** root
`package.json` / `vercel.json` / `turbo` — the app is only under `frontend/`.

This is why **CLI deploys work but a push does not**: `npx vercel` is run from inside
`frontend/` (so frontend is the upload root), whereas the GitHub integration clones the repo
root and respects the (mis)configured Root Directory. The earlier "blocked: commit email"
message was a *separate*, now-fixed issue (git author); this root-dir error is what's left.

**Fix (pick one):**

1. **Recommended — set the Vercel Root Directory.** Vercel dashboard → project **frontend**
   (`prj_UgbCDi0AKwKVAxwUrFhXKnGawJfo`, team `team_W8BV9gDAIhD1SL9Szcko1hXh`) → Settings →
   Build & Deployment → **Root Directory = `frontend`** → Save. Then `git push origin main`
   builds correctly and auto-deploys to production. (Root Directory is a dashboard/API setting;
   it is NOT settable via `vercel.json`.)

2. **Avoid the GitHub build entirely — deploy via CLI** (already proven to work):
   `cd frontend && npx vercel --prod --yes`. Downside: every push still kicks off a failing
   GitHub build (noise) until option 1 is done.

3. If a repo-root build is genuinely wanted, add a root `vercel.json` with an explicit
   `installCommand`/`buildCommand`/`outputDirectory` pointing into `frontend/` — clunkier than
   option 1; only if Root Directory can't be changed.

---

## Pending tasks (in order)

1. **Fix the Vercel Root Directory** (above) so push-to-`main` deploys cleanly. Until then,
   deploy with `cd frontend && npx vercel --prod --yes`.
2. **Visually QA the 16 marketing pages** on the preview URL before promoting — could not
   render them in this session (no display). Routes: `/` `/calculator` `/how-it-works`
   `/products` `/partners` `/about` `/reviews` `/help` `/faq` `/contact` `/blog` `/careers`
   `/privacy` `/terms` `/fair-practices` `/grievance`. Check: hero + loan-journey animation,
   calculator sliders/ring/rate-table, FAQ accordions, mobile drawer, header scroll state,
   fonts (Bricolage/Hanken/IBM Plex Mono), and that internal links + the Apply/Sign-in CTAs
   go to the **real** `/signup/mobile-otp` and `/login`.
3. **Push the two local commits to `main`** (`git push origin main` — fast-forward) and
   **promote to production** (auto via push once #1 is fixed, or `npx vercel --prod --yes`).
4. **Confirm no functional regression** post-deploy: borrower `/login`→OTP→dashboard, staff
   `/staff/login`, and that the borrower `/support` page (distinct from the new marketing
   `/help`) still works.
5. Optional cleanup: the old marketing chrome in `frontend/src/components/site/`
   (`site-shell`, `site-header`, `site-footer`, `utility-bar`, `fraud-alert`, `section-head`,
   `emi-calculator`, `brand`, `reveal-init`, `index.ts`) is now **dead code** — safe to delete
   once the redesign is confirmed live.

---

## Notes / guardrails
- Marketing CSS is scoped under `.navix-mkt` (incl `:root`→`.navix-mkt`, keyframes `nv-*`) and
  must NOT leak into the borrower/staff app (shared var names with different values). Keep it scoped.
- Regen scripts (if the design export changes) are in the session scratchpad: `scope_css.py`
  (CSS→`marketing-theme.css`) and `transform_html.py` (sections→`_content/*.ts`).
- Marketing `#support` is mapped to **`/help`** on purpose — the borrower app already owns
  `/support` (parallel-route build error otherwise).
