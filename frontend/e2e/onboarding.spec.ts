import { test, expect } from "@playwright/test";
import { uniqueMobile } from "./_fixtures";

/**
 * The Phase-1 ten-screen intake (revamp.md). Rewritten for the revamp: the old spec drove
 * `/signup/mobile-otp`, a route Phase 1 retired to a redirect stub, and set a 13-character
 * password that the 6–10 policy (decision 23) now rejects.
 *
 * We drive the deterministic screens — start → otp (dev-echo) → employment → set-password →
 * employer — and assert each gates and advances. Provider/device steps stay `test.fixme`.
 */

const PAN_POOL = "ABCDEFGHIJKLMNPQRSTUVWXYZ";
/** A syntactically valid, unique-per-run PAN so reruns don't collide on identity uniqueness. */
function uniquePan(): string {
  const r = () => PAN_POOL[Math.floor(Math.random() * PAN_POOL.length)];
  return `${r()}${r()}${r()}P${r()}${String(Math.floor(1000 + Math.random() * 9000))}${r()}`;
}

/** Screen 1: fill the identity fields and accept both mandatory confirmations. */
async function completeStartScreen(page: import("@playwright/test").Page, mobile: string) {
  await page.goto("/signup/start");
  await page.getByPlaceholder("ABCDE1234F").fill(uniquePan());
  await page.getByPlaceholder("9876543210").fill(mobile);
  // Ticking the T&C box opens the modal; only "Agree" inside it actually sets the flag.
  await page.getByRole("checkbox").first().click();
  await page.getByRole("button", { name: "Agree" }).click();
  await page.getByRole("checkbox").nth(1).check();
}

test.describe("onboarding wizard", () => {
  /**
   * Decision 19: the PEP declaration is mandatory and "Continue stays disabled until ticked".
   * This regressed — the button was gated only on `busy`, so it looked available and rejected the
   * borrower on click instead.
   */
  test("Continue is disabled until BOTH confirmations are ticked", async ({ page }) => {
    await page.goto("/signup/start");
    const cont = page.getByRole("button", { name: /continue/i });
    await expect(cont).toBeDisabled();

    await page.getByPlaceholder("ABCDE1234F").fill(uniquePan());
    await page.getByPlaceholder("9876543210").fill(uniqueMobile());
    await expect(cont).toBeDisabled(); // fields alone are not enough

    // Decline must leave the box unticked and the button disabled.
    await page.getByRole("checkbox").first().click();
    await page.getByRole("button", { name: "Decline" }).click();
    await expect(page.getByRole("checkbox").first()).not.toBeChecked();
    await expect(cont).toBeDisabled();

    // Agree ticks T&C, but PEP is still outstanding.
    await page.getByRole("checkbox").first().click();
    await page.getByRole("button", { name: "Agree" }).click();
    await expect(page.getByRole("checkbox").first()).toBeChecked();
    await expect(cont).toBeDisabled();

    await page.getByRole("checkbox").nth(1).check();
    await expect(cont).toBeEnabled();
  });

  /** The OTP screen must never claim the send FAILED while it is still in flight. */
  test("OTP screen does not report a failed send before the request resolves", async ({ page }) => {
    await completeStartScreen(page, uniqueMobile());
    await page.getByRole("button", { name: /continue/i }).click();
    await expect(page).toHaveURL(/\/signup\/otp/, { timeout: 20_000 });
    // Whatever the outcome, the failure copy must not be what greets them.
    await expect(page.getByText("We couldn't send an SMS.")).toHaveCount(0);
    await expect(page.getByText(/Dev code:|Code sent\.|Sending your code/)).toBeVisible();
  });

  test("start → otp (dev-echo) → employment → set-password → employer", async ({ page }) => {
    const mobile = uniqueMobile();
    await completeStartScreen(page, mobile);
    await page.getByRole("button", { name: /continue/i }).click();

    await expect(page).toHaveURL(/\/signup\/otp/, { timeout: 20_000 });
    const devCode = page.getByText(/Dev code:/);
    await expect(devCode).toBeVisible({ timeout: 15_000 });
    const code = ((await devCode.textContent()) ?? "").match(/\d{6}/)?.[0];
    expect(code).toMatch(/^\d{6}$/);
    for (let i = 0; i < 6; i++) {
      await page.getByLabel(`Digit ${i + 1}`).fill(code![i]);
    }

    // OtpInput auto-submits on the sixth digit → creates the DRAFT → employment.
    await expect(page).toHaveURL(/\/signup\/employment/, { timeout: 20_000 });
    await page.getByRole("combobox").selectOption("SALARIED");
    await page.getByRole("button", { name: /continue/i }).click();

    await expect(page).toHaveURL(/\/signup\/set-password/, { timeout: 20_000 });
    await expect(page.getByRole("button", { name: /skip for now/i })).toBeVisible();

    // Decision 23: 6–10 chars with a letter, a digit and a special character.
    await page.getByLabel("Password", { exact: true }).fill("abc123"); // no special char
    await page.getByLabel("Confirm password").fill("abc123");
    await page.getByRole("button", { name: /set password & continue/i }).click();
    // "Password must be …" is the error; the bare rule text is also on screen as helper copy.
    await expect(page.getByText(/^Password must be 6/)).toBeVisible();
    await expect(page).toHaveURL(/\/signup\/set-password/); // did not advance

    await page.getByLabel("Password", { exact: true }).fill("abc12!");
    await page.getByLabel("Confirm password").fill("abc12!");
    await page.getByRole("button", { name: /set password & continue/i }).click();

    await expect(page).toHaveURL(/\/signup\/employer/, { timeout: 20_000 });
    await expect(page.getByText("Company name")).toBeVisible();
    // Decision 13: the previous salary date is a FULL date, not a day-of-month picker.
    await expect(page.locator('input[type="date"]')).toBeVisible();
  });

  test("set-password can be skipped", async ({ page }) => {
    await completeStartScreen(page, uniqueMobile());
    await page.getByRole("button", { name: /continue/i }).click();
    await expect(page).toHaveURL(/\/signup\/otp/, { timeout: 20_000 });
    const devCode = page.getByText(/Dev code:/);
    await expect(devCode).toBeVisible({ timeout: 15_000 });
    const code = ((await devCode.textContent()) ?? "").match(/\d{6}/)?.[0];
    for (let i = 0; i < 6; i++) {
      await page.getByLabel(`Digit ${i + 1}`).fill(code![i]);
    }
    await expect(page).toHaveURL(/\/signup\/employment/, { timeout: 20_000 });
    await page.getByRole("combobox").selectOption("SALARIED");
    await page.getByRole("button", { name: /continue/i }).click();

    await expect(page).toHaveURL(/\/signup\/set-password/, { timeout: 20_000 });
    await page.getByRole("button", { name: /skip for now/i }).click();
    await expect(page).toHaveURL(/\/signup\/employer/, { timeout: 20_000 });
  });


  /**
   * Cross-device resume (revamp.md C1): a borrower who signs in on a second device and opens the
   * wizard at the top must be sent forward to the step the SERVER says they reached — not walked
   * back through screens they already completed.
   */
  test("signing in and reopening /signup/start resumes at the saved step", async ({ page }) => {
    const mobile = uniqueMobile();
    await completeStartScreen(page, mobile);
    await page.getByRole("button", { name: /continue/i }).click();
    await expect(page).toHaveURL(/\/signup\/otp/, { timeout: 20_000 });
    const devCode = page.getByText(/Dev code:/);
    await expect(devCode).toBeVisible({ timeout: 15_000 });
    const code = ((await devCode.textContent()) ?? "").match(/\d{6}/)?.[0];
    for (let i = 0; i < 6; i++) await page.getByLabel(`Digit ${i + 1}`).fill(code![i]);
    await expect(page).toHaveURL(/\/signup\/employment/, { timeout: 20_000 });
    await page.getByRole("combobox").selectOption("SALARIED");
    await page.getByRole("button", { name: /continue/i }).click();
    await expect(page).toHaveURL(/\/signup\/set-password/, { timeout: 20_000 });
    await page.getByRole("button", { name: /skip for now/i }).click();
    await expect(page).toHaveURL(/\/signup\/employer/, { timeout: 20_000 });

    // Simulate the second device: drop every client-side breadcrumb, keep only the session cookie.
    await page.evaluate(() => { localStorage.clear(); sessionStorage.clear(); });
    await page.goto("/signup/start");

    // Must land on the saved step, NOT back at the beginning and NOT short of it.
    await expect(page).toHaveURL(/\/signup\/employer/, { timeout: 20_000 });
  });

  // External redirect+poll (DigiLocker consent on an external host) — not E2E-automatable here.
  test.fixme("digilocker init → poll → complete", async () => {});
  // getUserMedia camera capture + Signzy liveness — flaky against the live provider.
  test.fixme("selfie capture + liveness", async () => {});
  // The consent screen fires PAN-206 + work-email + bureau against live providers.
  test.fixme("consent screen fires the three checks (needs provider creds)", async () => {});
});
