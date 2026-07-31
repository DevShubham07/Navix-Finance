import { test, expect } from "@playwright/test";
import { uniqueMobile } from "./_fixtures";

/**
 * The 9-step verified wizard. We drive the deterministic, automatable steps
 * (mobile-otp via dev-echo → set-password → email) and assert each renders + advances. The
 * external / device steps are `test.fixme` (see comments) and are covered by the
 * manual QA checklist instead.
 */
test.describe("onboarding wizard", () => {
  test("mobile-otp step verifies (dev-echo) and advances to the set-password step", async ({ page }) => {
    const mobile = uniqueMobile();
    await page.goto("/signup/mobile-otp");

    await page.getByPlaceholder("98765 43210").fill(mobile);
    await page.getByRole("button", { name: /send code/i }).click();

    const devCode = page.getByText(/Dev code:/);
    await expect(devCode).toBeVisible();
    const code = ((await devCode.textContent()) ?? "").match(/\d{6}/)?.[0];
    expect(code).toMatch(/^\d{6}$/);

    for (let i = 0; i < 6; i++) {
      await page.getByLabel(`Digit ${i + 1}`).fill(code![i]);
    }
    // OtpInput auto-submits → creates the DRAFT → advances to the OPTIONAL set-password step
    // (added with the V34 password-auth work; it sits between mobile-otp and email).
    await expect(page).toHaveURL(/\/signup\/set-password/, { timeout: 20_000 });
    await expect(page.getByRole("button", { name: /skip for now/i })).toBeVisible();

    // Setting a password is the primary path — it also covers POST /api/auth/borrower/set-password
    // (authed via the session the OTP step just established) and its 10-char alnum policy.
    await page.getByLabel("Password", { exact: true }).fill("Passw0rdTest1");
    await page.getByLabel("Confirm password").fill("Passw0rdTest1");
    await page.getByRole("button", { name: /set password & continue/i }).click();
    await expect(page).toHaveURL(/\/signup\/email/, { timeout: 20_000 });
  });

  test("email step renders its inputs", async ({ page }) => {
    // (Standalone render check — does not depend on the prior test's session.)
    await page.goto("/signup/email");
    await expect(page.locator("form, .form-card, input").first()).toBeVisible();
  });

  // External redirect+poll (DigiLocker consent on an external host) — not E2E-automatable here.
  test.fixme("digilocker init → poll → complete", async () => {});
  // getUserMedia camera capture + Fintrix liveness — flaky to automate against the live provider.
  test.fixme("selfie capture + liveness", async () => {});
  // Full PAN→bureau→salary→penny-drop→agreement→submit walk needs a fresh DB to avoid the
  // V12 PAN-uniqueness collision on the shared RDS (sandbox PAN QVEPS0901K is single-valued).
  test.fixme("full happy path to KYC_PENDING (needs a clean DB)", async () => {});
});
