import { test, expect } from "@playwright/test";
import { loginBorrower, uniqueMobile } from "./_fixtures";

test.describe("repay", () => {
  test("payment settings are served from the backend (payee source)", async ({ page }) => {
    await loginBorrower(page, uniqueMobile());
    const res = await page.request.get("/api/payment-settings");
    expect(res.ok()).toBeTruthy();
    // BFF may return the ApiResponse envelope ({data:{...}}) or the unwrapped object.
    const body = (await res.json()) as { upiId?: string; data?: { upiId?: string } };
    const upiId = body.data?.upiId ?? body.upiId;
    // Seeded payee block (V18) — the repay screen reads THIS, not a hardcoded literal.
    expect(upiId, "payment settings should expose a UPI id").toBeTruthy();
  });

  test("repay page renders gracefully for a borrower (with or without an active loan)", async ({ page }) => {
    await loginBorrower(page, uniqueMobile());
    await page.goto("/repay");
    await expect(page).toHaveURL(/\/repay/);
    // No React error boundary / crash.
    await expect(page.locator("body")).not.toContainText(/Something went wrong|Application error/i);
    // Either the payee/repay UI, or a graceful "no active loan" empty state, renders.
    await expect(
      page.getByText(/repay|outstanding|UPI|no active loan|nothing to repay/i).first(),
    ).toBeVisible();
  });

  // A full record-repayment flow needs a borrower with an ACTIVE loan (seed via
  // scripts/populate-demo-data.ps1 or walk the lifecycle); covered in the manual QA checklist.
  test.fixme("record a repayment against an active loan", async () => {});
});
