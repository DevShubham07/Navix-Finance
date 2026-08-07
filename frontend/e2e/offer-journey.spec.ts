import { test, expect, type ConsoleMessage } from "@playwright/test";
import { loginBorrower } from "./_fixtures";

/**
 * The post-sanction offer journey (`/loan/*`, revamp.md Phase 3).
 *
 * These exist because a regression took out the entire journey and nothing caught it: the V47
 * dynamic step-list change gave `useSyncExternalStore` a `getSnapshot` that rebuilt its array on
 * every call. React compares snapshots by reference, so each render produced a "new" value —
 * "Maximum update depth exceeded" inside `LoanLayout`, which wraps EVERY `/loan/*` route. All of
 * them rendered a blank page. Server-side the routes still returned HTTP 200, so nothing short of
 * a real browser could see it.
 *
 * A borrower with a SANCTIONED application is seeded by the demo data; the mobile below belongs to
 * one. If the fixture is missing the test skips rather than failing for the wrong reason.
 */

const SANCTIONED_BORROWER_MOBILE = "9822236427";

/** Collect page errors + console errors so a silent render crash cannot pass as "renders". */
function watchForErrors(page: import("@playwright/test").Page): string[] {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(`pageerror: ${e.message}`));
  page.on("console", (m: ConsoleMessage) => {
    if (m.type() === "error") errors.push(`console: ${m.text()}`);
  });
  return errors;
}

test.describe("offer journey", () => {
  test("every /loan/* route renders without a render-loop crash", async ({ page }) => {
    const errors = watchForErrors(page);
    await loginBorrower(page, SANCTIONED_BORROWER_MOBILE);

    for (const route of [
      "/loan/amount",
      "/loan/repayment-date",
      "/loan/summary",
      "/loan/sanctioned",
      "/loan/disbursal-account",
    ]) {
      await page.goto(route);
      // The layout paints its wizard chrome; a crashed layout paints nothing at all.
      await expect(page.locator("body")).not.toBeEmpty();
      const text = (await page.locator("body").innerText()).trim();
      expect(text.length, `${route} rendered an empty body`).toBeGreaterThan(40);
    }

    console.log(`[offer-journey] console/page errors seen: ${errors.length}`);
    for (const e of errors) console.log(`   ${e}`);
    const fatal = errors.filter(
      (e) => /Maximum update depth|getServerSnapshot|infinite loop/i.test(e),
    );
    expect(fatal, `render-loop errors: ${fatal.join(" | ")}`).toHaveLength(0);

    // The wizard chrome paints its progress. "Step N of M" — M is the borrower's OWN journey
    // length, which for a re-apply is shorter than the full eleven.
    // Asserted here rather than in a second test on purpose: each login burns an OTP send, and the
    // per-mobile send cap made a separate test flaky in a full-suite run while passing alone.
    await page.goto("/loan/amount");
    await expect(page.getByText(/Step \d+ of \d+/i)).toBeVisible({ timeout: 15_000 });
  });
});
