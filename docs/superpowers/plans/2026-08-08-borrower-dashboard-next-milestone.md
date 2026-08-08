# Borrower Dashboard Next Milestone Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the borrower dashboard DOB card with a lifecycle-aware next-milestone card, remove the scrolling payment-warning strip above the marketing footer, and deploy the verified frontend from `main`.

**Architecture:** Add a small pure frontend helper that maps application status and contractual due date to borrower-facing milestone content. The dashboard renders this content in the existing sidebar footprint. The marketing footer stops mounting its ticker while shared safety copy remains available to support and policy pages.

**Tech Stack:** Next.js 15, React 19, TypeScript, Vitest, ESLint, Vercel.

## Global Constraints

- Work directly on `main`, as requested.
- Preserve unrelated local files and configuration; stage only intended product changes.
- Treat the loan due date as a local calendar date and test salary day, grace day, and overdue boundaries deterministically.
- Do not remove the shared payment-safety notice from policy/support surfaces; remove only the footer ticker.

---

### Task 1: Add the lifecycle milestone mapper with tests

**Files:**
- Create: `frontend/src/lib/borrower-next-milestone.ts`
- Create: `frontend/src/lib/borrower-next-milestone.test.ts`

**Steps:**

1. Write deterministic Vitest cases for no application, draft, review, sanctioned, disbursal, active-before-due, due today, grace day, overdue, closed, unsuccessful terminal states, and missing due dates.
2. Run the focused test and confirm it fails because the mapper does not exist.
3. Implement an exported pure mapper returning title, detail, action label, href, and icon kind.
4. Use the existing calendar-day difference helper so browser time-of-day does not shift due-date boundaries.
5. Run the focused test and confirm every case passes.

### Task 2: Replace DOB with the next milestone card

**Files:**
- Modify: `frontend/src/app/(borrower)/dashboard/page.tsx`

**Steps:**

1. Remove DOB formatting and the conditional DOB card.
2. Compute the milestone from the live application status and loan due date.
3. Render the milestone in the existing sidebar footprint with a status-appropriate icon and a clear action link.
4. Keep the profile request for the borrower’s name and preserve the current responsive layout.
5. Run the focused milestone test, TypeScript check, and ESLint for the changed files.

### Task 3: Remove the footer payment-warning ticker

**Files:**
- Modify: `frontend/src/components/site/marketing-footer.tsx`

**Steps:**

1. Remove the footer ticker import and rendered ticker row.
2. Leave the shared safety notice constant/component intact for other product surfaces.
3. Search the footer component to confirm it no longer references the ticker.
4. Run TypeScript and ESLint validation.

### Task 4: Verify, push, and deploy

**Files:**
- Verify all intended staged product files and documentation.

**Steps:**

1. Run the focused Vitest suite, full frontend TypeScript check, ESLint, and production build.
2. Review `git diff` and selectively stage the approved product changes, excluding local tooling, screenshots, credentials, and unrelated assets.
3. Commit on `main` and push `origin/main`.
4. Deploy the frontend production build through the configured Vercel project.
5. Verify the production alias resolves to the new deployment and inspect the live footer/dashboard behavior where authentication permits.
