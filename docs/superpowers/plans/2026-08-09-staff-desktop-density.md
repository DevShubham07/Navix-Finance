# Staff Desktop Density Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the authenticated staff console approximately 20% denser on laptop and desktop screens while preserving semantic table rows, all data/actions, and the current mobile layout.

**Architecture:** `StaffShell` owns a reversible class on the document root for the lifetime of `/staff`. A desktop-only design-system media query uses that class to reduce the rem scale and pixel-based staff controls, while `.navix-crm` table rules remove avoidable desktop minimum widths and allow safe wrapping. The live-pipeline action group loses its content-sized minimum so shared table rules can actually compact it.

**Tech Stack:** Next.js 15, React 19, TypeScript 5.7, Tailwind CSS 3.4, Vitest 3, Playwright Chromium.

## Global Constraints

- Apply the density change only to `/staff`; borrower and marketing routes must remain unchanged.
- Use 1366px as the laptop baseline and start compact sizing at 1024px.
- Keep current sizing and horizontal table scrolling below 1024px.
- Keep every table semantic, with one record per row and headings at the top.
- Do not hide columns, data, or actions.
- Preserve sticky live-pipeline identity/action columns and horizontal overflow as a fallback.
- Do not use CSS `zoom` or transform scaling.
- Preserve unrelated working-tree changes.

---

### Task 1: Reversible staff density boundary

**Files:**
- Create: `frontend/src/lib/staff-density.ts`
- Create: `frontend/src/lib/staff-density.test.ts`
- Modify: `frontend/src/components/staff/staff-shell.tsx`

**Interfaces:**
- Produces: `STAFF_DESKTOP_DENSITY_CLASS: "staff-desktop-density"`.
- Produces: `applyStaffDesktopDensity(classList: Pick<DOMTokenList, "add" | "remove">): () => void`.
- Consumes: the browser's `document.documentElement.classList` from `StaffShell`.

- [ ] **Step 1: Write the failing class-lifecycle test**

```ts
import { describe, expect, it } from "vitest";

async function subject() {
  return import("./staff-density").catch(() => ({}));
}

describe("applyStaffDesktopDensity", () => {
  it("adds the staff-only density marker and removes it during cleanup", async () => {
    const mod = await subject() as Record<string, unknown>;
    expect(typeof mod.applyStaffDesktopDensity).toBe("function");
    const values = new Set<string>();
    const classList = {
      add: (...tokens: string[]) => tokens.forEach((token) => values.add(token)),
      remove: (...tokens: string[]) => tokens.forEach((token) => values.delete(token)),
    };

    const apply = mod.applyStaffDesktopDensity as (value: typeof classList) => () => void;
    const cleanup = apply(classList);
    expect(values.has("staff-desktop-density")).toBe(true);

    cleanup();
    expect(values.has("staff-desktop-density")).toBe(false);
  });
});
```

- [ ] **Step 2: Run the focused Vitest test and verify RED**

Run: `cd frontend; npm test -- src/lib/staff-density.test.ts`

Expected: FAIL because `@/lib/staff-density` does not exist.

- [ ] **Step 3: Implement the minimal density lifecycle helper**

```ts
export const STAFF_DESKTOP_DENSITY_CLASS = "staff-desktop-density";

export function applyStaffDesktopDensity(
  classList: Pick<DOMTokenList, "add" | "remove">,
): () => void {
  classList.add(STAFF_DESKTOP_DENSITY_CLASS);
  return () => classList.remove(STAFF_DESKTOP_DENSITY_CLASS);
}
```

- [ ] **Step 4: Mount the density boundary in `StaffShell`**

Import `applyStaffDesktopDensity` and add this hook before any conditional return:

```ts
React.useEffect(
  () => applyStaffDesktopDensity(document.documentElement.classList),
  [],
);
```

This includes public `/staff` pages but cleanup removes the class immediately when the staff layout unmounts.

- [ ] **Step 5: Run the focused test and verify GREEN**

Run: `cd frontend; npm test -- src/lib/staff-density.test.ts`

Expected: PASS, one test and zero failures.

- [ ] **Step 6: Commit the boundary**

```bash
git add frontend/src/lib/staff-density.ts frontend/src/lib/staff-density.test.ts frontend/src/components/staff/staff-shell.tsx
git commit -m "feat: scope desktop density to staff console"
```

---

### Task 2: Compact desktop controls and tables

**Files:**
- Create: `frontend/e2e/staff-density.spec.ts`
- Modify: `frontend/src/app/globals.css`
- Modify: `frontend/src/components/staff/pipeline/app-row.tsx`

**Interfaces:**
- Consumes: the `html.staff-desktop-density` class from Task 1.
- Consumes: `.navix-crm`, `.staff-data-table`, `.staff-sticky-identity`, `.staff-sticky-actions`, `.btn`, and `.btn-sm` existing design-system classes.
- Produces: desktop-only density behavior at `min-width: 1024px`; no mobile override.

- [ ] **Step 1: Write the failing browser regression test**

```ts
import { expect, test } from "@playwright/test";
import path from "node:path";
import type { Page } from "@playwright/test";

const globalStyles = path.resolve(process.cwd(), "src/app/globals.css");

async function mountFixture(page: Page) {
  await page.setContent(`
    <html class="staff-desktop-density">
      <body>
        <main class="navix-crm">
          <div data-testid="table-scroll" style="width: 1000px; overflow-x: auto">
            <table class="staff-data-table">
              <thead><tr><th>Application</th><th>Customer details and assignment</th><th>Actions</th></tr></thead>
              <tbody><tr><td>#10001</td><td>Customer details</td><td><button class="btn btn-sm">Open customer journey</button></td></tr></tbody>
            </table>
          </div>
        </main>
      </body>
  `);
  await page.addStyleTag({ content: ".navix-crm table.staff-data-table { min-width: 1120px; }" });
  await page.addStyleTag({ path: globalStyles });
}

test("staff tables and buttons compact without desktop horizontal overflow", async ({ page }) => {
  await page.setViewportSize({ width: 1366, height: 768 });
  await mountFixture(page);

  await expect.poll(() => page.locator("html").evaluate((el) => getComputedStyle(el).fontSize)).toBe("12.8px");
  const overflow = await page.getByTestId("table-scroll").evaluate((el) => el.scrollWidth - el.clientWidth);
  expect(overflow).toBeLessThanOrEqual(1);
  await expect.poll(() => page.locator("button").evaluate((el) => getComputedStyle(el).padding)).toBe("8px 12px");
});

test("mobile staff sizing and wide-table scrolling remain unchanged", async ({ page }) => {
  await page.setViewportSize({ width: 900, height: 768 });
  await mountFixture(page);

  await expect.poll(() => page.locator("html").evaluate((el) => getComputedStyle(el).fontSize)).toBe("16px");
  const overflow = await page.getByTestId("table-scroll").evaluate((el) => el.scrollWidth - el.clientWidth);
  expect(overflow).toBeGreaterThan(1);
});
```

- [ ] **Step 2: Run the focused Playwright test and verify RED**

Run: `cd frontend; npx playwright test e2e/staff-density.spec.ts`

Expected: desktop test FAILS because root font size remains 16px, the 1120px table overflows, and `.btn-sm` padding remains 11px 18px. The mobile test passes.

- [ ] **Step 3: Add desktop-only staff density rules to `globals.css`**

Append after the existing staff table block:

```css
@media (min-width: 1024px) {
  html.staff-desktop-density { font-size: 80%; }

  html.staff-desktop-density .navix-crm table {
    min-width: 100%;
    table-layout: auto;
  }

  html.staff-desktop-density .navix-crm table th {
    white-space: normal;
  }

  html.staff-desktop-density .navix-crm table td {
    overflow-wrap: break-word;
  }

  html.staff-desktop-density .staff-data-table th,
  html.staff-desktop-density .staff-data-table td {
    padding: .625rem .75rem;
  }

  html.staff-desktop-density .navix-crm .btn {
    gap: 7px;
    padding: 11px 19px;
    border-radius: 10px;
  }

  html.staff-desktop-density .navix-crm .btn-sm {
    gap: 6px;
    padding: 8px 12px;
  }

  html.staff-desktop-density .navix-crm .card {
    padding: 22px;
  }

  html.staff-desktop-density .navix-crm .field {
    margin-bottom: 14px;
  }

  html.staff-desktop-density .navix-crm .field input,
  html.staff-desktop-density .navix-crm .field select,
  html.staff-desktop-density .navix-crm .field textarea {
    padding: 10px 12px;
    border-radius: 10px;
  }
}
```

- [ ] **Step 4: Allow live-pipeline actions to wrap compactly**

In `frontend/src/components/staff/pipeline/app-row.tsx`, replace:

```tsx
<div className="flex min-w-max flex-wrap items-center gap-2">
```

with:

```tsx
<div className="flex flex-wrap items-center gap-2">
```

- [ ] **Step 5: Run the focused Playwright test and verify GREEN**

Run: `cd frontend; npx playwright test e2e/staff-density.spec.ts`

Expected: PASS, two tests and zero failures.

- [ ] **Step 6: Run focused and full frontend verification**

Run in `frontend`:

```bash
npm test -- src/lib/staff-density.test.ts
npm test
npx tsc --noEmit
npm run lint
npm run build
```

Expected: all commands exit 0. If the documented Next.js `/staff/admin/staff` Client Manifest environment issue recurs, record that exact build failure separately; do not represent it as a code failure or as a successful build.

- [ ] **Step 7: Review graph blast radius and working-tree scope**

Run `code-review-graph update`, then `code-review-graph detect-changes --brief` (or MCP equivalents) for:

- `frontend/src/lib/staff-density.ts`
- `frontend/src/lib/staff-density.test.ts`
- `frontend/src/components/staff/staff-shell.tsx`
- `frontend/src/app/globals.css`
- `frontend/src/components/staff/pipeline/app-row.tsx`
- `frontend/e2e/staff-density.spec.ts`

Run `git diff --check` and inspect `git diff --` for only those paths. Confirm the density marker is reversible, the media query is desktop-only, table data is not hidden, and unrelated dirty files are absent.

- [ ] **Step 8: Commit the compact table system**

```bash
git add frontend/e2e/staff-density.spec.ts frontend/src/app/globals.css frontend/src/components/staff/pipeline/app-row.tsx
git commit -m "feat: compact staff tables on desktop"
```
