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

    // create a staff account via the admin form (role defaults to CREDIT_EXECUTIVE)
    await page.goto("/staff/admin/staff");
    await page.getByPlaceholder("person@navix.example").fill(email);
    await page.getByPlaceholder("Full name").fill("PW Test Staff");
    await page.getByPlaceholder(/Set a password/i).fill(password);
    await page.getByRole("button", { name: /create staff/i }).click();
    await expect(page.getByText(/staff account created/i)).toBeVisible({ timeout: 20_000 });

    // Find the new account's id WHILE STILL ADMIN — /api/staff/users is ADMIN-only, and the login
    // below replaces this context's session with the new CREDIT_EXECUTIVE's.
    const listed = await page.request.get("/api/staff/users");
    expect(listed.ok(), "staff list should be readable as admin").toBeTruthy();
    const body = (await listed.json()) as { data?: Array<{ id: number; email: string }> };
    const created = (body.data ?? []).find((s) => s.email === email);
    expect(created, "the account just created should be listed").toBeTruthy();

    // the newly-created staff can authenticate (via the BFF → backend)
    const res = await page.request.post("/api/auth/staff/login", { data: { email, password } });
    expect(res.ok()).toBeTruthy();

    // Clean up after ourselves — back as admin, since we are now signed in as the new staffer.
    // Without this each run left a permanent ACTIVE CREDIT_EXECUTIVE behind, and the Credit Head's
    // "assign an executive" picker (which lists ACTIVE staff) filled up with fourteen identical
    // "PW Test Staff" entries nobody could tell apart. Disabling is enough — the picker filters on
    // ACTIVE — and it keeps any audit rows the account owns.
    const backAsAdmin = await page.request.post("/api/auth/staff/login", {
      data: { email: ADMIN_EMAIL, password: ADMIN_PASSWORD },
    });
    expect(backAsAdmin.ok(), "should be able to sign back in as admin").toBeTruthy();
    const removed = await page.request.delete(`/api/staff/users/${created!.id}`);
    expect(removed.ok(), "cleanup should disable the created account").toBeTruthy();
  });
});
