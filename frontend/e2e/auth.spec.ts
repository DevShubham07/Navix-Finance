import { test, expect } from "@playwright/test";
import { loginStaff, uniqueMobile } from "./_fixtures";

test.describe("auth", () => {
  test("borrower logs in through the UI with the SMS OTP (dev-echo)", async ({ page }) => {
    const mobile = uniqueMobile();
    await page.goto("/login");

    await page.getByPlaceholder("98765 43210").fill(mobile);
    await page.getByRole("button", { name: /send code/i }).click();

    // dev-echo surfaces the code in the UI as "Dev code: NNNNNN"
    const devCode = page.getByText(/Dev code:/);
    await expect(devCode).toBeVisible();
    const code = ((await devCode.textContent()) ?? "").match(/\d{6}/)?.[0];
    expect(code, "a 6-digit dev code should be shown").toMatch(/^\d{6}$/);

    for (let i = 0; i < 6; i++) {
      await page.getByLabel(`Digit ${i + 1}`).fill(code![i]);
    }
    // OtpInput auto-submits on the 6th digit; land on the dashboard.
    await expect(page).toHaveURL(/\/dashboard/, { timeout: 15_000 });
  });

  test("staff role-pick login lands on the dashboard", async ({ page }) => {
    await page.goto("/staff/login");
    await page.getByRole("button").filter({ hasText: "Administrator" }).click();
    await expect(page).toHaveURL(/\/staff\/dashboard/, { timeout: 15_000 });
    await expect(page.getByText(/Welcome,/)).toBeVisible();
  });

  test("unauthenticated /staff/* redirects to the staff login", async ({ page }) => {
    await page.goto("/staff/dashboard");
    await expect(page).toHaveURL(/\/staff\/login/);
  });

  test("logout clears the staff session", async ({ page }) => {
    expect(await loginStaff(page, "ADMIN")).toBeTruthy();
    await page.request.post("/api/auth/staff/logout");
    await page.goto("/staff/dashboard");
    await expect(page).toHaveURL(/\/staff\/login/);
  });

  test("a borrower API call without a session is rejected (401)", async ({ page }) => {
    const res = await page.request.get("/api/borrower/applications/mine");
    expect(res.status()).toBe(401);
  });
});
