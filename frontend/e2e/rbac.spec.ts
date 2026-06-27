import { test, expect } from "@playwright/test";
import { loginStaff } from "./_fixtures";

// The credit-queue panels are wrapped in <PermissionGate permission="loan:approve|review">,
// so the "Credit head decision" StatusQueue title only renders for an authorised role.
test.describe("RBAC", () => {
  test("KYC approver cannot see the credit-head queue", async ({ page }) => {
    await loginStaff(page, "KYC_APPROVER");
    await page.goto("/staff/credit/queue");
    await expect(page.getByRole("heading", { name: "Credit queue" })).toBeVisible();
    await expect(page.getByText("Credit head decision")).toHaveCount(0);
  });

  test("ADMIN sees the credit-head queue", async ({ page }) => {
    await loginStaff(page, "ADMIN");
    await page.goto("/staff/credit/queue");
    await expect(page.getByText("Credit head decision")).toBeVisible();
  });

  test("non-KYC role sees the no-access notice on KYC approvals", async ({ page }) => {
    await loginStaff(page, "ACCOUNTANT");
    await page.goto("/staff/kyc-approvals");
    await expect(page.getByText("Only KYC approvers can clear KYC.")).toBeVisible();
  });

  test("KYC approver sees the KYC clearance queue", async ({ page }) => {
    await loginStaff(page, "KYC_APPROVER");
    await page.goto("/staff/kyc-approvals");
    await expect(page.getByText("Applications awaiting KYC clearance")).toBeVisible();
  });
});
