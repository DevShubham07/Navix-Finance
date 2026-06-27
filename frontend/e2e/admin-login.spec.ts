import { test, expect } from "@playwright/test";

/**
 * Real staff email+password login + the seeded admin + admin-creates-staff.
 *
 * Runs against a deployed (or local) stack via E2E_BASE_URL. The backend must have
 * the V19 admin seeded (navixfinance@gmail.com / demo). Drives the actual login
 * FORM (no role-picker) so it proves the demo role-picker is gone.
 */

const ADMIN_EMAIL = "navixfinance@gmail.com";
const ADMIN_PASSWORD = "demo";

test.describe("staff email + password login", () => {
  test("login page is a real email+password form — no role picker", async ({ page }) => {
    await page.goto("/staff/login");
    await expect(page.getByLabel("Email")).toBeVisible();
    await expect(page.getByLabel("Password")).toBeVisible();
    await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();
    // the old demo role-picker personas + label must be gone
    await expect(page.getByText("Ananya Rao")).toHaveCount(0);
    await expect(page.getByText("demo — no password")).toHaveCount(0);
  });

  test("admin signs in with email + password and reaches the dashboard", async ({ page }) => {
    await page.goto("/staff/login");
    await page.getByLabel("Email").fill(ADMIN_EMAIL);
    await page.getByLabel("Password").fill(ADMIN_PASSWORD);
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page).toHaveURL(/\/staff\/dashboard/, { timeout: 20_000 });
  });

  test("wrong password shows an error and stays on the login page", async ({ page }) => {
    await page.goto("/staff/login");
    await page.getByLabel("Email").fill(ADMIN_EMAIL);
    await page.getByLabel("Password").fill("definitely-wrong");
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page.getByText(/invalid email or password/i)).toBeVisible({ timeout: 20_000 });
    await expect(page).toHaveURL(/\/staff\/login/);
  });

  test("admin creates a staff account that can then sign in", async ({ page }) => {
    const email = `e2e-pw-${Date.now()}@navix.test`;
    const password = "pass1234";

    // sign in as admin
    await page.goto("/staff/login");
    await page.getByLabel("Email").fill(ADMIN_EMAIL);
    await page.getByLabel("Password").fill(ADMIN_PASSWORD);
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page).toHaveURL(/\/staff\/dashboard/, { timeout: 20_000 });

    // create a staff account via the admin form (role defaults to KYC_APPROVER)
    await page.goto("/staff/admin/staff");
    await page.getByPlaceholder("person@navix.example").fill(email);
    await page.getByPlaceholder("Full name").fill("PW Test Staff");
    await page.getByPlaceholder(/Set a password/i).fill(password);
    await page.getByRole("button", { name: /create staff/i }).click();
    await expect(page.getByText(/staff account created/i)).toBeVisible({ timeout: 20_000 });

    // the newly-created staff can authenticate (via the BFF → backend)
    const res = await page.request.post("/api/auth/staff/login", { data: { email, password } });
    expect(res.ok()).toBeTruthy();
  });
});
