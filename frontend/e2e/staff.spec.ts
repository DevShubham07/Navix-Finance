import { test, expect } from "@playwright/test";
import { loginStaff } from "./_fixtures";

test.describe("staff console", () => {
  test("admin payment-settings editor renders", async ({ page }) => {
    await loginStaff(page, "ADMIN");
    await page.goto("/staff/admin/payment-settings");
    await expect(page.getByRole("heading", { name: "Payment settings" })).toBeVisible();
    // The form exposes the editable payee fields + the QR/PDF upload controls.
    await expect(page.getByText(/UPI/i).first()).toBeVisible();
  });

  test("live applications renders the KYC approver's queue shell", async ({ page }) => {
    await loginStaff(page, "KYC_APPROVER");
    await page.goto("/staff/applications");
    await expect(page.getByRole("heading", { name: "Live applications" })).toBeVisible();
    await expect(page.getByText("Applications awaiting KYC clearance")).toBeVisible();
  });

  test("dashboard greets the signed-in staff member", async ({ page }) => {
    await loginStaff(page, "ADMIN");
    await page.goto("/staff/dashboard");
    await expect(page.getByText(/Welcome,\s*Meera/)).toBeVisible();
  });

  test("customers roll-up is reachable", async ({ page }) => {
    await loginStaff(page, "ADMIN");
    await page.goto("/staff/customers");
    // Page renders without a permission wall for an authorised role.
    await expect(page).toHaveURL(/\/staff\/customers/);
    await expect(page.locator("body")).not.toContainText("don't have access");
  });
});
